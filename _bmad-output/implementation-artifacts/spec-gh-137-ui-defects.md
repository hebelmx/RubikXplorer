---
title: 'GH-137: UI defects found during Play Store screenshot capture'
type: 'bugfix'
created: '2026-11-13'
status: done
baseline_revision: 'd9b77b3ea0f73a74d9605179ce6ac6c5f6c213a4'
review_loop_iteration: 0
followup_review_recommended: false
context:
  - '{project-root}/AGENTS.md'
  - '{project-root}/docs/accessibility-manual-check.md'
warnings: [multiple-goals, oversized]
deferred:
  - summary: >-
      The Solve Playback net gets only the height left after the controls, so at very large font or a short landscape height it can shrink toward zero.
    evidence: |-
      Pre-existing: the old net box also took the remainder via weight(1f, fill = false). The new BoxWithConstraints keeps that behaviour but now sizes the net inside the remainder without clipping. Settling it needs a run at fontScale 2.0 on a short landscape phone.
    location: >-
      app/src/main/kotlin/dev/cubentia/app/ui/solve/SolutionPlayback.kt:285
    severity: low
---

<intent-contract>

## Intent

**Problem:** GitHub issue #137 lists seven UI defects seen on real devices: the Teaching Mode switcher and the "It matches"/"Enter it" buttons split words ("Guid / e me", "It / matche / s") at large font sizes and on 600 dp two-pane lessons; Solve Playback and the tablet Enter-colors editor paint the light window background in dark theme (and status-bar icons go white on white); the Solve Playback net is clipped on a 411 dp phone; returning learners see Welcome on every cold start; two texts touch the left screen edge; the transport at 0/N looks wrongly enabled; and the algorithm-detail ✕ sits in the middle of the header.

**Approach:** Fix each defect at its source with the smallest change: measure whether every single word of every label fits its one-row segment and reflow the switcher to a 2×2 grid (and stack the confirm buttons) only when some word would otherwise split — wrapping at spaces is fine; paint the theme surface and drive system-bar appearance from the app theme; size the net from the space it really has; decide Welcome only after the session restore has loaded; add the standard margin; pin the ✕ to the header's leading edge. Each fix gets a Robolectric/Compose test.

## Boundaries & Constraints

**Always:**
- The switcher stays one segmented control: one `selectableGroup` container tagged `lesson:modeSwitcher` with the same `contentDescription`, each segment still `selectable(role = RadioButton)` tagged `mode:<MODE>`, active segment `accent`, same `onSelect` contract (UX-DR8).
- A label may wrap between words; no text line may end inside a word.
- Colors come from `RubikTheme.tokens` / `MaterialTheme`; light and dark themes both work, including the Settings theme override (UX-DR30).
- All new Learner-facing text is none; existing keys and strings are reused.
- Every commit subject contains `#137`; commits end with `Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>`.
- `./gradlew spotlessCheck build` passes (JDK 25 env from AGENTS.md).

**Never:**
- Do not change the virtual lesson cube size, the tablet learning-path layout, or the *Test me* release level / `FeatureFlagLevels.kt` (out of scope).
- Do not add a layout other than the single-row and the 2×2 grid for the switcher (PO decision); do not shorten labels, shrink the type scale, or use ellipsis.
- No new modules, ports, persisted entities or `SessionEvent`s; no edits under `_bmad/`, `.claude/skills/`, or `_bmad-output/planning-artifacts/`.
- Do not change the `AndroidManifest.xml` window theme as the dark-background fix; paint the surfaces.

## I/O & Edge-Case Matrix

| Scenario | Input / State | Expected Output / Behavior | Error Handling |
|----------|--------------|---------------------------|----------------|
| Switcher fits | 411 dp phone, font 100% | One row of four segments; labels may wrap at spaces ("Let me / try") | — |
| Word would split | any width/fontScale where some single word is wider than its one-row segment (e.g. phone at 200%, or a narrow pane) | 2×2 grid (rows of two), whole words, same selection/TalkBack | — |
| Words fit, labels wrap | phone at 1.5/2.0 or 600 dp two-pane at 1.0 where every word still fits a segment | One row, labels wrap between words only | — |
| Three modes | `TEST_ME` off, grid needed | Rows of two; the odd last segment fills its row | — |
| Cold start, returning | saved progress events or attempt records | Learn shell, Welcome never composed | Placeholder surface (no Welcome) until restore completes |
| Cold start, fresh | no progress, no attempts | Welcome | — |
| Catalog failed to load | `CatalogState.Failed`, restore never runs | Decide from current (empty) lists: Welcome shown, never stuck on the placeholder | — |
| Playback at 0/N | position 0, size N > 0 | Step back + restart(any) per existing rules: back disabled; play and forward enabled, drawn `inkPrimary`; disabled drawn `inkDisabled` | — |

</intent-contract>

## Code Map

- `app/src/main/kotlin/dev/cubentia/app/ui/lesson/TeachingModeSwitcher.kt` -- `Row` of `Segment`s with `weight(1f)` and `padding(horizontal = Spacing.s3)`, `labelLarge`; reflow goes here (e.g. `BoxWithConstraints` + `rememberTextMeasurer` measuring each label's longest word against the per-segment content width).
- `app/src/main/kotlin/dev/cubentia/app/ui/lesson/ConfirmCubeCard.kt` -- `Row` with two `weight(1f)` buttons (M3 default 24 dp content padding); text keys `btn.physical.confirm-enter` / `btn.physical.confirm-matches`.
- `app/src/main/kotlin/dev/cubentia/app/ui/solve/SolvePlaybackScreen.kt` -- root `Column(fillMaxSize().safeDrawingPadding())` has no background and no content color.
- `app/src/main/kotlin/dev/cubentia/app/ui/shell/AppShell.kt` -- `:161` `welcome` frozen from `showWelcome`; `:189` `LaunchedEffect(content, host) { host.restore(...) }`; `:191-210` Solve Playback branch; `:211-233` Welcome branch; compact `Scaffold(containerColor = surfaceBase)` vs rail `Row(Modifier.fillMaxSize())` at ~`:601-614` with no background (Solve tab `destinationModifier` at ~`:592` has none either, unlike Learn at ~`:586`).
- `app/src/main/kotlin/dev/cubentia/app/MainActivity.kt:58` -- computes `showWelcome` from not-yet-restored `progressEvents`/`attemptRecords`; also where system-bar appearance can be set (`enableEdgeToEdge(SystemBarStyle...)` from activity 1.13.0, keyed on the resolved dark flag).
- `app/src/main/kotlin/dev/cubentia/app/preferences/LocalPreferences.kt` -- `PreferredTheme` resolves dark via `isDarkTheme(prefs.theme, isSystemInDarkTheme())`; reuse it for the system-bar style.
- `app/src/main/kotlin/dev/cubentia/app/session/SessionHost.kt:184-265` -- `restored: CompletableDeferred`, `awaitRestored()`, `restore()` runs `reloadProgress()`/`reloadAttempts()` before completing; completes immediately when no repositories (tests).
- `app/src/main/kotlin/dev/cubentia/app/ui/solve/SolveScreen.kt:54,62` -- `solve:pauseNote` Text without margin; Editing phase holds `ManualCubeEditor`.
- `app/src/main/kotlin/dev/cubentia/app/ui/solve/SolutionPlayback.kt:284-290` -- net `Box(fillMaxWidth().weight(1f, fill = false).aspectRatio(NET_ASPECT))`: width forced to max while height is capped, so the box can be sized inconsistently; `Net2DRenderer.Content` (`render/Net2DRenderer.kt:151-161`) already takes `cell = min(w/12, h/9)` and tags the net `net`.
- `app/src/main/kotlin/dev/cubentia/app/ui/playback/PlaybackTransport.kt` -- `TransportButton` already colors `inkPrimary`/`inkDisabled` by `enabled`; `playback:note` Text (~`:320-326`) has no horizontal padding. Shared by Library detail and Lesson demonstration.
- `app/src/main/kotlin/dev/cubentia/app/ui/library/AlgorithmDetailScreen.kt:101-117` -- `title` `Row` without `fillMaxWidth` inside a `CenterHorizontally` column, so the ✕ centers with the title.
- Tests: `app/src/test/kotlin/dev/cubentia/app/ui/a11y/A11yAssertions.kt` (`fontScale200`, `assertNoTextOverflow` using `GetTextLayoutResult`), `ui/shell/AppShellSkipCheckTest.kt` (host with fake `ProgressRepository`/`AttemptRepository`, `showWelcome = true`), `ui/lesson/LessonScreenTest.kt`, `ui/lesson/PhysicalLessonScreenTest.kt`, `ui/solve/SolutionPlaybackTest.kt`, `ui/library/AlgorithmDetailScreenTest.kt`, `ui/shell/AppShellSolveTest.kt`. Use `@GraphicsMode(NATIVE)` where text measurement or pixels matter.

## Tasks & Acceptance

**Execution:**
- `ui/lesson/TeachingModeSwitcher.kt` -- measure whether every single word of every label fits its segment's content width in one row (never compare the whole label); if not, lay the same segments out two per row (odd last one fills its row) inside the same container -- whole-word labels at large type and 600 dp (UX-DR8 unchanged).
- `ui/lesson/ConfirmCubeCard.kt` -- keep side-by-side buttons when whole words fit; otherwise stack them full width (*It matches* stays the primary, order unchanged); a smaller content padding (`Spacing.s3`) is allowed -- no mid-word "matche / s".
- `ui/solve/SolvePlaybackScreen.kt` -- paint `surfaceBase` behind the whole screen including insets (background before `safeDrawingPadding`) and provide `inkPrimary` as content color (e.g. `Surface(color = surfaceBase, contentColor = inkPrimary)`) -- no window background in dark theme.
- `ui/shell/AppShell.kt` -- give the medium/expanded rail layout's destination area `surfaceBase` and `inkPrimary` content color, as the compact `Scaffold` does; make `welcome` a saveable `Boolean?` that is `false` when `showWelcome` is false, and otherwise is decided once after `host.awaitRestored()` (or immediately when the catalog is `Failed`) as "no progress events and no attempt records"; render a plain `surfaceBase` placeholder while undecided; keep the reset-progress path setting `welcome = true`; update the KDoc of `showWelcome` ("offer Welcome on a cold start with no profile").
- `MainActivity.kt` -- pass `showWelcome = true` (the shell decides); set system-bar appearance from the resolved app theme (light theme → dark icons, dark theme → light icons) and re-apply when the theme preference changes.
- `ui/solve/SolutionPlayback.kt` -- size the net box from the available width and height (largest 12:9 box that fits both) instead of `fillMaxWidth()` + `aspectRatio` -- no clipped column at 411 dp.
- `ui/solve/SolveScreen.kt` -- `padding(horizontal = Spacing.s4)` on both `solve:pauseNote` texts.
- `ui/playback/PlaybackTransport.kt` -- `padding(horizontal = Spacing.s4)` on `playback:note`.
- `ui/solve/SolutionPlayback.kt` (`PhysicalPlaybackControls`, ~:357) -- `padding(horizontal = Spacing.s4)` on its `playback:note` too.
- `ui/library/AlgorithmDetailScreen.kt` -- `title` row `fillMaxWidth()` so the ✕ sits at the leading edge and the title follows it.
- Tests (new or extended, under `app/src/test/kotlin/dev/cubentia/app/ui/`) -- a shared helper asserting no text line ends mid-word (line end at text end, or the char before/after the break is whitespace); switcher + confirm card at fontScale 1.5 and 2.0 on `w360dp`/`w411dp`, and the real Lesson / physical Lesson at `w600dp` fontScale 1.0, asserting no mid-word break in every configuration, a single row at `w411dp` 1.0, and the 2×2 reflow (segment tops differ) in at least one configuration where the one-row segment is narrower than a label word (pick it from measured word widths so the test proves one row would split); the 600 dp test renders the real Lesson/physical Lesson panel; selection/radio semantics still hold in grid mode; dark-theme pixel or color check of Solve Playback and the rail Enter-colors area showing `DarkTokens.surfaceBase`; net `net` unclipped bounds (`positionInRoot` + `size`, not the ancestor-clipped `boundsInRoot`) inside `solutionPlayback`/screen at `w411dp-h891dp` (and a short height); transport enabled states at 0/N; seeded-progress cold start shows Learn not Welcome and fresh host shows Welcome; ✕ left edge near the screen's leading edge and left of the title; pause note and playback note start ≥ 16 dp from the edge.

**Acceptance Criteria:**
- Given a compact phone at fontScale 1.5 or 2.0, or a 600 dp two-pane Lesson at 1.0, when the Lesson shows the mode switcher or the physical confirm card, then no label line ends mid-word; the switcher shows a 2×2 grid only when one row would split a word, and at 411 dp and 1.0 it stays one row.
- Given the grid layout, when TalkBack or a test reads the switcher, then it is one selectable group with four RadioButton segments, the selected one marked, and tapping a segment reports that mode.
- Given dark theme, when Solve Playback or the tablet Enter-colors editor is shown, then the background is the theme `surfaceBase`, and status-bar icons contrast with the app theme in light and dark.
- Given a 411 dp phone, when Solve Playback opens, then the whole net is inside the screen.
- Given saved progress or attempts, when the app cold-starts, then the Learn shell shows and Welcome is never shown; given no profile, Welcome shows.
- Given Solve Playback at 0/N, then Step back is disabled and drawn `inkDisabled`, Play and Step forward are enabled and drawn `inkPrimary`.
- Given Library → an algorithm detail, then the ✕ is at the header's leading edge with the title after it.

## Spec Change Log

### 2026-11-13 — PO clarification on the switcher reflow (intent gap resolved)
- Trigger: intent-gap halt (reading a vs b). PO chose (a): the 2×2 grid appears ONLY when a single word would otherwise split inside a segment; labels may wrap at spaces, so the phone at 100% keeps one row.
- Amended: Approach, matrix rows "Switcher too narrow" → "Word would split" / "Words fit, labels wrap", the switcher task, its tests, and the first AC. Also folded in the three patch findings from the first review (physical-playback note margin, unclipped net bounds, real Lesson panel in font-scale tests).
- Avoids: forcing the grid on the phone at 100% (full-label check) and a matrix that demanded the grid at 1.5/2.0 regardless of fit.
- KEEP: everything else in attempt-1 (spotlessCheck build green); reuse it via `git apply`.

## Review Triage Log

### 2026-11-13 — Review pass
- verdicts: 18 findings — high 0, medium 6, low 7, false 1, maybe-false 4
- findings:
  - `[medium]` `[intent_gap]` (blind) Switcher grid test measures the whole label, so it reflows when wrapping between words would be fine — real: on the S24 FE at 100% "Let me try" wraps (issue #137), so this rule turns the phone into a 2×2 grid, against the AC "at 411 dp and 1.0 it stays one row". Switching to a longest-word-only check (tried) makes `largePhone`, `largestPhone`, `narrowLargePhone` and `tabletPane` keep one row with no mid-word break, which contradicts the contract matrix row "phone at fontScale 1.5 or 2.0 → 2×2 grid". The contract and the verbatim intent ("reflow when needed") disagree; attempted change saved at [spec-gh-137-attempt-1.patch](spec-gh-137-attempt-1.patch).
  - `[low]` `[reject]` (blind) Grid segments in one row can have unequal heights when labels wrap differently — same pattern existed in the old single row; cosmetic and rare at the specified scales.
  - `[medium]` `[patch]` (blind) `PhysicalPlaybackControls` still renders `playback:note` with no horizontal margin (SolutionPlayback.kt:357) — real; moot this pass because of the intent gap.
  - `[medium]` `[patch]` (blind) `assertNetFits` uses `boundsInRoot`, which is clipped to ancestors, so a clipped net could pass — real test weakness; moot this pass.
  - `[low]` `[reject]` (blind) Short-height net test mounts `SolutionPlayback` without the screen header and insets — the intent asks for the net inside its container; moot this pass.
  - `[low]` `[reject]` (blind) `assertNoMidWordBreaks` does not check overflow — `assertNoTextOverflow` already covers overflow in the a11y audits; adding it is extra scope.
  - `[low]` `[reject]` (blind) No activity-level test of system-bar appearance — the intent requires a test only for the background.
  - `[false]` `[reject]` (blind) Recreation while restore is suspended could leave the placeholder — `SessionHost` is an injected singleton, `restored` is a shared `CompletableDeferred`, and the `LaunchedEffect` restarts on recreation with the saved `null`, so the decision still runs.
  - `[low]` `[reject]` (edge) A word longer than a 2×2 cell would still split — not reachable at fontScale ≤ 2.0 (cell ≈ 156 dp at 360 dp, longest word ≈ 77 dp).
  - `[medium]` `[intent_gap]` (edge) Full-label comparison forces the grid when only between-word wrapping is needed — same root cause as the first row.
  - `[low]` `[reject]` (verification-gap) System-bar appearance has no `MainActivity` test — same as the blind row; not required by the intent.
  - `[medium]` `[intent_gap]` (intent) The diff implements reading 1b (grid when a whole label doesn't fit on one line) while the intent's wording supports 1a (grid only when a word would split) — same root cause as the first row.
  - `[maybe-false]` `[reject]` (intent) `enableEdgeToEdge` is new and turns on edge-to-edge on API 29–34 too — targetSdk 36 already forces it on 15+, and screens use `safeDrawingPadding`/`Scaffold` insets; would need a run on an API 29–34 device to settle; moot this pass.
  - `[medium]` `[patch]` (intent) Font-scale tests render the switcher and card alone at full width, not inside the real Lesson panel — real test-surface gap; moot this pass.
  - `[maybe-false]` `[reject]` (intent) Net test does not cover the full `SolvePlaybackScreen` — the component fills the screen minus the close row; would need a screen-level bounds check; moot.
  - `[low]` `[reject]` (intent) System bars are applied in `LaunchedEffect`, one frame after the first draw — negligible.
  - `[maybe-false]` `[reject]` (intent) Shared `PlaybackTransport` note margin also changes Library and Lesson demos untested — margin is the standard one; would need screenshots to judge; moot.
  - `[maybe-false]` `[reject]` (intent) No dark-theme transport colour test — colours are tokens and dark surfaces are covered by item-2 pixel tests; would need a dark capture of the transport; moot.

### 2026-11-14 — Review pass
- verdicts: 17 findings — high 0, medium 0, low 15, false 2, maybe-false 0
- findings:
  - `[low]` `[reject]` (blind) A word wider than a 2×2 cell would still split; no one-column fallback — carried: not reachable at fontScale ≤ 2.0, and the PO allows only one row or 2×2.
  - `[low]` `[reject]` (blind) Segments in one grid row can have unequal heights when only one label wraps — carried: same pattern as the old single row; cosmetic.
  - `[low]` `[defer]` (blind) The net gets only the remaining height and can shrink toward zero at large font or short landscape — pre-existing (the old box also took the remainder with `weight(1f, fill = false)`); recorded in `deferred`.
  - `[low]` `[reject]` (blind) `assertNetFits` does not check overlap with controls or a minimum size — the net sits in a Column, so overlap cannot happen; the test now renders the full `SolvePlaybackScreen` and uses unclipped `positionInRoot` + `size`.
  - `[low]` `[reject]` (blind) The switcher test repeats the production measurement formula — `normalPhone` pins one row and the two real 600 dp Lesson tests pin the grid independently; `assertNoMidWordBreaks` is the independent outcome check.
  - `[low]` `[reject]` (blind) The restore placeholder is an empty surface with no loading indicator — restore is a local read that takes a frame or two; the spec chose a plain surface to avoid a Welcome flash, and a spinner would add new text and scope.
  - `[low]` `[reject]` (blind) No activity-level test of system-bar appearance — carried: the intent asks for a test of the background only.
  - `[low]` `[reject]` (verification-gap) `MainActivity` system-bar style has no activity test (filed as patch) — carried: same location and claim as the earlier row; not required by the intent.
  - `[false]` `[reject]` (intent) The 600 dp tablet tests assert a grid while the matrix lists 600 dp at 1.0 as a one-row example — the matrix row is conditional ("where every word still fits"), and the PO clarification names 600 dp at 100% as a grid example; the layout is measurement-driven, so the grid appears there only because a word does not fit.
  - `[low]` `[reject]` (intent) Test computes `wordRequiresGrid` with the production formula — same as the blind row above.
  - `[low]` `[reject]` (intent) Font scale is faked with a linear `Density` instead of the system's non-linear scale — production measures and renders through the same `LocalDensity`, so it stays self-consistent; changing it adds no coverage of the rule.
  - `[low]` `[reject]` (intent) System-bar icons have no `MainActivity` test — same as the carried rows above.
  - `[low]` `[reject]` (intent) Dark-surface tests sample one pixel each — a pixel at a corner away from content is enough to show the window background is no longer visible.
  - `[low]` `[reject]` (intent) `MainActivity` now passes a constant `showWelcome = true` and is not exercised — the decision moved into `AppShell`, which three new tests cover (progress, attempts only, failed catalog) plus existing fresh-install tests.
  - `[low]` `[reject]` (intent) Transport colour test is light theme only and skips restart — carried: colours are theme tokens; restart has no enable rule change.
  - `[low]` `[reject]` (intent) The ✕ is tested only in portrait — landscape two-pane uses the same `title` composable.
  - `[false]` `[reject]` (intent) `ConfirmCubeCard` narrows button content padding, a lever not in the intent — the spec task explicitly allows `Spacing.s3` content padding.

## Design Notes

Whole-word fit check (sketch): with `val m = rememberTextMeasurer()`, a label fits a content width `w` when `label.split(' ').all { m.measure(it, style).size.width <= w }` (measure at the current `LocalDensity`, so fontScale counts). Single row content width = `maxWidth / n − 2·Spacing.s3`. Compose only breaks inside a word when that word is wider than the line, so this check is exact.

Welcome: deciding after `awaitRestored()` fixes the race at its source; a persisted onboarding flag would add a persisted entity, which the spine forbids without need.

## Verification

**Commands:**
- `export JAVA_HOME=/usr/lib/jvm/java-25-openjdk-amd64 ANDROID_HOME=$HOME/Android/Sdk && ./gradlew :app:testDebugUnitTest --tests '*Switcher*' --tests '*AppShell*' --tests '*SolutionPlayback*' --tests '*AlgorithmDetail*' --tests '*Lesson*'` -- expected: pass.
- `./gradlew spotlessApply && ./gradlew spotlessCheck build` -- expected: BUILD SUCCESSFUL.

## Auto Run Result

Status: done

**Summary:** All seven #137 build items are fixed. The mode switcher reflows to a 2×2 grid only when a single word would split in its one-row segment; labels may still wrap at spaces, so the phone at 100% keeps one row. The confirm buttons stack under the same rule. Solve Playback and the tablet rail destination paint `surfaceBase`, and system-bar icons follow the resolved app theme. The playback net is sized to the largest 12:9 box that fits. Welcome is decided after the session restore, with a plain surface until then. The pause note and both playback notes have the `Spacing.s4` margin, and the algorithm-detail ✕ sits at the leading edge. The attempt-1 review fixes (physical-playback note margin, unclipped net bounds, real Lesson panel at 600 dp) are included.

**Files changed:**
- `MainActivity.kt`: system-bar style from the resolved theme; `showWelcome = true` (the shell decides).
- `ui/lesson/TeachingModeSwitcher.kt`: per-word fit check; one row, or rows of two.
- `ui/lesson/ConfirmCubeCard.kt`: per-word fit check; side-by-side or stacked; `s3` content padding.
- `ui/shell/AppShell.kt`: `Boolean?` welcome decided after `awaitRestored()`; placeholder; rail destination `Surface(surfaceBase)`.
- `ui/solve/SolvePlaybackScreen.kt`: `Surface(surfaceBase, inkPrimary)` behind the screen.
- `ui/solve/SolutionPlayback.kt`: net sized from width and height; physical note margin.
- `ui/solve/SolveScreen.kt`, `ui/playback/PlaybackTransport.kt`: `Spacing.s4` margin on the notes.
- `ui/library/AlgorithmDetailScreen.kt`: title row `fillMaxWidth()`.
- Tests: new `TeachingModeSwitcherTest`; `assertNoMidWordBreaks` helper; tests extended in `LessonScreenTest`, `PhysicalLessonScreenTest` (both 600 dp, real panel), `AppShellSkipCheckTest`, `AppShellSolveTest`, `SolutionPlaybackTest` and `AlgorithmDetailScreenTest`.

**Review:** 17 findings. 0 patched, 1 deferred (low, pre-existing net remainder height), 16 rejected; each reason is in the triage log above.

**Follow-up review:** not recommended (0 patched: high 0, medium 0, low 0).

**Verification:** Codex ran the filtered tests (277 passed). `./gradlew spotlessCheck build` (JDK 25) gave BUILD SUCCESSFUL, re-run by the runner. JUnit XML confirms every new test ran with 0 failures. The matrix test audit passed.

**Residual risks:** Fit is measured with Robolectric fonts, so real-device font metrics may pick one row or the grid at different widths; the rule itself is measurement-driven. Physical-device visual and TalkBack checks were not run. `enableEdgeToEdge` is now applied on API 29–34 too (screens already use insets padding).
