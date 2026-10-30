---
title: 'Story 1.6: Turn a free cube on a 2D net with buttons'
type: 'feature'
created: '2026-10-29'
status: 'done'
baseline_revision: 'f4de98b09bc4081feb821fd0783aa479fcf84a79'
review_loop_iteration: 0
followup_review_recommended: false
context:
  - '{project-root}/_bmad-output/implementation-artifacts/epic-1-context.md'
  - '{project-root}/AGENTS.md'
  - '{project-root}/_bmad-output/planning-artifacts/ux-designs/ux-RubikXplorer-2026-10-29/DESIGN.md'
warnings: ['oversized']
deferred: []
---

<intent-contract>

## Intent

**Problem:** The app still shows a placeholder title. The cube engine (1.2–1.5) has no screen, so a Learner cannot turn a cube, undo, redo or reset, and nothing proves the rendered cube matches the engine (AD-4, NFR-4).

**Approach:** Add the first screen, the free cube, inside a Material3 theme built from DESIGN.md tokens and bundled IBM Plex fonts. The screen shows a `Net2DRenderer` behind a `CubeRenderer` port in a cube stage with an Undo · Redo · Reset toolbar, plus a Move key bar covering all 54 Moves. History is a pure `MoveHistory` in `:core:domain`, and sticker looks come from a pure `StickerAppearance` table. Compose UI tests run on the JVM through Robolectric, so `./gradlew build` checks the acceptance criteria.

## Boundaries & Constraints

**Always:**
- `:core:domain` stays pure Kotlin. `MoveHistory` goes in `dev.rubikxplorer.domain.cube` (the cube Kover gate needs ≥ 90% coverage), and `StickerAppearance` goes in `dev.rubikxplorer.domain.appearance`.
- `MoveHistory` is immutable and starts from `CubeState.SOLVED`. It has `state`, `canUndo`, `canRedo`, `undoable: Move?`, `redoable: Move?`, `commit(move)`, `undo()`, `redo()` and `reset()`.
  - `commit` pushes the Move and clears redo.
  - `undo` and `redo` are no-ops when there is nothing to undo or redo.
  - `reset()` returns solved with both stacks empty.
- `StickerAppearance.of(color): StickerAppearance(fill, glyph, glyphInk, pattern)`:
  - `fill` and `glyphInk` are ARGB `Long`s.
  - `glyph` is one of W Y R O G B.
  - `pattern` is `null` (patterns are deferred).
  - Fills are the DESIGN.md sticker hexes.
  - `glyphInk` is `cubie-body #121417` for WHITE, YELLOW, ORANGE and GREEN, and `sticker-white #F4F5F0` for RED and BLUE. This is the higher-contrast ink; every pair is ≥ 5:1.
  - Sticker fills never depend on the theme.
- All of the following lives in `:app`:
  - `render/`: `CubeRenderer` (port), `PresentationItem` (`Animate(move, revision, target)` and `Snap(revision, target)`), `RenderStyle(colorBlindGlyphs)`, `MoveIntent(move)` and `Net2DRenderer`.
  - `ui/theme/`: the theme.
  - `ui/freecube/`: `FreeCubeViewModel` (a plain `ViewModel`) and `FreeCubeScreen`.
- Commit before animate (AD-4):
  - Every key produces a `MoveIntent`. `FreeCubeViewModel.onMoveIntent` commits it to `MoveHistory` first, then submits `Animate(move)`.
  - Undo submits `Animate(undone.inverse())`, Redo submits `Animate(move)`, and Reset submits `Snap(SOLVED)`.
  - Input during an animation is committed at once.
  - When more than 3 items are queued, the renderer fast-forwards (each item ≤ 1/4 of its normal duration). It never drops an item.
  - `renderedFacelets()` returns the facelets currently drawn and equals the last target once the queue drains.
  - An item takes ≤ 250 ms. The animation runs on the Compose frame clock (`Animatable`/`withFrameNanos`), never `delay`, so the test clock drives it.
  - The renderer never mutates `MoveHistory`.
- Theme `RubikXplorerTheme(darkTheme = isSystemInDarkTheme())`:
  - Map the light and dark chrome and status tokens from DESIGN.md into the M3 `ColorScheme`:
    - `background` = surface-base
    - `surface` = surface-raised
    - `surfaceContainerHighest` = surface-stage
    - `onSurface` = ink-primary
    - `onSurfaceVariant` = ink-secondary
    - `outline` = hairline
    - `primary` = accent
    - `onPrimary` = on-accent
    - `error` = status-invalid
  - Expose the full token set (including ink-disabled and cubie-body) through a `CompositionLocal`.
  - M3 `Shapes`: extraSmall 2dp, small 4dp, medium 8dp, large 12dp. Add a `Spacing` object (4, 8, 12, 16, 24, 32, 48dp, `touchMin` 48dp).
  - Typography: Plex Sans 400/500/600 for display/title/body/label/meta, and Plex Mono 500 `notation` / 400 `data` (tabular figures). `notation` is one M3 step above surrounding text.
- Fonts are static TTFs from `https://raw.githubusercontent.com/IBM/plex/v6.4.0/`:
  - `IBM-Plex-Sans/fonts/complete/ttf/IBMPlexSans-{Regular,Medium,SemiBold}.ttf` and `IBM-Plex-Mono/fonts/complete/ttf/IBMPlexMono-{Regular,Medium}.ttf`.
  - Save them as `app/src/main/res/font/ibm_plex_{sans,mono}_{regular,medium,semibold}.ttf`.
  - Save `LICENSE.txt` (OFL) as `app/src/main/assets/licenses/ibm-plex-OFL.txt`.
  - No downloadable or Google fonts.
- Screen layout, top to bottom:
  - Cube stage, a `surface-stage` well with `rounded.lg` corners:
    - A toolbar with Undo · Redo · Reset icon buttons. Icons are vector drawables in `res/drawable`. Each button has a content label and is ≥ 48dp. When disabled it is tinted ink-disabled.
    - A `VIRTUAL` frame label at top-left.
    - The 2D net in Kociemba cross layout. Stickers use `rounded.xs` corners and are filled from `StickerAppearance`.
  - Move key bar:
    - Face keys `U U' U2 … B2`.
    - A ⟳ group `x x' x2 y … z2`.
    - A "more" toggle that reveals wide `u … b2` and slice `M … S2` keys.
    - Every key shows its canonical `Notation.format` label in `notation`, has a ≥ 48dp target, uses `rounded.sm` corners and a hairline border, and inverts while pressed.
    - Groups are horizontally scrollable rows.
  - Below the key bar: the debug toggle row.
- A Move key's plain name has the form `<spoken>: <layer> <direction>`, built only from `res/values/strings.xml`, with no English literals in Kotlin.
  - **Spoken:** the letter, with `'` → " prime" and `2` → " two". Wide keys are prefixed "wide ".
  - **Layer:** "up/right/front/down/left/back face". Wide keys use "… two layers". Slices are "middle/equator/standing slice". Rotations are "whole cube".
  - **Direction:** "clockwise", "counter-clockwise" or "half turn". Slices and rotations add ", like L|D|F|R|U|F".
  - Examples: "R prime: right face counter-clockwise", "wide r two: right two layers half turn", "M: middle slice clockwise, like L".
  - The plain name is the key's TalkBack content description. Long-press shows it on screen (an M3 tooltip or a stage caption) without committing a Move. A committed key fires a light haptic tick.
- The color-blind glyph toggle is a labeled `Switch`, shown only when `FreeCubeScreen(showDebugToggles = true)`.
  - `MainActivity` passes `DebugOptions.SHOW_DEBUG_TOGGLES`, defined `true` in `app/src/debug/kotlin/.../DebugOptions.kt` and `false` in `app/src/release/kotlin/.../DebugOptions.kt`. Never read `BuildConfig`.
  - When on, every sticker shows its glyph centered in `glyphInk`, legible at 16dp (sp-independent size ≥ 16dp, weight ≥ 500).
- Test tags and semantics:
  - Stickers: `sticker:<index>` (0..53), with a custom semantics property carrying the drawn `Color`.
  - Keys: `moveKey:<token>`.
  - Toolbar buttons: `toolbar:undo|redo|reset`.
  - Debug toggle: `debug:glyphs`.
- Test setup: Robolectric `@RunWith(RobolectricTestRunner::class)`, SDK 34 via `app/src/test/resources/robolectric.properties` (JDK 17 cannot run SDK 35+ android-all).
  - Tests run under JUnit Platform through `junit-vintage-engine`.
  - `testOptions.unitTests.isIncludeAndroidResources = true`.
  - `ui-test-manifest` is `debugImplementation` only (AGP 9 runs only debug unit tests).

**Never:**
- No `LearningSession`, `SessionEvent` or persistence. The free cube is not a session (AD-20).
- No gestures, View Rotation, reset-view, dimming, SEEN strip, cube semantics description, Settings screen or theme override, WindowSizeClass layouts or 3D. Those belong to later epics.
- No Reset confirmation.
- Never read `BuildConfig` outside the flags adapter. Never add `INTERNET`, a network font provider or any denylisted SDK.
- Never edit planning artifacts, `_bmad/` or `.claude/`. No new Gradle module.
- No `material-icons-*` or `lifecycle-viewmodel-compose`, unless `by viewModels()` fails to resolve without it.

## I/O & Edge-Case Matrix

| Scenario | Input / State | Expected Output / Behavior | Error Handling |
|----------|--------------|---------------------------|----------------|
| Commit | solved, tap `R` | state = SOLVED.apply(R); Undo enabled, Redo disabled | none |
| Undo/redo | after `R U`, Undo then Redo | Undo → SOLVED.apply(R); Redo → SOLVED.apply(R, U) | none |
| New Move clears redo | after `R`, Undo, tap `F` | state = SOLVED.apply(F); Redo disabled | none |
| Reset | any history, tap Reset | SOLVED, no dialog; Undo and Redo disabled | none |
| Nothing to undo | fresh screen | Undo/Redo disabled; `MoveHistory.undo()` returns an equal history | no-op |
| Burst | 10 keys tapped with no idle between | after drain, `renderedFacelets()` == final state facelets; no Move dropped | none |
| Glyphs | debug toggle on | 54 stickers show the glyph of their color in `glyphInk` | none |

</intent-contract>

## Code Map

- `core/domain/src/main/kotlin/dev/rubikxplorer/domain/cube/CubeState.kt`: `SOLVED`, `apply(Move)`, `apply(List)`, `toFacelets()`.
- `.../domain/cube/Move.kt`: `Move.inverse()`, the variants `FaceTurn`/`WideTurn`/`SliceTurn`/`CubeRotation`, `Turn`, `Slice`, `Axis`.
- `.../domain/cube/Notation.kt`: `Notation.format(move)` gives the key labels and tags.
- `.../domain/cube/Facelets.kt`: index = `face.ordinal*9 + row*3 + col` in URFDLB order; the net layout is in its KDoc.
- `.../domain/cube/Color.kt`: `Color` enum.
- `core/domain/src/test/kotlin/dev/rubikxplorer/domain/cube/AllMoves.kt`: `allMoves` (54) and the kotest arbs `arbAnyMove`/`arbAnySequence`. Reuse them in `MoveHistoryTest`.
- `build-logic/.../CubeCoverageGate.kt` (read-only): the 90% line gate on `domain.cube`.
- `app/build.gradle.kts`: add the test deps and `isIncludeAndroidResources` here. `ui-test-junit4` and `ui-test-manifest` come from the Compose BOM `2026.09.00`. Add `robolectric = "4.17"` and `junit-vintage-engine` (version from `junit-bom`) to `gradle/libs.versions.toml`.
- `app/src/main/kotlin/dev/rubikxplorer/app/MainActivity.kt`: replace the placeholder `Text` with the theme and `FreeCubeScreen(viewModel = by viewModels<FreeCubeViewModel>(), showDebugToggles = DebugOptions.SHOW_DEBUG_TOGGLES)`. Keep the `featureFlags` injection.
- `app/src/test/kotlin/dev/rubikxplorer/app/flags/BuildConfigUsageTest.kt`: scans every non-test source set, including `debug` and `release`, so `DebugOptions` must not mention `BuildConfig`.
- `app/src/main/res/values/strings.xml`: holds all new Learner-facing text.
- Only `testDebugUnitTest` runs in `build` (verified with `--dry-run`).
- ktlint 1.8 runs from the root `spotless`. If it rejects PascalCase `@Composable` names, add a root `.editorconfig` with `ktlint_function_naming_ignore_when_annotated_with = Composable`.

## Tasks & Acceptance

**Execution:**
- `core/domain/src/main/kotlin/dev/rubikxplorer/domain/cube/MoveHistory.kt`: add `MoveHistory` per Always (FR-3, AR-8).
- `core/domain/src/test/kotlin/dev/rubikxplorer/domain/cube/MoveHistoryTest.kt`:
  - The I/O rows at the domain level.
  - Kotest properties with pinned seeds: commit a sequence, then undo it all → SOLVED; undo then redo → same state; any commit after an undo → `canRedo == false`.
- `core/domain/src/main/kotlin/dev/rubikxplorer/domain/appearance/StickerAppearance.kt` and `core/domain/src/test/kotlin/dev/rubikxplorer/domain/appearance/StickerAppearanceTest.kt`: the table, with tests for distinct glyphs, exact hexes, glyph contrast ≥ 4.5:1 by the WCAG formula, and `pattern == null` (AD-17, UX-DR21).
- `app/src/main/res/font/*`, `app/src/main/assets/licenses/ibm-plex-OFL.txt`: the fonts and their license (UX-DR2).
- `app/src/main/kotlin/dev/rubikxplorer/app/ui/theme/{Tokens,Theme,Type,Shape,Spacing}.kt`: the theme per Always (UX-DR1–3, UX-DR30).
- `app/src/main/kotlin/dev/rubikxplorer/app/render/{CubeRenderer,PresentationItem,RenderStyle,MoveIntent,Net2DRenderer}.kt`: the port, the presentation items and the 2D net adapter. `Net2DRenderer` also provides the `@Composable` that draws the net from its rendered facelets and `RenderStyle` (AD-4).
- `app/src/main/kotlin/dev/rubikxplorer/app/ui/freecube/{FreeCubeViewModel,FreeCubeScreen,MoveKeyBar,MovePlainName}.kt`, plus `res/drawable/ic_{undo,redo,reset}.xml` and `res/values/strings.xml`: the screen per Always (FR-1, FR-3, UX-DR4, UX-DR5).
- `app/src/{debug,release}/kotlin/dev/rubikxplorer/app/DebugOptions.kt` and `MainActivity.kt`: wire up the screen.
- `gradle/libs.versions.toml`, `app/build.gradle.kts`, `app/src/test/resources/robolectric.properties`: the test toolchain.
- `app/src/test/kotlin/dev/rubikxplorer/app/ui/theme/ThemeTokensTest.kt` (plain JUnit 5), covering AC 1:
  - Light and dark tokens equal the DESIGN.md hexes.
  - Contrast: ink-primary and ink-secondary ≥ 4.5:1 on every surface token per theme; accent/on-accent ≥ 4.5:1; status ≥ 3:1 on every surface; sticker-highlight ≥ 7:1 on cubie-body.
  - Typography families are Plex, with notation and data in Mono, and the shape radii are 2/4/8/12dp.
- `app/src/test/kotlin/dev/rubikxplorer/app/ui/freecube/FreeCubeScreenTest.kt` (Robolectric Compose), covering AC 1–5 at the screen surface:
  - Sticker colors via semantics are identical under `darkTheme = true` and `false`.
  - Tap every one of the 54 keys (scroll to each, open "more"). After each tap and idle, `renderer.renderedFacelets()` == `viewModel` state facelets. Repeat as a burst with no idle in between.
  - The Undo/Redo/Reset rows from the I/O matrix, through the toolbar tags.
  - Glyphs on/off.
  - Each key's content description equals the plain-name table: assert R', r2, M, x and U2 literally, and all 54 are non-blank and distinct. Long-press shows the name and commits nothing. Every key and toolbar button is ≥ 48dp (`assertTouchWidthIsAtLeast`/`Height`).

**Acceptance Criteria:**
- Given the app theme, when the free cube screen renders in light or dark (following the system), then it uses the DESIGN.md tokens, the bundled IBM Plex fonts and the spacing/shape scale, and sticker colors are identical in both themes.
- Given `Net2DRenderer` behind `CubeRenderer`, when any Move is committed from the Move key bar (every face, wide, slice and Cube Rotation key), then `renderedFacelets()` equals the committed state's `Facelets` after the presentation queue drains.
- Given the pure `MoveHistory`, when the Learner taps Undo, Redo or Reset on the stage toolbar, then:
  - Undo restores the exact previous state.
  - Redo restores the undone state.
  - A new Move clears redo.
  - Reset returns to solved without a confirmation.
- Given `StickerAppearance`, when color-blind glyphs are switched on in the debug toggle, then every sticker shows its W Y R O G B glyph in the DESIGN.md glyph ink.
- Given a Move key, when it is long-pressed or reached by TalkBack, then it announces its plain name ("R prime: right face counter-clockwise") and its touch target is ≥ 48dp.
- Given `./gradlew spotlessCheck build`, when it runs, then it passes, including the cube 90% and module 80% Kover rules and all AD-13 and AD-18 gates.

## Spec Change Log

## Review Triage Log

### 2026-10-30 — Review pass
- verdicts: 35 findings — high 0, medium 0, low 29, false 6, maybe-false 0
- findings:
  - `[false]` `[reject]` (blind) release unit tests fail because `ui-test-manifest` is debug-only — refuted: `./gradlew build --dry-run` schedules only `:app:testDebugUnitTest` (AGP 9 tests only the tested build type).
  - `[low]` `[patch]` (blind) nothing tests where stickers sit on the net — patched: `every sticker sits in its Kociemba cross cell` checks all 54 cells against the U/L/F/R/B/D origins and a 12×9 grid.
  - `[low]` `[reject]` (blind) Reset's `Snap` waits behind queued animations — real but short: more than 3 queued items fast-forward at 50 ms each, and the spec's "never drops an item" rule forbids skipping them.
  - `[low]` `[patch]` (blind) `revision` KDoc claims stale-item detection that nothing does — patched: the KDoc now says only that it increases per item and identifies the target state. The unused `move` field is required by the spec for later renderers.
  - `[low]` `[patch]` (blind) no tests for animation timing or fast-forward — grouped with verification-gap rows 1–2; patched there.
  - `[false]` `[reject]` (blind) rotation mid-animation, or two `drain()` collectors, desync the net — refuted: `drain()`'s `finally` sets `rendered = item.target` on cancellation, the restarted `LaunchedEffect` drains the rest, and `Content` has a single call site.
  - `[low]` `[reject]` (blind) the net has no TalkBack summary — the intent is "exactly this story's acceptance criteria", and none asks for a cube description (AD-17's arrives with the 3D renderer). Per-sticker glyph letters are debug-only.
  - `[low]` `[reject]` (blind) `indication = null` leaves keys without a focus highlight — touch phones are the target, and the press inversion stays; a focus state adds a branch per key.
  - `[low]` `[reject]` (blind) no scroll hint on the key rows — a UX refinement that adds layout code; the rows are scrollable as DESIGN.md specifies.
  - `[low]` `[reject]` (blind) ⟳ may fall back to a system font — the glyph still renders through font fallback, and it carries a content description.
  - `[low]` `[patch]` (blind) the caption is lost on rotation and stays stale after Undo/Redo/Reset — patched: `rememberSaveable`, and the toolbar actions clear it. The re-announce and live-region nuances are rejected as negligible.
  - `[false]` `[reject]` (blind) plain names hard-code English through `like.name` — refuted: `like.name` is the face letter (L/D/F/R/U), notation and not prose; every word comes from `strings.xml`.
  - `[low]` `[reject]` (blind) `MoveHistory` copies lists, O(n) per step — a free-cube session is hundreds of Moves (microseconds); a persistent stack adds structure for no felt cost.
  - `[low]` `[reject]` (blind) `AtomicInteger` in a renderer documented as main-thread — harmless redundancy, with no bad outcome.
  - `[low]` `[patch]` (blind) glyph size: ignores font scale, and can overflow small cells — the font-scale independence is spec-mandated, but the overflow is real. Grouped with edge row 5 and patched there.
  - `[false]` `[reject]` (blind) the review diff omits the spec, license and fonts — refuted as a code defect: those files exist in the tree and are committed; binaries were left out of the review diff on purpose.
  - `[low]` `[patch]` (verification-gap) the burst test cannot see a dropped or merged item — patched: `a burst passes through every intermediate state in order` steps frame by frame and allows only target k-1 or k while waiting for k.
  - `[low]` `[patch]` (verification-gap) timing, fast-forward and the instant Reset are never checked — patched: `one Move settles within 250 ms` (not yet changed at 50 ms), burst budgets at 250/4 ms per fast item (inverting `fast` fails it), and `reset snaps to solved within one frame`.
  - `[low]` `[reject]` (verification-gap) glyph font-scale independence is untested — debug-only toggle in this story; the test needs a font-scale config setup. Revisit with the Learner-facing Settings toggle (UX-DR21).
  - `[low]` `[reject]` (verification-gap) `MainActivity` and release `DebugOptions` wiring are untested — two readable lines; testing them needs a Hilt `ActivityScenario` setup and release unit tests that AGP 9 does not run.
  - `[low]` `[reject]` (intent) the theme test injects `darkTheme` instead of the system setting — `RubikXplorerTheme`'s default is `isSystemInDarkTheme()`, readable in one line; driving the system UI mode adds Robolectric qualifier setup for no new coverage.
  - `[low]` `[patch]` (intent) `renderedFacelets()` equality holds by construction — the drawn layout is now verified by the placement test (grouped with blind row 2), and drawn colors by semantics.
  - `[low]` `[reject]` (intent) the port shape differs from AD-4 (Facelets instead of CubeState, `RenderStyle` not on the port, no `MoveCommitted`) — the free cube is not a session (AD-20), so there is no reducer. The port has one adapter in `:app`, and Epic 5 adapts it with the 3D renderer.
  - `[low]` `[reject]` (intent) "no confirmation" is not asserted — no dialog code exists; the test asserts that a single Reset click reaches solved.
  - `[low]` `[reject]` (intent) the debug-only toggle wiring is untested — same root as verification-gap row 4; rejected there.
  - `[low]` `[reject]` (intent) only 5 of 54 plain names are checked literally — the rest are composed from the same tested parts, and all 54 are asserted non-blank and distinct.
  - `[low]` `[reject]` (intent) the cube semantics description (AD-17) is absent — not in this story's acceptance criteria, which the intent limits the work to.
  - `[false]` `[reject]` (edge) `testReleaseUnitTest` fails — refuted: the same as blind row 1.
  - `[low]` `[reject]` (edge) Reset waits for pending animations — same as blind row 3.
  - `[false]` `[reject]` (edge) two `drain()` collectors — refuted: `Net2DRenderer.Content` has one call site (`CubeStage`).
  - `[low]` `[patch]` (edge) `revision` is never read — same root as blind row 4; KDoc patched.
  - `[low]` `[patch]` (edge) the glyph overflows cells under ~29dp — patched: glyph size = min(max(16dp, 0.55·cell), cell·(1 − 2·GAP_FRACTION)).
  - `[low]` `[patch]` (edge) on landscape or short screens the fixed rows squeeze the stage almost to nothing — patched: the key bar and debug row sit in a `weight(1f, fill = false)` vertically scrolling column, so the stage keeps at least half the free height.
  - `[low]` `[patch]` (edge) the caption stays stale after toolbar actions — same root as blind row 11; patched.
  - `[low]` `[reject]` (edge) `MoveHistory` grows without bound — same as blind row 13.

## Design Notes

**Reset clears both stacks.** It starts the free cube fresh: no Lesson progress is at stake, so it needs no confirmation and is not undoable. FR-3 asks nothing more of the free cube.

**Contrast scope.** The light `status-likely` on `surface-stage` measures about 4.45:1, under the 4.5:1 chip-text bar. No status chip exists in this story, so the test asserts status only as icons (≥ 3:1). Chip text is checked when chips arrive.

**Shape of the renderer.** It is a projection: it keeps the rendered `Facelets` in Compose state and a FIFO queue of `PresentationItem`, and a `LaunchedEffect` drains the queue:

```kotlin
interface CubeRenderer {
    fun submit(items: List<PresentationItem>)
    fun renderedFacelets(): Facelets
}
```

## Verification

**Commands:** (`export JAVA_HOME=/usr/lib/jvm/java-25-openjdk-amd64 ANDROID_HOME=$HOME/Android/Sdk`)
- `./gradlew :core:domain:check`: expected all tests pass; `koverVerify` and `koverVerifyCube` pass.
- `./gradlew :app:testDebugUnitTest`: expected `FreeCubeScreenTest` and `ThemeTokensTest` pass, plus the existing flags tests.
- `./gradlew spotlessCheck build`: expected BUILD SUCCESSFUL.

## Auto Run Result

Status: done

**Summary:** Story 1.6 replaces the placeholder screen with the free cube.

- **Theme:** a Material3 theme from the DESIGN.md tokens, with bundled IBM Plex Sans and Mono and the spacing/shape scale.
- **Rendering:** a `Net2DRenderer` adapter behind a `CubeRenderer` port. It drains a FIFO presentation queue of `Animate`/`Snap` items on the Compose frame clock, at 200 ms per item, fast-forwarding to 1/4 when more than 3 are queued, and never drops an item.
- **History:** a pure `MoveHistory` in `:core:domain`.
- **Stage:** an Undo · Redo · Reset toolbar and a `VIRTUAL` frame label.
- **Move key bar:** keys for all 54 Moves, each with a plain-name content description, a long-press caption and a haptic tick.
- **Color-blind glyphs:** drawn from a pure `StickerAppearance` table, behind a debug-only toggle.
- **Tests:** Compose UI tests run on the JVM through Robolectric (SDK 34) under JUnit Platform.

**Files changed:**
- `core/domain/.../cube/MoveHistory.kt` (new): immutable undo/redo history; reset clears both stacks.
- `core/domain/.../appearance/StickerAppearance.kt` (new): fill, glyph, glyph ink and pattern (null) per Color.
- `core/domain/src/test/.../MoveHistoryTest.kt`, `StickerAppearanceTest.kt` (new): examples plus seeded kotest properties; hexes, glyphs and contrast.
- `app/.../render/{CubeRenderer,PresentationItem,RenderStyle,MoveIntent,Net2DRenderer}.kt` (new): the port, the presentation items and the 2D net adapter.
- `app/.../ui/theme/{Tokens,Theme,Type,Shape,Spacing}.kt` (new): the theme.
- `app/.../ui/freecube/{FreeCubeViewModel,FreeCubeScreen,MoveKeyBar,MovePlainName}.kt` (new): the screen; commit-before-animate in the ViewModel.
- `app/src/{debug,release}/.../DebugOptions.kt` (new); `MainActivity.kt`: wiring.
- `app/src/main/res/{font,drawable}/*`, `values/strings.xml`, `assets/licenses/ibm-plex-OFL.txt`: fonts (IBM/plex v6.4.0), icons, strings and the OFL license.
- `app/src/test/.../FreeCubeScreenTest.kt` (16 tests), `ThemeTokensTest.kt` (6 tests), `robolectric.properties`: AC 1–5 at the screen surface.
- `gradle/libs.versions.toml`, `app/build.gradle.kts`, `.editorconfig`: Robolectric 4.17, Compose ui-test, the vintage engine, and the ktlint Composable naming exemption.

**Review findings:** 35 findings (high 0, medium 0, low 29, false 6, maybe-false 0).
- **Patched:** 6 entries, all low:
  - the net placement test (also covers the by-construction equality);
  - the `revision` KDoc wording;
  - bounded-clock tests for per-item order, timing, fast-forward and the instant Reset;
  - the caption is saveable and cleared by toolbar actions;
  - the glyph is clamped to the sticker size;
  - a scrollable key area, so the stage keeps its height on short screens.
- **Deferred:** none.
- **Rejected:**
  - Reset waiting behind the queue (the spec says never drop an item);
  - no net TalkBack summary or AD-17 cube description (outside the acceptance criteria);
  - the focus highlight;
  - a scroll hint;
  - ⟳ font fallback;
  - `MoveHistory` list copying;
  - the `AtomicInteger`;
  - untested font scale and release/MainActivity wiring;
  - the injected `darkTheme` in tests;
  - the AD-4 port shape (the free cube is not a session);
  - the unasserted "no dialog";
  - the literal checks on all 54 names;
  - and 6 false findings.

  Every reason is in the Review Triage Log.

**Follow-up review recommended:** false. The first pass patched no high or medium entries (patched counts: high 0, medium 0, low 6).

**Verification:**
- `./gradlew spotlessCheck build` exited 0 after the patches.
- `FreeCubeScreenTest` 16/16, `ThemeTokensTest` 6/6, `MoveHistoryTest` 9/9, `StickerAppearanceTest` 5/5, and the existing flags tests all pass. Kover rules pass, including the cube 90% gate.
- Matrix audit: every I/O-matrix row (commit, undo/redo, new Move clears redo, reset, nothing to undo, burst, glyphs) is covered by a passing `FreeCubeScreenTest` test, with domain-level `MoveHistoryTest` counterparts.

**Residual risks:**
- Robolectric runs at SDK 34 because JDK 17 cannot run SDK 35+ android-all. Behavior on SDK 36 is unverified on the JVM.
- The first item of a burst always runs at normal speed, because fast-forward is decided when an item starts.
- The landscape and expanded layouts are minimal: there is no WindowSizeClass split yet.
- The ⟳ label may render in a fallback font.
- The `CubeRenderer` port carries `Facelets`, not `CubeState`. The 3D renderer (Epic 5) may need to widen it.
