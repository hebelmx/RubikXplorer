---
title: 'Story 6.4: Preferences and Settings'
type: 'feature'
created: '2026-11-05'
baseline_revision: '84d4c495131e8c7e8b99a87eab0ffeb148314104'
status: 'done'
review_loop_iteration: 0
followup_review_recommended: true
context:
  - '{project-root}/_bmad-output/implementation-artifacts/epic-6-context.md'
  - '{project-root}/AGENTS.md'
warnings: ['oversized']
deferred:
  - summary: >-
      rememberReduceMotion caches the system animator scale for the composition's lifetime, so a system change while the app is open is not seen until the screen is recomposed from scratch.
    evidence: |-
      ReduceMotion.kt reads Settings.Global.ANIMATOR_DURATION_SCALE inside remember(resolver); this predates Story 6.4 (the in-app override itself is read live from LocalReduceMotionOverride).
    location: >-
      app/src/main/kotlin/dev/rubikxplorer/app/ui/lesson/ReduceMotion.kt
    severity: low
---

<intent-contract>

## Intent

**Problem:** Theme follows the system with no override, color-blind glyphs and haptics are in-memory switches on the free cube only, there is no animation-speed or reduce-motion override, and every new Lesson starts in whatever mode the previous session held. Nothing survives a restart (FR-19, NFR-7, AD-10, AD-17, AR-21, UX-DR21, UX-DR27, UX-DR30).

**Approach:** A pure `Preferences` model and an `UpdatePreference` use case in `:core:application` are the only writer, persisting through a `PreferencesRepository` port to a one-row Room `preferences` table (schema v4). An `@Singleton PreferenceStore` in `:app` exposes the current value as a `StateFlow` and feeds every surface through CompositionLocals. A new *You* tab placeholder opens a Settings screen, and the reducer emits an `UpdatePreference` effect for the last Teaching Mode.

## Boundaries & Constraints

**Always:**
- **Model** (`:core:application/preference/`, pure Kotlin):
  - `data class Preferences(theme = ThemeChoice.SYSTEM, colorBlindGlyphs = false, animationSpeed = AnimationSpeed.NORMAL, haptics = true, reduceMotion = false, lastTeachingMode = TeachingMode.DEFAULT)`; `Preferences.DEFAULT` is that value.
  - `enum ThemeChoice { SYSTEM, LIGHT, DARK }`; `enum AnimationSpeed(factor) { SLOW(0.5), NORMAL(1.0), FAST(2.0) }` with `fun scale(baseMillis): Long = (baseMillis / factor)`.
  - `sealed interface Preference` with one case per field (`Theme`, `ColorBlindGlyphs`, `Speed`, `Haptics`, `ReduceMotion`, `LastTeachingMode`); `Preferences.with(preference)` is pure.
- **Single writer.** `UpdatePreference(repository)` is the only code that calls `PreferencesRepository.save` or `reset`. It offers `suspend operator fun invoke(current, preference): PreferenceUpdate` (returns the new value and the save result) and `suspend fun reset(): PreferenceUpdate` (deletes the stored row; the value is `Preferences.DEFAULT`). Results are sealed (AD-12); a failed save still returns the new in-memory value plus `Failed`.
- **Port.** `PreferencesRepository { load(): PreferencesLoad; save(Preferences): PreferenceSave; reset(): PreferenceSave }`, with `PreferencesLoad.Found(prefs)` (an empty table is `Found(Preferences.DEFAULT)`) and `Failed`, and `PreferenceSave.Saved` / `Failed`. Room exceptions become `Failed`.
- **Storage.** Room v4 adds table `preferences`: `id` INTEGER PK (always 1), `theme`, `color_blind_glyphs`, `animation_speed`, `haptics`, `reduce_motion`, `last_teaching_mode`. Save replaces row 1; reset deletes from `preferences` only. An unknown enum name in a column decodes to that field's default. `AutoMigration(3, 4)`, schema `4.json` exported. Preferences are not an append-only log; replacing the row is their single writer's save.
- **Store** (`app/.../preferences/PreferenceStore.kt`, `@Singleton`):
  - `val preferences: StateFlow<Preferences>` starts at `Preferences.DEFAULT` and is loaded once through `SerialWriter.read` (never on the main thread). `suspend fun awaitLoaded()` returns after the load finished (Found or Failed).
  - `update(preference)` and `reset()` set the `StateFlow` immediately, then enqueue `UpdatePreference` on the `SerialWriter`. The persisted order equals the call order.
  - A test constructor with no repository/writer keeps preferences in memory.
- **Every surface applies the value immediately:**
  - Theme: `MainActivity` collects the store and passes `darkTheme` to `RubikXplorerTheme` (`SYSTEM` → `isSystemInDarkTheme()`, `LIGHT` → false, `DARK` → true).
  - Glyphs and speed: `RenderStyle` gains `animationSpeed: AnimationSpeed = NORMAL`. A `LocalRenderStyle` CompositionLocal (default `RenderStyle()`) is provided from the store at the top of the shell. Every sticker surface takes its style from it: Lesson stage, `DemonstrationPlayer`, `FreeCubeScreen`, `SolutionPlayback`, `ManualCubeEditor`/`StickerReviewNet` (their `style` parameters default to `LocalRenderStyle.current`).
  - Speed: `Cube3DRenderer` (through `PresentationQueue`) and `Net2DRenderer` scale their turn duration with `style.animationSpeed.scale(TURN_MILLIS / ITEM_MILLIS)`; fast-forward still divides that. The constants stay as the NORMAL base.
  - Haptics: a `LocalHaptics` (Boolean) provided from the store. The free cube's tick and the Lesson's `MoveKeyBar` (`tickOnCommit`) fire only when it is true. The free cube's on-screen Haptics switch and its debug glyph switch become views of the preference and write through `PreferenceStore.update`; `FreeCubeViewModel` drops its own `hapticsEnabled` and `style` state.
  - Reduce motion: `rememberReduceMotion()` returns `systemAnimatorScaleIsZero || LocalReduceMotionOverride.current`, so every existing caller follows the override.
- **Last Teaching Mode:**
  - `SessionEvent.LessonStarted` gains `mode: TeachingMode? = null`. `start()` sets `teachingMode = event.mode` when non-null and keeps today's behavior when null.
  - `modeChanged` appends `Effect.UpdatePreference(effectId, revision, Preference.LastTeachingMode(mode))` only when the mode actually changes; it consumes one effect id. No other transition emits it.
  - `SessionHost.dispatch` hands that effect to `PreferenceStore.update` (nullable constructor parameter, like `checkpoints`).
  - `LessonScreen` awaits `PreferenceStore.awaitLoaded()` before dispatching `LessonStarted`, passing `mode = lastTeachingMode` when it is in the offered `modes`, else `TeachingMode.DEFAULT`. A Lesson restored from a snapshot or checkpoint keeps its own stored mode.
- **Navigation.** `Tab` gains `YOU` (label "You", new vector drawable `ic_you`), after SOLVE. Its destination is a placeholder screen with a *Settings* row; Settings opens full-screen like a Lesson (Back or a close button returns to the You tab) and survives configuration change (`rememberSaveable`).
- **Settings screen** (`app/.../ui/settings/SettingsScreen.kt`): theme (System/Light/Dark), Color-blind glyphs, Animation speed (Slow/Normal/Fast), Haptics, Reduce motion, and *Reset preferences*. Each control writes through `PreferenceStore.update`. *Reset preferences* opens a confirmation dialog whose text names exactly what returns to defaults (theme, glyphs, animation speed, haptics, reduce motion, last Teaching Mode) and states that Lesson progress, attempts and saved Lessons are kept; Confirm calls `PreferenceStore.reset()`, Cancel changes nothing. All controls ≥ 48dp with content descriptions; test tags `settings:*`.
- **Copy** is Android string resources (`R.string`), like the existing app chrome and `ResetConfirmation` buttons; no literals (LearnerTextPolicy).

**Never:**
- No DataStore or other new dependency. No progress, attempt, checkpoint, snapshot or calibration reset (Epics 7 and 10). No first-launch glyph offer, Objective-met haptic, progress UI on the You tab, or about/licences screen.
- Never write preferences from a ViewModel, composable or repository caller other than `UpdatePreference` (the store calls only the use case). No Room/Android imports in `:core:*`.
- Never change the snapshot, checkpoint or attempt tables or payload formats. Never edit planning artifacts, `_bmad/` or `.claude/skills/`.

## I/O & Edge-Case Matrix

| Scenario | Input / State | Expected Output / Behavior | Error Handling |
|----------|--------------|---------------------------|----------------|
| First launch | Empty `preferences` table | `Preferences.DEFAULT`; theme follows system | — |
| Change + restart | Set DARK, glyphs on, FAST, haptics off, reduce motion on; new store over the same DB | All five values loaded | — |
| Immediate apply | Toggle glyphs in Settings | `StateFlow` updated before the write; `LocalRenderStyle` consumers see it | — |
| Mode remembered | `TeachingModeChanged(LET_ME_TRY)` in a Lesson | One `Effect.UpdatePreference(LastTeachingMode(LET_ME_TRY))`; stored | — |
| Same mode | `TeachingModeChanged(current mode)` | No effect | — |
| New Lesson | last mode LET_ME_TRY, open another Lesson | `LessonStarted(mode = LET_ME_TRY)`; session starts in LET_ME_TRY | — |
| Mode not offered | last mode TEST_ME, `TEST_ME` flag off | Lesson starts in `TeachingMode.DEFAULT` | — |
| Reset confirmed | Non-default prefs plus snapshot, checkpoint and attempt rows | Preferences back to DEFAULT; the other rows unchanged | — |
| Reset cancelled | Cancel in the dialog | Nothing changes | — |
| Unknown enum | Row with `theme = "NEON"` | That field DEFAULT, others kept | tolerant decode |
| DB failure | load or save throws | Load: `Failed`, DEFAULT in memory; save: `Failed`, in-memory value kept | caught, no crash |

</intent-contract>

## Code Map

- `core/application/.../session/Reduce.kt` -- `modeChanged` (l.~489) gets the effect; `start` (l.~113) honors `event.mode`. Effect ids: follow how `persistCheckpoint`/attempt effects allocate ids.
- `core/application/.../session/{SessionEvent.kt (LessonStarted l.~46), Effect.kt (PersistCheckpoint l.~34 is the shape), TRANSITIONS.md}` -- add the event field, the effect, and a paragraph on it.
- `core/application/.../port/` -- add `PreferencesRepository.kt` next to `AttemptRepository.kt` (pattern for sealed results).
- `core/domain/.../tutor/TeachingMode.kt` -- `DEFAULT = GUIDE_ME` (read-only).
- `app/.../session/SessionHost.kt` `dispatch` (l.~140-217) -- effect loop; add the `UpdatePreference` branch beside the `PersistCheckpoint` block. Constructors: internal `(clock, confined, repository?, writer?, checkpoints?, reduced)`, `@Inject (clock, repository, writer, checkpoints)`; add a nullable `preferences` param keeping existing test call sites compiling.
- `app/.../data/{RubikDatabase.kt (v3 → v4), AttemptDao.kt/RoomAttemptRepository.kt (patterns)}`, `app/.../di/PersistenceModule.kt` (bindings), `app/schemas/dev.rubikxplorer.app.data.RubikDatabase/` (export 4.json via build).
- `app/.../MainActivity.kt` -- inject `PreferenceStore`; collect it for `RubikXplorerTheme(darkTheme=…)`; pass the store to `AppShell`.
- `app/.../ui/theme/Theme.kt:35` -- `RubikXplorerTheme(darkTheme)` (unchanged signature).
- `app/.../ui/shell/AppShell.kt` -- `Tab` enum, destinations, full-screen Lesson pattern (`openLesson`), `availableModes(flags)`; provide the CompositionLocals here (store param; default an in-memory `PreferenceStore()` so existing tests compile).
- `app/.../render/RenderStyle.kt`, `render/gl/PresentationQueue.kt` (`TURN_MILLIS` l.~60 read in `onFrame` l.~46), `render/Cube3DRenderer.kt` (`Content(style…)` l.~161, queue built internally), `render/Net2DRenderer.kt` (`ITEM_MILLIS` l.~251 used l.~132).
- Sticker surfaces: `ui/lesson/LessonScreen.kt:608` (hard-coded `RenderStyle()`), `ui/lesson/DemonstrationPlayer.kt:110`, `ui/solve/SolutionPlayback.kt:76`, `ui/editor/ManualCubeEditor.kt:98`, `ui/freecube/FreeCubeScreen.kt:178` (+ debug glyph switch l.~224, haptics switch l.~121, tick l.~78-81), `ui/freecube/FreeCubeViewModel.kt:44-51,83`.
- `ui/freecube/MoveKeyBar.kt:72` -- `tickOnCommit`; `LessonScreen.kt:619-630` calls it with the default.
- `ui/lesson/ReduceMotion.kt:14` -- add the override local.
- `ui/lesson/LessonScreen.kt:127-133` -- the `LessonStarted` dispatch; switcher `onModeChange` l.~262.
- `app/src/main/res/values/strings.xml` -- `nav_*` (l.~15), `free_cube_haptics` (l.~131), `dialog_cancel`.
- Tests to follow/update: `app/src/test/.../ui/shell/AppShellTest.kt`, `ui/freecube/FreeCubeScreenTest.kt` (fake `LocalHapticFeedback` l.~68-80), `ui/lesson/LessonScreenTest.kt` (SessionHost with `writer =`/`checkpoints =` l.~201), `render/gl/PresentationQueueTest.kt`, `render/Net2DRendererTest.kt`, `data/SessionDatabaseMigrationTest.kt` (`MigrationTestHelper`, 2→3 case l.~47-63), `data/RoomCheckpointRepositoryTest.kt:24` (in-memory Room builder), `app/src/sharedTest/.../session/TestLessons.kt`.

## Tasks & Acceptance

**Execution:**
- `core/application/.../preference/{Preferences,UpdatePreference}.kt`, `.../port/PreferencesRepository.kt` -- model, enums, `Preference`, use case, port.
- `core/application/.../session/{SessionEvent,Effect,Reduce}.kt`, `TRANSITIONS.md` -- `LessonStarted.mode`, `Effect.UpdatePreference`, emission and start mode.
- `core/application/src/test/.../preference/{PreferencesTest,UpdatePreferenceTest}.kt` (new) -- `with` for every case, `scale`, use case with a fake repository (save, reset, failure keeps value), and that the fake sees writes only through the use case.
- `core/application/src/test/.../session/TeachingModePreferenceTest.kt` (new) -- matrix rows "Mode remembered", "Same mode", `LessonStarted(mode)` and `mode = null`. Update existing effect-id expectations only where the new effect appears.
- `app/.../data/{PreferencesEntity,PreferencesDao,RoomPreferencesRepository}.kt`, `RubikDatabase.kt`, `app/schemas/.../4.json`, `di/PersistenceModule.kt` -- storage and bindings (`UpdatePreference`, repository).
- `app/.../preferences/{PreferenceStore,LocalPreferences}.kt` -- store and the `LocalRenderStyle`, `LocalHaptics`, `LocalReduceMotionOverride` locals plus one provider composable.
- `app/.../session/SessionHost.kt` -- effect branch.
- `app/.../render/{RenderStyle,Cube3DRenderer,Net2DRenderer}.kt`, `render/gl/PresentationQueue.kt` -- speed.
- `app/.../ui/{lesson/LessonScreen,lesson/DemonstrationPlayer,lesson/ReduceMotion,solve/SolutionPlayback,editor/ManualCubeEditor,freecube/FreeCubeScreen,freecube/FreeCubeViewModel}.kt` -- consume the locals; Lesson start mode.
- `app/.../ui/shell/{AppShell,YouScreen}.kt`, `ui/settings/SettingsScreen.kt`, `MainActivity.kt`, `res/drawable/ic_you.xml`, `res/values/strings.xml` -- tab, placeholder, Settings, theme wiring, copy.
- `app/src/test/.../data/{RoomPreferencesRepositoryTest,PreferenceStoreTest}.kt` (new) -- first launch, change + restart (new repository and store over the same DB), unknown enum, DB failure, reset leaves snapshot/checkpoint/attempt rows unchanged, immediate `StateFlow` update.
- `app/src/test/.../data/SessionDatabaseMigrationTest.kt` -- 3→4 keeps existing rows and creates `preferences`.
- `app/src/test/.../ui/settings/SettingsScreenTest.kt` (new, Robolectric Compose) -- You tab → Settings; each control updates the store; theme DARK switches `RubikTheme.tokens` to dark; reset confirm restores defaults; cancel keeps values; Back returns to You.
- `app/src/test/.../ui/{shell/AppShellTest,freecube/FreeCubeScreenTest,lesson/LessonScreenTest}.kt` -- You tab present; free cube glyphs and haptics follow the store; a new Lesson opens in the stored mode (and DEFAULT when that mode is not offered); a mode switch reaches the store through `SessionHost`.
- `app/src/test/.../render/{gl/PresentationQueueTest,Net2DRendererTest}.kt` -- FAST halves and SLOW doubles the turn duration.
- `docs/ARCHITECTURE.md` -- one line on the `preferences` table and `UpdatePreference`.

**Acceptance Criteria:**
- Given `UpdatePreference` as the only writer, when the Learner changes theme, color-blind glyphs, animation speed, haptics or reduce-motion override in Settings (reached from the You tab placeholder), then the value persists across restarts and applies immediately on every surface.
- Given the last Teaching Mode used, when a new Lesson opens, then it starts in that mode.
- Given *Reset preferences* in Settings, when confirmed, then only preferences return to defaults.
- Given the v3 database, when the app upgrades, then the 3→4 migration test passes and `./gradlew spotlessCheck build` passes.

## Spec Change Log

## Review Triage Log

### 2026-11-05 — Review pass
- verdicts: 19 findings — high 0, medium 4, low 11, false 4, maybe-false 0
- findings:
  - `[false]` `[reject]` (blind) Reduce motion never reaches free-cube turns — EXPERIENCE.md defines reduce motion as Demonstrations stepping, the milestone burst and camera transitions; free-cube turns were never under the system setting either, and the spec routes the override through the existing `rememberReduceMotion` callers.
  - `[low]` `[reject]` (blind) save/reset failures are not shown in Settings — real, but needs a Room write failure; surfacing it adds UI and retry machinery, the same stance as 6.1–6.3 for failed saves.
  - `[low]` `[reject]` (blind) after a failed initial load, the next save writes defaults for the other fields — real only after a Room read failure; a reload/retry path is new machinery for a rare case.
  - `[low]` `[patch]` (blind) opening Settings drops the Learn list scroll position — `AppShell` now declares `openLesson` and `learnScroll` above the Settings early return.
  - `[low]` `[patch]` (blind) theme and speed radio groups lack selectable-group semantics — each group is now a `Column(Modifier.selectableGroup())`.
  - `[low]` `[patch]` (blind) tapping a setting's label does nothing — each row is now `selectable(role = RadioButton)` or `toggleable(role = Switch)` with the control's own click set to null, so the whole row (label merged) acts; test tags moved to the rows.
  - `[low]` `[defer]` (blind) the system animator scale is cached per composition — pre-existing behavior of `rememberReduceMotion`, not introduced here; recorded in `deferred`.
  - `[low]` `[patch]` (blind) the reset-before-load branch is untested — `PreferenceStoreTest.aResetBeforeTheLoadFinishesDropsTheStoredValue` covers reset→update and update→reset with a gated load, asserting the value and the write order.
  - `[low]` `[patch]` (blind) the restart test reuses one open in-memory database — `RoomPreferencesRepositoryTest.preferencesSurviveClosingAndReopeningTheDatabaseFile` writes through a store, closes the file database, reopens it with a new database, repository and store, and reads all six values back.
  - `[medium]` `[patch]` (blind) Reduce motion has no behavioral coverage — grouped with the verification-gap reduce-motion row; patched there.
  - `[low]` `[patch]` (edge) Learn scroll lost through Settings — same root cause as the blind scroll row; same fix.
  - `[false]` `[reject]` (edge) free-cube turns keep animating with Reduce motion on — same refutation as the first blind row.
  - `[medium]` `[patch]` (verification-gap) the theme test used its own mapping, not production's — the mapping moved to `isDarkTheme(choice, systemDark)` and a `PreferredTheme(store)` composable that `MainActivity` now uses; `SettingsScreenTest` renders through `PreferredTheme` and a new test covers SYSTEM (both system states), LIGHT and DARK.
  - `[medium]` `[patch]` (verification-gap) the Reduce motion preference is never verified through playback — `DemonstrationPlayerTest` now provides a store with the override on, system animator scale 1 and the default player-state factory, and asserts the net snaps within one frame.
  - `[low]` `[reject]` (intent) no single test goes Settings → database — each link is tested: Settings → store (`SettingsScreenTest`), store → `UpdatePreference` → Room → new store after a file reopen (`RoomPreferencesRepositoryTest`); the composition between them is one injected singleton.
  - `[medium]` `[patch]` (intent) the real `MainActivity` theme mapping is untested — same root cause as the verification-gap theme row; same fix.
  - `[low]` `[reject]` (intent) no test shows a sticker surface drawing glyphs from the preference — renderer glyph drawing is already tested with an explicit `RenderStyle`; the wiring is one CompositionLocal read at each surface, and the 3D glyphs live in a GL texture that Robolectric cannot read.
  - `[false]` `[reject]` (intent) Objective-met haptic and gesture ticks are not switched — no Objective-met haptic exists in the app to switch, and gesture commits never ticked before this story; the haptics preference governs every haptic that exists.
  - `[false]` `[reject]` (intent) the store changes its in-memory value directly, so `UpdatePreference` is not the only writer — the persisted preference has one writer, `UpdatePreference`, called only from `PreferenceStore`; the in-memory `StateFlow` is a read model of the same change (spec Always: Store), and the debug toggle and the reducer effect both go through `PreferenceStore.update`.

## Design Notes

- **Room, not DataStore.** The spine allows either; Room is already wired (serial writer, migration harness, in-memory tests) and adds no dependency. Preferences are a single current value, not a log, so the one row is replaced by its only writer and deleted by its scoped reset.
- **Why an effect for the mode.** The adversarial review (L2) found two writers for the last mode; the session emits `UpdatePreference(LastTeachingMode)` and the host forwards it, so the Teaching Mode switcher never writes preferences directly.
- **Animation speed** reuses the 0.5×/1×/2× scale of the playback transport (DESIGN.md) for turn animations only; Demonstration step timing keeps its own `PlaybackSpeed`.

## Verification

**Commands:**
- `./gradlew spotlessCheck build` -- expected: pass (JVM tests, Kover ≥ 80% on `:core:*`, gates, APKs).
- `./gradlew :core:application:test :app:testDebugUnitTest --tests '*Preference*' --tests '*Settings*' --tests '*Migration*' --tests '*AppShell*' --tests '*FreeCubeScreen*' --tests '*LessonScreen*' --tests '*PresentationQueue*' --tests '*Net2DRenderer*' --tests '*TeachingMode*'` -- expected: pass.

## Auto Run Result

**Summary:**
- Pure `Preferences` model (theme, color-blind glyphs, animation speed 0.5×/1×/2×, haptics, reduce-motion override, last Teaching Mode), `Preference` changes and the `UpdatePreference` use case in `:core:application`, behind a `PreferencesRepository` port.
- Room v4 adds a one-row `preferences` table (AutoMigration 3→4); save replaces row 1, reset deletes it only; unknown enum names decode to defaults.
- `@Singleton PreferenceStore` applies changes to a `StateFlow` at once and persists them in order through `UpdatePreference` on the `SerialWriter`; changes made before the first load are re-applied over the loaded value.
- Surfaces: `PreferredTheme` drives the app theme; `LocalRenderStyle` carries glyphs and speed to every sticker surface (3D and 2D renderers scale their turn time); `LocalHaptics` gates the free-cube and Lesson ticks; `rememberReduceMotion` ORs the override with the system setting.
- New You tab placeholder opens a full-screen Settings screen with the five controls and a confirmed *Reset preferences*.
- The reducer emits `Effect.UpdatePreference(LastTeachingMode)` on an actual mode change; `SessionHost` forwards it to the store; a new Lesson starts in the remembered mode when it is offered, else `TeachingMode.DEFAULT`.

**Files:**
- `core/application/.../preference/{Preferences,UpdatePreference}.kt`, `port/PreferencesRepository.kt`: model, use case, port.
- `core/application/.../session/{Effect,Reduce,SessionEvent}.kt`, `TRANSITIONS.md`: the effect, `LessonStarted.mode`.
- `app/.../data/{PreferencesEntity,PreferencesDao,RoomPreferencesRepository,RubikDatabase}.kt`, `app/schemas/.../4.json`, `di/PersistenceModule.kt`: storage and bindings.
- `app/.../preferences/{PreferenceStore,LocalPreferences}.kt`: store, CompositionLocals, `PreferredTheme`.
- `app/.../ui/theme/Theme.kt`: `isDarkTheme`.
- `app/.../MainActivity.kt`, `session/SessionHost.kt`: wiring.
- `app/.../render/{RenderStyle,Cube3DRenderer,Net2DRenderer}.kt`, `render/gl/PresentationQueue.kt`: speed.
- `app/.../ui/{lesson/LessonScreen,lesson/DemonstrationPlayer,lesson/ReduceMotion,solve/SolutionPlayback,editor/ManualCubeEditor,editor/StickerReviewNet,freecube/FreeCubeScreen,freecube/FreeCubeViewModel,freecube/MoveKeyBar}.kt`: consumers.
- `app/.../ui/shell/{AppShell,YouScreen}.kt`, `ui/settings/SettingsScreen.kt`, `res/drawable/ic_you.xml`, `res/values/strings.xml`: You tab, Settings, copy.
- Tests: new `PreferencesTest`, `UpdatePreferenceTest`, `TeachingModePreferenceTest`, `PreferenceStoreTest`, `RoomPreferencesRepositoryTest`, `SettingsScreenTest`; updated `CheckpointTest`, `SessionDatabaseMigrationTest` (3→4), `Net2DRendererTest`, `PresentationQueueTest`, `FreeCubeScreenTest`, `AppShellTest`, `DemonstrationPlayerTest`.
- `docs/ARCHITECTURE.md`: one line on the table.

**Review:** 19 findings — high 0, medium 4, low 11, false 4.
- Patched entries: 2 medium (production theme mapping untested; Reduce-motion preference untested through playback) and 5 low (Learn scroll lost through Settings; radio group semantics; label rows not tappable; reset-before-load untested; restart test on one open in-memory DB).
- Deferred: 1 low (pre-existing cached system animator scale).
- Rejected: 4 low (failed-save feedback, failed-load overwrite, no Settings→DB single test, no glyph-surface test) and 4 false (free-cube reduce motion ×2, Objective-met haptic, single writer); each reason is in the triage log.
- Before review, the orchestrating agent also fixed two issues it found in the Codex implementation: changes made before the first load were dropped when the load finished, and the Settings screen had no surface or content color (unreadable in dark theme).

**Follow-up review recommended:** true. Two medium entries were patched. The unverified risk: the patch refactored the Settings rows to `selectable`/`toggleable` rows and moved the theme mapping into `PreferredTheme`; no reviewer has read that code, only the tests that pass on it.

**Verification:**
- Codex: `./gradlew spotlessCheck build` and the targeted `--tests` command passed.
- Locally after the pre-review fixes: `spotlessApply`, then `spotlessCheck build` passed; the targeted command passed with `--rerun`.
- After the review patches: `spotlessCheck build` passed (one earlier attempt failed in Spotless with a Gradle fingerprinting error on `lineEndingsPolicy`, then passed on retry). The targeted command failed once on `LessonScreenTest > the guide me net follows the session through a Move, undo, redo and a confirmed reset` (a 3D render-timing assertion; the test does not use preferences), then passed 62/62 alone and on a full `--rerun` of the targeted command (PreferenceStoreTest 3, RoomPreferencesRepositoryTest 2, SettingsScreenTest 2, DemonstrationPlayerTest 21, LessonScreenTest 62, AppShellTest 16, all green).
- Matrix audit: first launch, change + restart, unknown enum, DB failure, reset leaves other rows (`RoomPreferencesRepositoryTest`); immediate apply (`PreferenceStoreTest`, `SettingsScreenTest`); mode remembered and same mode (`TeachingModePreferenceTest`, `AppShellTest`); new Lesson and mode not offered (`AppShellTest`); reset confirmed and cancelled (`SettingsScreenTest`).

**Residual risks:**
- `LessonScreenTest`'s guide-me net sync test failed once and passed on rerun; it looks timing-flaky and unrelated, but it was not investigated.
- Local Spotless depends on line endings under the global `core.autocrlf=true`; the Codex run left LF files that `spotlessApply` converted back to CRLF before the index refreshed clean.
- A failed preference save is silent; the in-memory value stays until restart.
- The free cube keeps animating turns with Reduce motion on, as before this story.
