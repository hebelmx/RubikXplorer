---
title: 'Solution Playback in 3D or on the 2D net'
type: 'feature'
created: '2026-11-17'
baseline_revision: '625c7f1ab2bd4c413cee971c38bc4c1c9d4c4454'
status: 'done'
review_loop_iteration: 0
followup_review_recommended: false
context:
  - '{project-root}/_bmad-output/implementation-artifacts/epic-12-context.md'
  - '{project-root}/_bmad-output/planning-artifacts/sprint-change-proposal-2026-11-17-playback-view-and-move-glyphs.md'
warnings: ['oversized']
deferred: []
---

<intent-contract>

## Intent

**Problem:** Solution Playback always draws the 2D net (`SolutionPlayback.kt:86` builds a `Net2DRenderer`), while DESIGN.md puts the 3D cube on the stage and Lessons already play on `Cube3DRenderer` (GitHub #175, Story 12.14).

**Approach:** Keep both adapters on Solution Playback behind `CubeRenderer` and let the Learner pick one with a toolbar view switch. The choice is one nullable preference written through `UpdatePreference` (FR-19); a switch builds the other adapter at the committed state (`Snap`) with the same playback position and dispatches nothing to the session.

**Refs (binding, verbatim from the story):** FR-16, FR-19 · NFR-1, NFR-2, NFR-7 · AD-4, AD-17, AD-20 · UX-DR4, UX-DR6, UX-DR29 · Stories 5.4, 5.5, 12.2 · GitHub #175.

## Boundaries & Constraints

**Always:**
- AD-4: the view switch builds the other adapter at `controller.state` (the committed state of the current step), shown at once with no animation; the transport (position, play/pause, speed) and the session are untouched. A switch never dispatches a `SessionEvent`; View Rotation stays renderer-local.
- AD-20: one `NetPlayback`/`PlaybackController` drives either adapter; notes come only from `MoveNote`.
- AD-17: the 3D view keeps the Story 5.5 description and *Read faces* action (`Cube3DRenderer.Content` with `summary = null`).
- PO decisions / SCP §5 C1 (settled, not new choices): one preference `playbackView: PlaybackView?`, default `null`. While `null` the view follows the mode (virtual → 3D, Physical-cube Mode → 2D net), including when the mode changes mid-playback. Once set it wins in both modes and across restarts. *Reset preferences* (`PreferenceStore.reset()` → `Preferences.DEFAULT`) unsets it.
- The switch is an icon button on a toolbar row along the stage's top edge (DESIGN.md Cube stage), test tag `playback:viewSwitch`. Its icon and TalkBack label name the target view: "Show the 2D net" (`btn.playback.show-net`) in 3D, "Show the 3D cube" (`btn.playback.show-cube`) on the net, from `en.json`. In 3D the toolbar also carries the shared `ViewControls` (reset view, DESIGN.md "reset-view affordance top-right").
- 3D arrow: `arrow = state.currentMove` (the same Move as the transport/physical Move label), `reduceMotion` = the state's reduce-motion (static arrow).
- Physical-cube Mode keeps the frame label, certainty chip, SEEN strip, Quick Check, *Done* and Back in either view.
- Preference persistence: new nullable column, Room version 8 → 9 by `AutoMigration`, exported schema `9.json` committed.

**Never:** a new port, module, persisted entity, feature flag or `SessionEvent`; Learner text in `strings.xml`; the Move strip, scrubber or Move pictures (Story 12.15); changing `Cube3DRenderer`/`Net2DRenderer` internals beyond what is needed; editing planning artifacts; measuring FPS (PO device session).

## I/O & Edge-Case Matrix

| Scenario | Input / State | Expected Output / Behavior | Error Handling |
|----------|--------------|---------------------------|----------------|
| Fresh, virtual | `playbackView = null`, virtual session | 3D cube (`cube3d`), switch says "Show the 2D net" | — |
| Fresh, physical | `null`, physical session (or restored mid-follow) | 2D net (`net`), switch says "Show the 3D cube" | — |
| Mode change, unset | `null`, *I'm following on my cube* | stage becomes the net at the committed state; position kept | — |
| Saved wins | `CUBE_3D` saved, physical | 3D cube; switch → `NET_2D` saved, applies in virtual too | — |
| Mid-turn switch | playing / several Animate queued | new view shows `controller.state` at once, `position`, `isPlaying`, `speed` unchanged, zero events | — |
| Reset prefs | saved `NET_2D`, *Reset preferences* | `playbackView == null`; next open follows the mode | — |
| Save fails | `UpdatePreference` save `Failed` | in-memory choice still applies this run (existing `PreferenceStore` behavior) | no crash |

</intent-contract>

## Code Map

- `core/application/src/main/kotlin/dev/cubentia/application/preference/Preferences.kt` -- `Preferences` + sealed `Preference` + `with()`; add `enum class PlaybackView { CUBE_3D, NET_2D }`, field `playbackView: PlaybackView? = null`, `Preference.PlaybackViewChoice(value)`.
- `core/application/src/main/kotlin/dev/cubentia/application/preference/UpdatePreference.kt` -- single writer; unchanged, `reset()` already returns DEFAULT.
- `core/application/src/test/.../preference/PreferencesTest.kt`, `UpdatePreferenceTest.kt` -- extend.
- `app/src/main/kotlin/dev/cubentia/app/data/PreferencesEntity.kt` -- add `@ColumnInfo(defaultValue = "NULL") val playback_view: String? = null`, map both ways (unknown name → null). Pattern: `onboarded` (commit ebac87f).
- `app/src/main/kotlin/dev/cubentia/app/data/RubikDatabase.kt` -- `version = 9`, `AutoMigration(from = 8, to = 9)`; `app/schemas/dev.cubentia.app.data.RubikDatabase/9.json` is generated by the build.
- `app/src/test/kotlin/dev/cubentia/app/data/SessionDatabaseMigrationTest.kt` -- add 8→9 keeps preferences row, `playback_view` null; `RoomPreferencesRepositoryTest.kt` round-trip.
- `app/src/main/kotlin/dev/cubentia/app/preferences/PreferenceStore.kt`, `LocalPreferences.kt` (`LocalPreferenceStore`) -- `update()`/`reset()` reused; `PreferenceStoreTest.kt` add reset-clears case.
- `app/src/main/kotlin/dev/cubentia/app/ui/playback/PlaybackTransport.kt` -- `NetPlayback(start, moves, reduceMotion, renderer, oldestLiveRevision)`: `renderer` is a `val`; make it Compose-observable and replaceable (e.g. `replaceRenderer(build: (Facelets) -> CubeRenderer)` that builds at `controller.state.toFacelets()` and clears `animatedIndex`). Demonstrations keep working unchanged.
- `app/src/main/kotlin/dev/cubentia/app/ui/solve/SolutionPlayback.kt` -- `SolutionPlaybackState` (renderer typed `Net2DRenderer` today, `alignToExpected` uses `scrubTo`, `currentMove` override for physical) and `SolutionPlayback` composable (stage `Box` sized by `NET_ASPECT`, `FrameLabel` overlay, `CertaintyChip`, `SeenStrip`, `PhysicalPlaybackControls`). Add `view`, `showView(view)`; renderer typed `CubeRenderer`; 3D stage is square.
- `app/src/main/kotlin/dev/cubentia/app/ui/solve/SolvePlaybackScreen.kt` -- hosts the state; already sets `testTagsAsResourceId = true` (benchmarks see `net`, `cube3d`, `playback:viewSwitch`).
- `app/src/main/kotlin/dev/cubentia/app/ui/lesson/DemonstrationPlayer.kt` -- reference pattern: `Cube3DRenderer` + `NetPlayback`, `ViewControls(state.renderer)` toolbar, `renderer.Content(style, …, arrow, reduceMotion)`.
- `app/src/main/kotlin/dev/cubentia/app/render/Cube3DRenderer.kt` -- `Content(style, modifier, onMoveIntent, emphasis, arrow, reduceMotion, summary, freeViewRotation)`, tag `cube3d`, `RenderedArrowKey`, *Read faces* custom action; `Net2DRenderer.kt:161` tag `net`.
- `app/src/main/kotlin/dev/cubentia/app/ui/freecube/ViewControls.kt` -- `ViewControls(renderer: Cube3DRenderer)`; `ToolbarButton` style for the switch.
- `core/domain/src/main/resources/content/strings/en.json` -- add the two `btn.playback.*` keys.
- `app/src/main/res/drawable/` -- add `ic_view_cube.xml`, `ic_view_net.xml` (24dp vectors, ink tint like `ic_reset_view.xml`).
- `app/src/test/kotlin/dev/cubentia/app/ui/solve/SolutionPlaybackTest.kt` -- Robolectric compose tests on `state.renderer.renderedFacelets()`; existing tests may pin `NET_2D` if 3D timing differs.
- `app/src/androidTest/kotlin/dev/cubentia/app/render/Cube3DRenderSyncTest.kt` -- existing `createComposeRule` + `mainClock` drain pattern.
- `macrobenchmark/src/main/kotlin/dev/cubentia/macrobenchmark/Net2DFrameTimingBenchmark.kt` -- `openSolutionPlayback()`, `requireNet()` with `NET_TOGGLE` regex fallback; `Cube3DFrameBenchmark.kt` + `GlFrameMetric(Mode.FACE_TURNS, n)` (one `Cube3D.turn` per tap, `TAP_GAP_MS = 450`).

## Tasks & Acceptance

**Execution:**
- `core/application/.../preference/Preferences.kt` + tests -- add `PlaybackView`, nullable field, `Preference.PlaybackViewChoice` -- FR-19 single writer.
- `app/.../data/PreferencesEntity.kt`, `RubikDatabase.kt`, `app/schemas/.../9.json`, migration + repository tests -- persist the column -- survives restarts.
- `app/src/main/res/drawable/ic_view_cube.xml`, `ic_view_net.xml`; `en.json` -- icons and labels for the switch.
- `app/.../ui/playback/PlaybackTransport.kt` -- replaceable, observable renderer on `NetPlayback` building at the committed state -- AD-4 Snap.
- `app/.../ui/solve/SolutionPlayback.kt`, `SolvePlaybackScreen.kt` -- resolve the view (saved ?: mode default) each composition, toolbar row with `ViewControls` (3D) and `playback:viewSwitch` writing `Preference.PlaybackViewChoice` via `LocalPreferenceStore`, stage draws the active adapter (3D with arrow + reduce motion), physical widgets unchanged.
- `app/src/test/.../ui/solve/SolutionPlaybackTest.kt` (+ new `SolutionPlaybackViewTest.kt` if cleaner) -- JVM tests for the I/O matrix, AC labels/semantics and zero events on switch.
- `app/src/androidTest/.../render/Cube3DRenderSyncTest.kt` -- add Solution Playback cases: 3D `renderedFacelets()` equals committed state after forward, back, restart and each speed change; mid-turn switch 3D→net and net→3D; switch in Physical-cube Mode with *Done*, Back and a Quick Check result still shown.
- `macrobenchmark/.../Net2DFrameTimingBenchmark.kt` -- `requireNet()` clicks `By.res("playback:viewSwitch")` when `net` is absent; delete `NET_TOGGLE`. Extract the Solution Playback navigation into a shared file in the module; add a 3D Solution Playback benchmark (e.g. `Cube3DFrameBenchmark.solutionPlaybackSteps`) that selects `cube3d` through the same tag, pauses, and taps Step forward `n` times `TAP_GAP_MS` apart under `GlFrameMetric(Mode.FACE_TURNS, n)`.

**Acceptance Criteria:**
- Given no saved playback view, when Solution Playback opens, then virtual shows the 3D cube and Physical-cube Mode shows the 2D net.
- Given the view switch, when tapped, then the stage swaps views, `PlaybackViewChoice` is saved through `UpdatePreference`, the saved choice wins in both modes after a restart, and *Reset preferences* restores the first AC's default.
- Given playback mid-turn (animating or several queued), when the view is switched, then the new view's `renderedFacelets()` equals the committed state at once, position is unchanged, no `SessionEvent` is dispatched, and frame label, certainty chip and SEEN strip are unchanged.
- Given the 3D view with a current step, then `RenderedArrowKey` carries the current Move (progress 1 under reduce motion).
- Given TalkBack, then `cube3d` reads the Story 5.5 description with a *Read faces* action, and the switch's content description is the `en.json` text for the target view.
- Given `./gradlew :app:assembleDebugAndroidTest :macrobenchmark:assemble`, then the new `Cube3DRenderSyncTest` cases and benchmarks compile; `Net2DFrameTimingBenchmark` has no `2D|Net` text fallback.

## Spec Change Log

## Review Triage Log

### 2026-11-17 — Review pass
- verdicts: 24 findings — high 0, medium 1, low 17, false 5, maybe-false 1
- findings:
  - `[low]` `[reject]` Blind: 3D playback benchmark measures 4 steps without checking the restored Solution has 4 Moves — the run fails loudly ("Missing Solution Playback Step forward"); the fix adds a counter check for an unlikely restored state.
  - `[low]` `[reject]` Blind: shared navigation times out on a restored physical session — pre-existing behavior moved verbatim from `Net2DFrameTimingBenchmark`, fails loudly with an explicit message.
  - `[low]` `[reject]` Blind: benchmark ignores the app's Reduce Motion preference — with it on, `GlFrameMetric` throws naming the missing `Cube3D.turn` slice (loud); same gap as the Story 12.2 benchmarks.
  - `[low]` `[reject]` Blind: renderer animation-speed preference is not normalized — same workload assumption as the existing 12.2 `faceTurns`; PO device session runs on a default install.
  - `[low]` `[reject]` Blind: speed-change sync test steps manually — AC6 asks render sync after speed changes, which the test checks; autoplay speed is `PlaybackController`'s own tested behavior.
  - `[low]` `[patch]` Blind: Restart in `Cube3DRenderSyncTest` is exercised from position 0 — now steps to position 2 first, then Restart lands at the first-Move boundary.
  - `[maybe-false]` `[reject]` Blind: net→3D mid-turn test does not prove the net still had a turn in flight — would need on-device timing of `Net2DRenderer`; if true only low.
  - `[low]` `[reject]` Blind: short-phone layout test covers only the net — visual acceptance of the 3D stage is in the phase 5 device session; adding layout cases is more than a direct correction.
  - `[low]` `[reject]` Blind: no test reopens a persisted store into the screen — Room round trip, 8→9 migration and `collectAsState` → `showView` path are each tested.
  - `[medium]` `[patch]` Edge: a new `Cube3DRenderer` at the same composition slot (re-solve via *Use my cube* with a saved 3D view) reused the old adapter's remembered GL surface/renderer, so `meshVersion` could collide and keep stale geometry — wrapped the 3D `Content` in `key(renderer)`.
  - `[low]` `[reject]` Edge: arrow uses `currentMove`, not `presentedMove`, during Step Back — AC4 says the arrow shows on the current Move and the spec ties it to the transport label; once idle both agree.
  - `[low]` `[reject]` Edge: Solution shorter than four Moves in the benchmark — same root cause as the first Blind row; loud failure.
  - `[low]` `[reject]` Edge: app Reduce Motion with nonzero animator scale — same root cause as the third Blind row; loud failure.
  - `[low]` `[patch]` Verification: 3D arrow checked only at the first Move — new `the3DArrowFollowsTheCurrentMoveAndResetViewKeepsThePosition` asserts the arrow on Move 2 (static, progress 1).
  - `[low]` `[patch]` Verification: playback reset-view button untested — same new test rotates the 3D view, taps `stage:resetView`, checks `ViewOrbit.DEFAULT` and position unchanged.
  - `[low]` `[patch]` Verification: rebuilt physical renderer not checked before drain — `aStateRebuiltMidFollowStartsAtTheHostExpectedStep` now asserts `renderedFacelets()` equals the Expected State on construction, for the net and an explicit 3D view.
  - `[false]` `[reject]` Intent: view re-derived on mid-session mode change (R1b) — settled by SCP §5 C1 ("unset … the view follows the mode") and recorded in the spec.
  - `[low]` `[reject]` Intent: AC2 not proven through the Settings row or a process restart — Settings already calls `PreferenceStore.reset()` (unchanged), and restart persistence is covered at the repository/migration level.
  - `[low]` `[reject]` Intent: queued-turn switch proven on JVM state only — the instrumented mid-turn test covers the on-screen 3D adapter; runs in the device session.
  - `[low]` `[reject]` Intent: TalkBack focus order not exercised — semantics are tested; the TalkBack walk is in the PO device session.
  - `[false]` `[reject]` Intent: instrumented tests not yet run — the intent assigns running them to the PO device session; they compile (`assembleDebugAndroidTest`).
  - `[false]` `[reject]` Intent: no input-latency metric for 3D playback — `GlFrameMetric(FACE_TURNS)` reports `inputToFrameP50Ms`/`MaxMs` from each Step-forward tap.
  - `[false]` `[reject]` Intent: `SolvePlaybackScreen.kt` listed but untouched — it needed no change; the view logic sits in `SolutionPlayback.kt` and a test drives it through the screen.
  - `[false]` `[reject]` Intent: AD-17 button-for-gesture not traced — the 3D toolbar mounts `ViewControls` (button equivalents for View Rotation); no AD deviation.

## Design Notes

Play/pause is untouched by a switch: AD-4 makes the switch an adapter rebuild only ("with the same playback position, and nothing in the session changes"), so the controller keeps emitting items to the new adapter. Building a fresh adapter (instead of reusing the old one) is what AD-4 prescribes and drops the old queue by construction.

The 3D benchmark uses Step forward taps rather than Play, because `GlFrameMetric.FACE_TURNS` measures input-to-frame from the tap before each `Cube3D.turn`.

## Verification

**Commands:**
- `./gradlew spotlessApply spotlessCheck build` -- expected: green (compile, lint, JVM tests, Kover, gates, APKs).
- `./gradlew :app:assembleDebugAndroidTest :macrobenchmark:assemble` -- expected: instrumented tests and benchmarks compile.
- `grep -n "NET_TOGGLE\|2D|Net" macrobenchmark/src/main/kotlin` -- expected: no match.

**Manual checks (if no CLI):**
- PO device session (before merge): run `Cube3DRenderSyncTest`, the 3D playback benchmark on minimum and mid devices (≥ 30 / 60 FPS, input ≤ 100 ms), TalkBack walk.

## Auto Run Result

**Summary:** Solution Playback now runs on the 3D cube or the 2D net. With no saved choice it follows the mode (3D virtual, net in Physical-cube Mode); the toolbar switch (`playback:viewSwitch`, labeled from `en.json` with the target view) saves `Preference.PlaybackViewChoice` through `UpdatePreference`, which then wins in both modes across restarts until *Reset preferences*. A switch rebuilds the adapter at the committed state with position, play state and speed unchanged and no `SessionEvent`. The 3D view carries the Move arrow, the Story 5.5 description with *Read faces*, and `ViewControls`.

**Files changed:**
- `core/application/.../preference/Preferences.kt` — `PlaybackView`, nullable `playbackView`, `Preference.PlaybackViewChoice`.
- `app/.../data/PreferencesEntity.kt`, `RubikDatabase.kt`, `app/schemas/.../9.json` — `playback_view` column, Room 8→9 auto-migration.
- `app/.../ui/playback/PlaybackTransport.kt` — observable, replaceable renderer on `NetPlayback` built at the committed state.
- `app/.../ui/solve/SolutionPlayback.kt` — view resolution, toolbar switch + `ViewControls`, 3D/net stage (`key(renderer)`), rebuilt physical state built at the Expected State.
- `app/.../ui/freecube/FreeCubeScreen.kt` — `ToolbarButton` overload taking a resolved label.
- `app/src/main/res/drawable/ic_view_cube.xml`, `ic_view_net.xml`; `en.json` — icons and labels.
- Tests: `PreferencesTest`, `UpdatePreferenceTest`, `PreferenceStoreTest`, `RoomPreferencesRepositoryTest`, `SessionDatabaseMigrationTest`, `SolutionPlaybackTest`, `Cube3DRenderSyncTest` (instrumented playback cases).
- Benchmarks: `SolutionPlaybackNavigation.kt` (shared navigation, `requirePlaybackView` by exact tag), `Net2DFrameTimingBenchmark.kt` (no `2D|Net` fallback), `Cube3DFrameBenchmark.solutionPlaybackSteps`.

**Review:** 24 findings. Patched: 1 medium (`key(renderer)`), 4 low (test strengthening). Deferred: none. Rejected: 13 low, 1 maybe-false, 5 false — reasons in the triage log.

**Follow-up review recommended:** false (patched: high 0, medium 1, low 4).

**Verification:** `./gradlew spotlessApply spotlessCheck build` green (all JVM tests, lint, Kover, gates, APKs); `./gradlew :app:assembleDebugAndroidTest :macrobenchmark:assemble` green; `SolutionPlaybackTest` 32/32 after patches; `grep -rn "NET_TOGGLE\|2D|Net" macrobenchmark/src/main/kotlin` no match.

**Residual risks:** `Cube3DRenderSyncTest` playback cases, the 3D playback benchmark (≥ 30 / 60 FPS, input ≤ 100 ms) and TalkBack acceptance have not run on a device; they are the PO device-session step before merge. Running either playback benchmark saves the playback-view preference on that device.
