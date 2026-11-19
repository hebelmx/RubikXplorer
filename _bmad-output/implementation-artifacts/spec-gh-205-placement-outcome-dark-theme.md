---
title: 'GH-205 Placement screen: theme surface, status-bar contrast and task-flow top bar'
type: 'bugfix'
created: '2026-11-19'
status: 'done'
baseline_revision: '833d494c2b1f53127fa100b009cf6c9d79f0863a'
review_loop_iteration: 0
followup_review_recommended: false
context: []
warnings: []
deferred: []
---

<intent-contract>

## Intent

**Problem:** `PlacementScreen` draws its Result, Choosing and Editing phases straight onto the window with no `Surface`, so in dark theme it shows a light background while `MainActivity` (correctly, for a dark app) sets `SystemBarStyle.dark` — light status-bar icons over a light background become unreadable on the Galaxy S24 FE. It also lacks the 56dp top bar with a close affordance that the other full-screen task flows (e.g. `SolvePlaybackScreen`) use (UX-DR1, UX-DR30, EXPERIENCE.md "Full-screen task flows").

**Approach:** Wrap `PlacementScreen` in a full-size `Surface(color = RubikTheme.tokens.surfaceBase, contentColor = RubikTheme.tokens.inkPrimary)` that paints edge-to-edge (behind the status bar), so the existing theme-driven `enableEdgeToEdge` icon style in `MainActivity` sits over a matching background. Add the same top bar as `SolvePlaybackScreen` — a `Row(Modifier.fillMaxWidth().heightIn(min = 56.dp))` holding an `IconButton` with `R.drawable.ic_close` and content description `btn.solve.close` — on every non-Idle phase, routed to `flow.back()`.

## Boundaries & Constraints

**Always:** The Surface is outermost and fills the whole screen (insets padding goes inside it, as in `SolvePlaybackScreen`). The top-bar close does exactly what system Back does in that phase (`flow.back()`: Choosing → Result, Result/Editing → Idle) and does nothing while `accepting` is true (disable the button). Learner text comes only from `strings.resolve(ContentText(...))` with existing keys. Existing `AppShellPlacementTest` cases keep passing unchanged.

**Never:** No change to `MainActivity` system-bar logic, `PlacementFlowViewModel`, `AppShell` routing, theme tokens, content strings, feature flags or planning artifacts. No new Learner-text keys. No Material `TopAppBar`/`Scaffold` (match the existing `SolvePlaybackScreen` pattern instead).

## I/O & Edge-Case Matrix

| Scenario | Input / State | Expected Output / Behavior | Error Handling |
|----------|--------------|---------------------------|----------------|
| Dark Result | `CubentiaTheme(darkTheme = true)`, Result phase | Screen pixels (incl. top-left corner under the status-bar area) are `DarkTokens.surfaceBase`; prompt text uses `inkPrimary` content color | No error expected |
| Light Result | light theme, Result phase | Background `LightTokens.surfaceBase` | No error expected |
| Close from Choosing | Choosing phase, tap `placement:close` | Returns to Result (prompt shown), nothing written | No error expected |
| Close from Result / Editing | tap `placement:close` | Returns to Welcome, no progress written, not onboarded | No error expected |
| Close while accepting | `accepting = true` | Close button disabled; phase unchanged | No error expected |

</intent-contract>

## Code Map

- `app/src/main/kotlin/dev/cubentia/app/ui/placement/PlacementScreen.kt` -- the screen; currently a bare `Column(modifier.fillMaxSize().safeDrawingPadding().padding(Spacing.s4))`. Editing phase has its own `TextButton(flow::back)` labelled `btn.solve.close` — replaced by the shared top bar. `BackHandler { if (!accepting) flow.back() }` stays.
- `app/src/main/kotlin/dev/cubentia/app/ui/solve/SolvePlaybackScreen.kt:55-66` -- reference pattern: `Surface(fillMaxSize, surfaceBase, inkPrimary)` → `Column(fillMaxSize().safeDrawingPadding())` → `Row(fillMaxWidth().heightIn(min = 56.dp)) { IconButton(testTag "solvePlayback:close") { Icon(painterResource(R.drawable.ic_close), contentDescription = strings.resolve(ContentText("btn.solve.close"))) } }`.
- `app/src/main/kotlin/dev/cubentia/app/MainActivity.kt` (`setContent`, `LaunchedEffect(dark)`) -- read-only: already picks `SystemBarStyle.dark`/`light` from the app theme; the bug is the missing background, not this logic.
- `app/src/main/kotlin/dev/cubentia/app/ui/placement/PlacementFlowViewModel.kt:96` `back()` -- read-only: Choosing → Result, otherwise → Idle.
- `app/src/main/kotlin/dev/cubentia/app/ui/shell/AppShell.kt:394-436` -- read-only: renders `PlacementScreen(... modifier = modifier ...)` and returns.
- `app/src/test/kotlin/dev/cubentia/app/ui/shell/AppShellPlacementTest.kt` -- existing Robolectric suite; `show()` uses `CubentiaTheme { AppShell(...) }` (light), `startScan()`, `deliver(...)`, `back` dispatcher. Extend here.
- `app/src/test/kotlin/dev/cubentia/app/ui/solve/SolutionPlaybackTest.kt:791-803` -- reference dark-surface pixel test (`@GraphicsMode(NATIVE)`, `captureToImage().toPixelMap()`, compare to `DarkTokens.surfaceBase`).

## Tasks & Acceptance

**Execution:**
- `app/src/main/kotlin/dev/cubentia/app/ui/placement/PlacementScreen.kt` -- wrap in full-size themed `Surface`; inside, `Column(fillMaxSize().safeDrawingPadding().testTag("placement:screen"))` with the 56dp top-bar `Row` + `IconButton(testTag "placement:close", enabled = !accepting, onClick = flow::back)`, then the existing body in a `Column(Modifier.weight(1f).padding(Spacing.s4), spacedBy(Spacing.s2))`; remove the Editing phase's `TextButton` close -- single scaffold across all phases.
- `app/src/test/kotlin/dev/cubentia/app/ui/shell/AppShellPlacementTest.kt` -- add a `dark` parameter to `show()` (passed to `CubentiaTheme(darkTheme = dark)`); add tests: (a) dark Result paints `DarkTokens.surfaceBase` at the top-left pixel of `placement:screen`'s root capture and the prompt's content color resolves to `DarkTokens.inkPrimary` (e.g. via a native-graphics pixel/`LocalContentColor` check), plus Choosing and Editing phases paint `DarkTokens.surfaceBase`; (b) `placement:close` is displayed in Result, Choosing and Editing, Choosing → Result, Result → Welcome, Editing → Welcome, with no records written -- covers I/O matrix.

**Acceptance Criteria:**
- Given the app in dark theme and a Placement Result, Choosing or Editing phase, when the screen renders, then its full area including the status-bar region is painted with `RubikTheme.tokens.surfaceBase` and content defaults to `inkPrimary`, so the dark status-bar style's light icons are readable (and the light theme's dark icons sit on the light surface).
- Given any non-Idle Placement phase, when the Learner looks at the top of the screen, then a 56dp-min top bar shows a close icon labelled "Close", and tapping it behaves like system Back.

## Verification

**Commands:**
- `export JAVA_HOME=/usr/lib/jvm/java-25-openjdk-amd64 ANDROID_HOME=$HOME/Android/Sdk; ./gradlew :app:testDebugUnitTest --tests 'dev.cubentia.app.ui.shell.AppShellPlacementTest'` -- expected: all pass, including new tests.
- `./gradlew spotlessCheck build` -- expected: BUILD SUCCESSFUL.

## Spec Change Log

## Review Triage Log

### 2026-11-19 — Review pass
- verdicts: 12 findings — high 0, medium 1, low 7, false 4, maybe-false 0
- findings:
  - `[medium]` `[patch]` (intent-alignment) Criterion 2 (status-bar contrast) is untested; Robolectric fixture supplies no status-bar inset — grouped with the verification-gap and blind-hunter inset findings. Patched: added `dark placement paints behind the status bar and keeps the 56dp top bar below it`, which dispatches a 96px status-bar inset and asserts the inset region is `DarkTokens.surfaceBase` and the top bar sits below it; mutation (moving `safeDrawingPadding()` onto the Surface) makes it fail.
  - `[false]` `[reject]` (intent-alignment) Close in Choosing steps back to Result instead of leaving like SolvePlayback's close — the intent asks for a "back/close affordance"; the spec's Always rule makes it equal system Back, which already steps Choosing → Result (existing test `system Back steps from the chooser...`).
  - `[low]` `[reject]` (intent-alignment) Light-theme Choosing/Editing and non-Result ink not pixel-checked — intent (4) asks for dark-theme tokens only; one Surface covers every phase, so a per-phase light check adds no distinct risk.
  - `[low]` `[patch]` (intent-alignment) 56dp top-bar height never asserted — grouped with blind-hunter toolbar-bounds finding. Patched: added `placement:topBar` tag and asserted height >= 56dp in the inset test.
  - `[false]` `[reject]` (intent-alignment) Tests live in AppShellPlacementTest rather than standalone — intent (4) explicitly places them alongside the existing AppShellPlacementTest cases.
  - `[medium]` `[patch]` (blind-hunter) Surface test samples only pixel [0,0] without MainActivity/insets — same root cause as the first row; covered by the inset test patch.
  - `[low]` `[patch]` (blind-hunter) Close tests would pass if the toolbar lost its 56dp height — same root cause as the height row; patched by the height assertion.
  - `[false]` `[reject]` (blind-hunter) Pending save from a chooser row is untested — the Close button's `enabled = !accepting` and the BackHandler guard are phase-independent, so the guard cannot fail only in Choosing.
  - `[false]` `[reject]` (blind-hunter) Close may not become usable again after a failed save — `AppShell` resets `acceptingPlacement = false` in `finally`, re-enabling Close; the existing `failed write keeps the placement result` test covers the failure path.
  - `[low]` `[reject]` (blind-hunter) Toolbar steals editor height in compact landscape — the Editing phase previously carried a ~48dp TextButton close; the net change is ~8dp, and a fix would add layout complexity for a rare viewport.
  - `[low]` `[reject]` (edge-case-hunter) Close tapped in the same frame as a save start, before recomposition, could dismiss — requires two taps on different controls within one frame; the BackHandler has the same staleness pre-change, and a real fix needs a new guard in AppShell.
  - `[low]` `[reject]` (edge-case-hunter) Header plus save error may leave no scroll viewport in a very short window — the save error was already outside the scroll region before this change; 56dp header in a ≥ phone-height window leaves ample space; fix would restructure layout.

## Auto Run Result

**Summary:** `PlacementScreen` now wraps all phases in a full-size `Surface(surfaceBase, inkPrimary)` that paints behind the system bars (insets padding inside), so the theme-driven status-bar icon style from `MainActivity` sits on a matching background. A shared 56dp top bar with an `ic_close` IconButton ("Close", `placement:close`) replaces the Editing-only TextButton and behaves like system Back (disabled while a save is pending).

**Files changed:**
- `app/src/main/kotlin/dev/cubentia/app/ui/placement/PlacementScreen.kt` -- themed Surface, shared 56dp top bar with close, body in a weighted padded column.
- `app/src/test/kotlin/dev/cubentia/app/ui/shell/AppShellPlacementTest.kt` -- `dark` theme option; tests for dark/light surface across phases, dark prompt ink, status-bar inset painting and 56dp bar below it, Close navigation without writes, Close/Back blocked during a pending save.

**Review findings:** 12 findings; patches applied for 2 root causes (status-bar inset test — medium; 56dp height assertion — low); 0 deferred; 8 rejected with reasons in the triage log.

**Follow-up review recommendation:** false — patched entries: 1 medium, 1 low (first pass; no high, fewer than two medium).

**Verification:** `./gradlew :app:testDebugUnitTest --tests 'dev.cubentia.app.ui.shell.AppShellPlacementTest'` -- 19 passed, 0 failed. `./gradlew spotlessCheck build` -- BUILD SUCCESSFUL. Mutation check: moving `safeDrawingPadding()` onto the Surface fails the new inset test.

**Residual risks:** Status-bar contrast is verified via Robolectric pixels with a synthetic inset, not on the Galaxy S24 FE; a device check of both themes is still worthwhile.
