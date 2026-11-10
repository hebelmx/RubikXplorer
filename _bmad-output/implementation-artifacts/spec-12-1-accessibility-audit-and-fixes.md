---
title: 'Accessibility audit and fixes'
type: 'feature'
created: '2026-11-10'
status: 'done'
baseline_revision: 'fa0f12c40692416cdf64b0491bdbb8e4020b6d5c'
review_loop_iteration: 0
followup_review_recommended: true
context:
  - '{project-root}/_bmad-output/implementation-artifacts/epic-12-context.md'
warnings: ['oversized']
deferred:
  - summary: >-
      The 200% Lesson audit covers only the first lesson in its first virtual and physical states, not mismatch, diagnosis, recovery, skip-check or two-look content.
    evidence: |-
      ScreenAudit.kt drives ready.lessons.first() and checks physical mode right after the switch. Unverified: driving the mismatch ladder, diagnosis and recovery states and the other lessons at 200% compact and expanded would show whether any panel text overflows or any control drops below 48dp.
    location: >-
      app/src/test/kotlin/dev/cubentia/app/ui/a11y/ScreenAudit.kt
    severity: medium (unverified)
---

<intent-contract>

## Intent

**Problem:** NFR-7 / PRD gate 5 need the app proven accessible, but today only part of it is enforced: `ThemeTokensTest` checks text and status-icon contrast but not status *chip text* (UX-DR1 says ≥4.5:1); nothing renders screens at 200% font scale, and the Lesson title, stage caption and milestone buttons truncate or clip there; Android lint runs with stock severities, so a11y issues are warnings that never fail CI (AD-14); `StickerReviewNet` stickers are clickable with no role or click label; `Net2DRenderer`/`StickerReviewNet` mirror under RTL (deferred `epic-9-deferred-9-3-rtl-net-mirroring`); nothing guards UX-DR28 banned patterns.

**Approach:** Automate every check that can be automated, fix what they find, and hand the product owner a concise TalkBack/switch-access checklist for the device pass (AC3's physical pass happens after the PR opens). Story ACs: (1) the token contrast test in CI covers every text/status/highlight pair at the DESIGN.md ratios in both themes; (2) at 200% dynamic type on compact and expanded layouts every screen is checked: the Lesson panel reflows and scrolls, the cube stage keeps its minimum size, no control or text truncates; (3) every essential action has a non-gesture route and a content label (Compose semantics tests, lint a11y checks as errors and green), and no UX-DR28 banned pattern is present.

## Boundaries & Constraints

**Always:** JVM/Robolectric tests for every automated AC (they run in CI through `./gradlew build`); lint gate changes live in `build-logic` with a build-logic unit test; Learner text only via existing content keys or existing `strings.xml` names (no new Learner wording except the checklist doc); fixes keep current behavior and visual layout at 100% scale; `:core:*` stays Android-free.

**Never:** no token hex changes (DESIGN.md is final; if a required pair fails, HALT — do not retune); no lint baseline file and no `@SuppressLint`/`tools:ignore` to silence a11y issues; no new dependency; no new Learner strings in `strings.xml` (the `checkLearnerText` baseline gate rejects them); never edit planning artifacts, `_bmad/`, `.claude/`; do not wire `SolutionPlayback` into `AppShell`; do not touch the 12.11/12.12 session code.

## I/O & Edge-Case Matrix

| Scenario | Input / State | Expected Output / Behavior | Error Handling |
|----------|--------------|---------------------------|----------------|
| Status chip text | any status token as `CertaintyChip` text on `surfaceRaised`, and status text on `surfaceBase`/`surfaceRaised`, both themes | ≥ 4.5:1 | test names the failing pair |
| Status icon | each status on every surface token, both themes | ≥ 3:1 | as above |
| Lesson at 200%, compact (`w360dp-h640dp`) | virtual Lesson open | no text node has visual overflow; `lesson:stage` height ≥ 280dp; `lesson:panel` scrollable and scrolling reveals its last node | — |
| Lesson at 200%, expanded (`w900dp-h891dp`) | virtual Lesson open | no overflow; panel width ≤ 560dp; stage ≥ 280dp | — |
| Other screens at 200%, compact + expanded | Welcome, Learn, Stage detail, Library, Algorithm detail, Solve tab, You, Settings + reset dialog, Manual editor, first-solve milestone | no text node has visual overflow; every clickable node ≥ 48dp tall | — |
| Unlabelled action | any node with an `OnClick` action on the screens above at 100% | has a non-blank merged `ContentDescription` or `Text` | test names screen and node |
| Non-gesture routes | Solve tab and virtual Lesson | Move key bar offers all 18 face turns (incl. its extra-keys toggle); View controls offer rotate left/right/up/down and reset view; cube stage exposes the *Read faces* custom action | — |
| RTL nets | `LocalLayoutDirection = Rtl` | `StickerReviewNet` and `Net2DRenderer` stickers sit at the same x positions as in LTR | — |
| Banned copy | content `en.json` | "!" only in `msg.milestone.*` values; no value contains streak/points/badge/countdown words | test names the key |
| Banned APIs | `app/src/main/kotlin` sources | no `HorizontalPager`, `VerticalPager`, `Carousel`, `NotificationCompat`, `NotificationManager`, `POST_NOTIFICATIONS` | test names the file |
| Lint a11y | `:app:lintDebug` | a11y issue ids are `error` severity and lint passes | build fails on a new issue |

</intent-contract>

## Code Map

- `app/src/test/kotlin/dev/cubentia/app/ui/theme/ThemeTokensTest.kt` -- contrast test (L96-108, `assertAtLeast` L150). Add chip-text ≥4.5 pairs: each `t.statuses` on `surfaceRaised` (`CertaintyChip.kt:51` background) and `surfaceBase`. Verified math: light `statusVerified` on `surfaceStage` is 4.45 — do NOT assert status text on `surfaceStage`; first grep for status-colored `Text` (`CertaintyChip.kt`, `LessonPanel.kt:245`, `TwoLookOffer.kt:31`) and confirm none sits on `surfaceStage`; if one does, HALT.
- `app/src/main/kotlin/dev/cubentia/app/ui/theme/Tokens.kt` L31-34 -- `surfaces`/`statuses` helpers; read-only.
- `app/src/main/kotlin/dev/cubentia/app/ui/lesson/LessonScreen.kt` L458-465 top-bar title `maxLines=1`+Ellipsis and L1137-1146 `stage:caption` `maxLines=1`+Ellipsis -- let both wrap (drop maxLines/overflow); `lesson:stage` L700; compact/expanded split L828-857, panel cap L1294-1297.
- `app/src/main/kotlin/dev/cubentia/app/ui/lesson/FirstSolveMilestone.kt` L147, L164, L179, L197 -- `.height(Spacing.touchMin)` → `.heightIn(min = Spacing.touchMin)`.
- Any further overflow the new 200% test finds: fix at the source with the same patterns (wrap instead of ellipsize, `heightIn(min=)` instead of fixed height, `verticalScroll` on a non-scrolling column). Known-safe: `MoveKeyBar` rows scroll sideways (`MoveKeyBar.kt:110`); `StickerGlyph.kt:32` ignores font scale on purpose (exclude glyph text from the overflow check by its tag if needed, with a comment).
- `app/src/main/kotlin/dev/cubentia/app/ui/editor/StickerReviewNet.kt` L145 `clickable(onClick)` -- add `role = Role.Button` (the existing `contentDescription` from `R.string.editor_sticker` stays the label; no new string); L111-115 grid: wrap in `CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Ltr)` like `RecognitionDiagramView.kt:47`.
- `app/src/main/kotlin/dev/cubentia/app/render/Net2DRenderer.kt` L157-168 -- same LTR lock.
- `app/src/main/kotlin/dev/cubentia/app/ui/shell/AppShell.kt` -- navigation via `nav:*` tags; tabs L435-556, width class L558-596 (`WindowWidthClass.kt`, 600/840dp). Read-only; drive screens through it.
- `app/src/main/kotlin/dev/cubentia/app/ui/freecube/MoveKeyBar.kt` L150-173, `ViewControls.kt` L22-42, `render/Cube3DRenderer.kt` L335-349 (*Read faces* custom action) -- existing non-gesture routes to assert, not change.
- `build-logic/src/main/kotlin/dev/cubentia/buildlogic/AndroidAppConventionPlugin.kt` L18-34 -- add `lint { abortOnError = true; error += AccessibilityLint.ERROR_ISSUES }` inside `extensions.configure<ApplicationExtension>`.
- `build-logic/src/main/kotlin/dev/cubentia/buildlogic/policy/AccessibilityLint.kt` (new) -- `object AccessibilityLint { val ERROR_ISSUES = setOf("ContentDescription", "ClickableViewAccessibility", "KeyboardInaccessibleWidget", "LabelFor", "GetContentDescriptionOverride") }` with KDoc citing AD-14.
- `build-logic/src/test/kotlin/dev/cubentia/buildlogic/policy/AccessibilityLintTest.kt` (new) -- pins the set.
- Test patterns: `app/src/test/kotlin/dev/cubentia/app/ui/settings/SettingsScreenTest.kt` (drives `AppShell` by `nav:*` tags, `@Config(application = Application::class, qualifiers = ...)`, `createComposeRule`); `ui/library/AlgorithmDetailScreenTest.kt` (window sizes + RTL); font scale inline as in `render/Cube3DRendererTest.kt:307` (`CompositionLocalProvider(LocalDensity provides Density(d.density, fontScale = 2f))`); `ui/editor/ManualCubeEditorTest.kt`, `ui/lesson/FirstSolveMilestoneTest.kt`, `ui/lesson/LessonScreenTest.kt` for direct composable setup.
- `core/domain/src/main/resources/content/strings/en.json` -- read on the app test classpath; current "!" only at `msg.milestone.first-solve` and `msg.milestone.two-look-mastered`.
- `_bmad-output/planning-artifacts/ux-designs/ux-RubikXplorer-2026-10-29/EXPERIENCE.md` L164-181 (Accessibility Floor), L205-290 (Key Flows; Flows 4-5 are v1.1, Flow 1 has no Quick Check and the ladder starts at rung 2, Flow 9 is step 4 only in v1.0) -- read-only source for the checklist.

## Tasks & Acceptance

**Execution:**
- `app/src/test/kotlin/dev/cubentia/app/ui/theme/ThemeTokensTest.kt` -- add status chip/text ≥4.5:1 pairs per the Code Map -- AC1.
- `app/src/test/kotlin/dev/cubentia/app/ui/a11y/A11yAssertions.kt` (new) -- shared helpers: `fontScale200 {}` wrapper; `assertNoTextOverflow(screen)` walking unmerged nodes with `SemanticsActions.GetTextLayoutResult` and failing on `hasVisualOverflow`, naming screen and text; `assertClickablesLabelled(screen)` (merged tree, every `OnClick` node has non-blank `ContentDescription` or `Text`); `assertClickablesAtLeast48dp`.
- `app/src/test/kotlin/dev/cubentia/app/ui/a11y/DynamicType200Test.kt` (new) -- Robolectric, compact `w360dp-h640dp` and expanded `w900dp-h891dp`, fontScale 2; every screen in the matrix; Lesson stage/panel assertions -- AC2.
- `app/src/main/kotlin/dev/cubentia/app/ui/lesson/LessonScreen.kt`, `FirstSolveMilestone.kt`, plus any screen the test flags -- truncation fixes -- AC2.
- `app/src/test/kotlin/dev/cubentia/app/ui/a11y/EssentialActionsTest.kt` (new) -- every screen at 100%: clickables labelled; Solve tab and virtual Lesson non-gesture routes per matrix -- AC3.
- `app/src/main/kotlin/dev/cubentia/app/ui/editor/StickerReviewNet.kt`, `app/src/main/kotlin/dev/cubentia/app/render/Net2DRenderer.kt` -- role + LTR lock; RTL position tests in `ui/editor/StickerReviewNetRtlTest.kt` and `render/Net2DRendererRtlTest.kt` (new) -- AC3 + deferred RTL item.
- `app/src/test/kotlin/dev/cubentia/app/ui/a11y/BannedPatternsTest.kt` (new) -- `en.json` and source scans per matrix; source dir resolved from the test working dir (`app/`) with a clear failure if missing -- AC3.
- `build-logic/.../policy/AccessibilityLint.kt`, `AndroidAppConventionPlugin.kt`, `AccessibilityLintTest.kt` -- lint a11y as errors; fix any issue lint then reports in source -- AC3.
- `docs/accessibility-manual-check.md` (new) -- concise device checklist for the product owner: setup (TalkBack on, Switch Access with two switches, font 200%, reduce motion, dark+light); then per v1.0 Key Flow (1, 2, 3, 6, 7, 8, 9-step-4; note 4-5 are v1.1) one short list of checks: every step reachable by swipe-navigation and switch scanning, each control's spoken label, focus order frame label → cube description → Move key bar → lesson panel → mode switcher, Certainty changes announced, *Read faces* output, Move keys speak plain names, View Rotation by buttons, no banned pattern; plus a results table (flow, device, pass/fail, notes) and a link back to AC3.

**Acceptance Criteria:**
- Given CI runs `./gradlew build`, when `ThemeTokensTest` runs, then every text, status (icon and chip text) and highlight pair meets the DESIGN.md ratios in both themes.
- Given the new 200% tests, when they run on compact and expanded configurations, then every listed screen shows no text overflow, the Lesson panel scrolls, the stage stays ≥ 280dp, and the expanded panel stays ≤ 560dp.
- Given `:app:lintDebug` with the a11y issue ids at error severity, when `build` runs, then it passes with no baseline or suppression.
- Given the semantics tests, when they run, then every clickable node on every listed screen has a label, and every gesture action has its button route.
- Given `docs/accessibility-manual-check.md`, when the product owner opens it, then it lists every v1.0 Key Flow with TalkBack and switch-access checks and a place to record results.

## Spec Change Log

## Review Triage Log

### 2026-11-10 — Review pass
- verdicts: 19 findings — high 0, medium 8, low 7, false 4, maybe-false 0
- findings:
  - `[low]` `[reject]` (blind) Overflow check misses text clipped by an ancestor without a constraint — a fixed-height parent already shrinks the layout and is caught (`A11yAssertionsTest` fixed-height case); an ancestor-clip-bounds walk adds a new mechanism for an unseen case.
  - `[medium]` `[patch]` (blind) Touch-target check verifies height only — `assertClickablesAtLeast48dp` now checks width too; it found `stage:viewMenu` squeezed to 25dp at compact 200%, fixed by wrapping the Lesson stage header in a `FlowRow`.
  - `[low]` `[reject]` (blind) Library audit only sees composed grid items — every tile is the same composable with the same label pattern; scrolling the whole grid adds cost for no distinct surface.
  - `[low]` `[reject]` (blind) Routes asserted by `OnClick` presence, not effect — effects of Move keys and view controls are already asserted by the existing `ui/freecube` and Lesson tests; this audit only proves the route exists.
  - `[medium]` `[patch]` (blind) Extra Move keys and View Rotation menu never opened at 200% — `auditStageExtras` opens both on Solve and Lesson and checks them; found the slice row squeezed to 12dp, fixed with `CubeAndKeys` layout (keys natural height, cube ≥ 96dp, keys scroll).
  - `[medium]` `[patch]` (blind) Milestone fixture lacks the next-stage variant — added a stage-mastery milestone surface with `nextStageLabel`/`onNextStage`, audited at 200%.
  - `[medium]` `[patch]` (blind) Lesson audited only in virtual state — audit switches `lesson:physicalModeSwitch` on, re-checks, asserts stage ≥ 280dp.
  - `[medium]` `[patch]` (edge) Clickable narrower than 48dp passes — same root cause and fix as the touch-target row above.
  - `[low]` `[reject]` (edge) Ancestor-clipped text passes — same as the first row; rejected for the same reason.
  - `[medium]` `[patch]` (verification-gap) Stage-mastery buttons bypass the 200% audit — same fix as the milestone-fixture row.
  - `[medium]` `[patch]` (verification-gap) Lint policy test does not verify enforcement — added `AccessibilityLintFunctionalTest` (AndroidFixture TestKit) applying the real convention plugin and asserting `lint.error` ids and `abortOnError`; mutation (removing `error +=`) made it fail.
  - `[low]` `[reject]` (intent) The five lint ids are View/XML checks with little Compose surface — AC3 asks for lint a11y green, which it is; labels are carried by the Compose semantics test as the spec intended.
  - `[low]` `[reject]` (intent) Behavioural UX-DR28 bans (shake, red flash, auto-advance) not automated — not statically detectable without new machinery; covered by the device checklist.
  - `[medium]` `[patch]` (intent) "Every screen" coverage gaps — Solve stage min size now asserted (`freeCube:stage` ≥ 280dp; was 258dp, fixed in `FreeCubeScreen` by capping controls height); stage-mastery milestone added. SolutionPlayback is not reachable in v1.0 and practice/skip-check reuse the audited `LessonScreen` layout, so those parts were not added.
  - `[false]` `[reject]` (intent) Font scale simulated via `LocalDensity` — linear 2× is at least as large as Android 14 non-linear 200% at every size, so the test is stricter, not weaker.
  - `[low]` `[reject]` (intent) Focus order and Certainty announcements not asserted — UX-DR29 items outside the three ACs; partly covered by existing Story 5.5 tests and listed in the device checklist.
  - `[false]` `[reject]` (intent) Checklist covers v1.0 flows only — Flows 4–5 and Flow 9 steps 1–3 are v1.1 per EXPERIENCE.md; the checklist states so.
  - `[false]` `[reject]` (intent) Status contrast stricter than DESIGN.md — status text appears on `surfaceBase`/`surfaceRaised` (`LessonPanel.kt:245`, `TwoLookOffer.kt:31`), so those pairs are required, and all pass.
  - `[false]` `[reject]` (intent) RTL pinning is out of scope — the deferred item `epic-9-deferred-9-3-rtl-net-mirroring` is assigned to 12.1 by the epic context.

### 2026-11-10 — Review pass
- verdicts: 22 findings — high 3, medium 1, low 10, false 5, maybe-false 3
- findings:
  - `[high]` `[patch]` (blind) `FreeCubeScreen` `controlsMax` is 0 when the window is ≤ 292dp tall, hiding the Move keys and haptics switch in landscape/split screen — the controls now keep a floor of `min(half, CONTROLS_MIN = 2 × touchMin)`; new `FreeCubeScreenTest` case at `w640dp-h320dp` asserts the first Move key is displayed and ≥ 48dp, and fails with the floor removed.
  - `[low]` `[reject]` (blind) Overflow check misses text clipped by an ancestor — carried: same claim as the earlier pass; a fixed-height parent already shrinks the layout and is caught.
  - `[false]` `[reject]` (blind) Screen audit never runs both themes — the audit's assertions (overflow, target size, labels) do not depend on colour, and status-coloured text sits only on `surfaceBase`/`surfaceRaised`, whose pairs `ThemeTokensTest` covers in both themes.
  - `[maybe-false]` `[defer]` (blind) Physical-mode Lesson audit stops right after the switch; mismatch, diagnosis, Certainty and recovery states are not audited at 200% — would need those states driven at 200% to see whether any panel text overflows; if true, medium.
  - `[maybe-false]` `[defer]` (blind) Only `ready.lessons.first()` is audited; longer titles, skip-check and two-look content are not — same Lesson-fixture coverage gap as the row above; would need the other lessons audited at 200%.
  - `[low]` `[reject]` (blind) *Read faces* is only checked to exist, not invoked — carried: same claim as "routes asserted by presence"; its output is asserted by `Cube3DRendererTest` (L217-242).
  - `[low]` `[reject]` (blind) Lint functional test checks the config, not a failing lint run on a seeded issue — testing AGP's own `abortOnError` needs an Android lint run in TestKit; the test proves our configuration and a mutation fails it.
  - `[low]` `[reject]` (blind) No automated guard against a future lint baseline or `@SuppressLint`/`tools:ignore` — none exist today (grep of `app/src/main`, build files, `build-logic`); a new scanning gate is more than a direct fix for a hypothetical future edit.
  - `[high]` `[patch]` (edge) `FreeCubeScreen.kt:109` controls get zero height in short windows — same root cause and fix as the first blind row.
  - `[low]` `[patch]` (edge) `assertNoTextOverflow` passes silently if `GetTextLayoutResult` returns false or nothing — it now asserts the action succeeded with a result, naming the text.
  - `[medium]` `[patch]` (verification-gap) Lesson audit never checks the cube keeps `CUBE_MIN` with every key row open — `auditStageExtras` now asserts `cube3d` ≥ 96dp while the extra Move keys are open (Solve and Lesson, compact and expanded at 200%).
  - `[false]` `[reject]` (intent) Stage minimum checked on the `lesson:stage` container, not the drawn cube — the intent's matrix defines the surface as `lesson:stage` height ≥ 280dp; the cube's own floor is now asserted by the verification-gap patch.
  - `[false]` `[reject]` (intent) 200% simulated via `LocalDensity` rather than the system setting — carried: linear 2× is at least as large as Android's non-linear 200% at every size.
  - `[low]` `[reject]` (intent) Clipped non-text controls are not detected — clickables are caught by the 48×48dp check; detecting clipped non-interactive icons would need a new bounds-vs-ancestor mechanism for no observed case.
  - `[low]` `[reject]` (intent) The five lint ids are View/XML checks with little to fire on in Compose — carried: AC3 asks for lint a11y at error and green, which it is; labels are carried by the Compose semantics test.
  - `[low]` `[reject]` (intent) Build-logic tests check settings, not a failing lint run — same claim and reason as the blind lint-functional row.
  - `[low]` `[reject]` (intent) `StickerReviewNet` gets a role but no `onClickLabel` — the stickers keep their `editor_sticker` content description as the label; a click label needs a new Learner string, which the boundaries forbid, and no existing string fits.
  - `[false]` `[reject]` (intent) Only status pairs were added, not "every pair" — the existing `ThemeTokensTest` case already covers text, accent, status-icon and highlight pairs (L102-115); status text was the missing pair.
  - `[maybe-false]` `[defer]` (intent) "Every screen" exercises one Lesson in its first virtual/physical state — same Lesson-fixture coverage gap as the two blind rows above.
  - `[high]` `[patch]` (intent) 100% behaviour changed: FreeCube controls also capped by `maxHeight - 280dp` in short windows — same root cause and fix as the first blind row.
  - `[false]` `[reject]` (intent) 100% behaviour changed: title/caption wrap, top bar is a `FlowRow`, physical caption moved onto the switch — wrapping is what the spec asks for; a `FlowRow` lays out as one row when its items fit; the switch was unlabelled before and the caption now labels it, which is the earlier pass's fix.
  - `[low]` `[patch]` (intent) Banned-API scan reads only Kotlin sources, so `POST_NOTIFICATIONS` in the manifest would pass — `BannedPatternsTest` now also scans `src/main/AndroidManifest.xml` and fails clearly if it is missing.

## Design Notes

Overflow check: `TextLayoutResult.hasVisualOverflow` is true both for ellipsized lines and for text cut by a fixed-height parent (the constraint shrinks the layout), so one check covers `maxLines` and fixed `height`. Example:

```kotlin
onAllNodes(hasGetTextLayoutResult(), useUnmergedTree = true).fetchSemanticsNodes().forEach { n ->
    val results = mutableListOf<TextLayoutResult>()
    n.config[SemanticsActions.GetTextLayoutResult].action?.invoke(results)
    assertFalse(results.single().hasVisualOverflow, "$screen: '${n.config.getOrNull(SemanticsProperties.Text)}' overflows")
}
```

Physical-mode Lessons hide the Move key bar by design (the Learner turns a real cube and confirms via the mismatch ladder buttons); that is not a gesture-only gap.

## Verification

**Commands:**
- `export JAVA_HOME=/usr/lib/jvm/java-25-openjdk-amd64 ANDROID_HOME=$HOME/Android/Sdk && ./gradlew spotlessCheck build` -- expected: BUILD SUCCESSFUL (includes `:app:lintDebug` and all new tests)
- `./gradlew -p build-logic test` -- expected: BUILD SUCCESSFUL
- `./gradlew :app:testDebugUnitTest --tests 'dev.cubentia.app.ui.a11y.*' --tests '*ThemeTokensTest' --tests '*RtlTest'` -- expected: all new tests ran and passed

## Auto Run Result

**Summary:** Story 12.1 automated: chip-text contrast pairs added to `ThemeTokensTest`; a 200% font-scale audit (compact `w360dp-h640dp`, expanded `w900dp-h891dp`) walks Welcome, Stage detail, Learn, Library, Algorithm detail, Solve (incl. extra Move keys and View menu), You, Settings, Reset dialog, Manual editor, Lesson (virtual and physical, scrolled to end), first-solve and stage-mastery milestones, asserting no text overflow, 48×48dp clickables, stage ≥ 280dp and expanded panel ≤ 560dp; a 100% audit asserts every clickable is labelled and every cube gesture has a button route; banned-copy/API scan; Android lint a11y ids raised to error; RTL lock for the 2D nets; device checklist for the product owner.

**Files changed:**
- `app/src/main/kotlin/dev/cubentia/app/render/Net2DRenderer.kt` -- LTR lock on the net grid.
- `app/src/main/kotlin/dev/cubentia/app/ui/editor/StickerReviewNet.kt` -- LTR lock; editable stickers have `Role.Button`.
- `app/src/main/kotlin/dev/cubentia/app/ui/freecube/FreeCubeScreen.kt` -- controls capped so the stage keeps 280dp; `freeCube:stage` tag.
- `app/src/main/kotlin/dev/cubentia/app/ui/lesson/FirstSolveMilestone.kt` -- buttons use `heightIn(min = 48dp)`.
- `app/src/main/kotlin/dev/cubentia/app/ui/lesson/LessonScreen.kt` -- top bar and stage header wrap (`FlowRow`), title/caption wrap, physical switch labelled, `CubeAndKeys` layout keeps the cube ≥ 96dp with scrolling keys.
- `app/src/main/kotlin/dev/cubentia/app/ui/shell/YouScreen.kt` -- stage row text gets weight so status labels do not collapse.
- `app/src/test/kotlin/dev/cubentia/app/ui/a11y/*` -- `A11yAssertions`, `A11yAssertionsTest`, `ScreenAudit`, `DynamicType200Test`, `EssentialActionsTest`, `BannedPatternsTest`.
- `app/src/test/kotlin/dev/cubentia/app/ui/theme/ThemeTokensTest.kt` -- status text ≥ 4.5:1 on base/raised.
- `app/src/test/kotlin/dev/cubentia/app/render/Net2DRendererRtlTest.kt`, `app/src/test/kotlin/dev/cubentia/app/ui/editor/StickerReviewNetRtlTest.kt` -- RTL position tests.
- `build-logic/src/main/kotlin/dev/cubentia/buildlogic/policy/AccessibilityLint.kt`, `AndroidAppConventionPlugin.kt` -- a11y lint ids at error, `abortOnError`.
- `build-logic/src/test/kotlin/dev/cubentia/buildlogic/policy/AccessibilityLintTest.kt`, `build-logic/src/test/kotlin/dev/cubentia/buildlogic/functional/AccessibilityLintFunctionalTest.kt` -- pin and prove the lint config.
- `docs/accessibility-manual-check.md` -- TalkBack/Switch Access device checklist per v1.0 Key Flow with a results table.

**Review findings:** 19 findings; 8 medium patched (6 distinct fixes: touch-target width, extras at 200%, stage-mastery milestone, physical-mode Lesson, Solve stage minimum, lint enforcement test); 0 deferred; 7 low and 4 false rejected with reasons in the triage log.

**Follow-up review recommended:** true -- 8 medium entries patched (high 0, medium 8, low 0). Unverified risk: the patch phase introduced the `CubeAndKeys` custom layout in `LessonScreen.kt` and the controls height cap in `FreeCubeScreen.kt`; these were verified by tests but never went through a review pass, and on a short phone with all Move key rows open the Lesson cube can shrink to 96dp.

**Verification:** `./gradlew spotlessCheck build` BUILD SUCCESSFUL (incl. `:app:lintDebug` with a11y ids at error, all JVM/Robolectric tests); `./gradlew -p build-logic test` BUILD SUCCESSFUL; JUnit reports show A11yAssertionsTest 3, BannedPatternsTest 2, DynamicType200Test 2, EssentialActionsTest 2, ThemeTokensTest 6, both RTL tests, AccessibilityLintTest and AccessibilityLintFunctionalTest all passing with 0 skipped. Implementation ran on Codex until its usage limit, then a Claude fallback finished it.

**Residual risks:** AC3's TalkBack/Switch Access device pass is still to be done by the product owner with `docs/accessibility-manual-check.md`; the Solve tab has no *Enter colors* route on this branch (Story 3.7, PR #100), so Flows 3 and 6 record it as unavailable; sprint-status item `epic-9-deferred-9-3-rtl-net-mirroring` is resolved by this change but its status entry was not updated here.

### Follow-up review pass (2026-11-10)

**Summary:** Follow-up review of story 12.1. Four Codex/Claude layers reported 22 findings; four distinct patches applied, one item deferred, the rest rejected with reasons in the triage log.

**Files changed in this pass:**
- `app/src/main/kotlin/dev/cubentia/app/ui/freecube/FreeCubeScreen.kt` -- controls keep a floor of two Move key rows (`CONTROLS_MIN`) in short windows instead of collapsing to 0dp.
- `app/src/test/kotlin/dev/cubentia/app/ui/freecube/FreeCubeScreenTest.kt` -- short-window (`w640dp-h320dp`) test that the first Move key row stays on screen.
- `app/src/test/kotlin/dev/cubentia/app/ui/a11y/A11yAssertions.kt` -- overflow check fails if a text node yields no layout.
- `app/src/test/kotlin/dev/cubentia/app/ui/a11y/ScreenAudit.kt` -- cube keeps ≥ 96dp with all extra Move keys open at 200%.
- `app/src/test/kotlin/dev/cubentia/app/ui/a11y/BannedPatternsTest.kt` -- banned-API scan also covers `AndroidManifest.xml`.

**Review findings:** patches applied: 1 high (FreeCube short-window controls, 3 rows), 1 medium (cube minimum assertion), 2 low (silent overflow pass, manifest scan); deferred: 1 (Lesson-fixture coverage at 200%, medium unverified, 3 rows); rejected: 8 low and 5 false, each with its reason in the triage log.

**Follow-up review recommended:** true -- this follow-up pass patched a high (high 1, medium 1, low 2). Unverified risk: the new `CONTROLS_MIN` floor in `FreeCubeScreen.kt` deliberately lets the Solve stage drop below 280dp in windows shorter than ~388dp, and that trade-off is tested only at 100% font scale in one short window, not at 200% or in other short or split-screen sizes.

**Verification:** `./gradlew :app:testDebugUnitTest --tests 'dev.cubentia.app.ui.a11y.*' --tests '*FreeCubeScreenTest'` passed (A11yAssertionsTest 3, BannedPatternsTest 2, DynamicType200Test 2, EssentialActionsTest 2, FreeCubeScreenTest 29); mutation check: removing the `CONTROLS_MIN` floor makes the new short-window test fail. `./gradlew spotlessCheck build` failed once on `Cube3DEmphasisTest` (frame-clock timing; this test also failed in earlier unrelated runs and is untouched by this change), which passed when run alone, and the rerun was BUILD SUCCESSFUL (incl. `:app:lintDebug`); `./gradlew -p build-logic test` BUILD SUCCESSFUL.

**Residual risks:** `Cube3DEmphasisTest` is flaky under full-suite load; the deferred Lesson-state coverage; AC3's device pass with `docs/accessibility-manual-check.md` is still the product owner's.
