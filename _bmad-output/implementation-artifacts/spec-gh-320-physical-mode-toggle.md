---
title: 'Make the Physical mode switch visible and easier to tap'
type: 'bugfix'
created: '2026-11-23'
status: 'done'
baseline_revision: 'fb31743a734c0cc722cc01b75e37d2e2fd4f7a67'
review_loop_iteration: 0
followup_review_recommended: true
context: ['{project-root}/AGENTS.md']
warnings: []
deferred: []
---

<intent-contract>

## Intent

**Problem:** The Physical mode switch is difficult to see when off in dark theme. The Lesson caption doesn't toggle the switch and the playback caption and switch have independent actions.

**Approach:** In Lessons and Solution Playback, put the caption and switch in a single labelled switch control that can be tapped across the row and is visible in both modes and themes. Keep playback's existing “I'm following on my cube” entry point.

## Boundaries & Constraints

**Always:** Reuse the localized mode label, theme tokens, and existing mode-change callbacks. Keep the Lesson and playback eligibility rules, confirmation, progress, and recovery behavior.

**Never:** Add new learner copy, change the session transition rules, modify camera behavior, or change switch styling across the rest of the app.

</intent-contract>

## Code Map

- `app/src/main/kotlin/dev/cubentia/app/ui/lesson/LessonScreen.kt` — LessonTopBar shows the gated switch beside Close and title using FlowRow for large type.
- `app/src/main/kotlin/dev/cubentia/app/ui/solve/SolutionPlayback.kt` — solution playback branches between regular and physical controls. followOnMyCube and switchToVirtual dispatch the existing session events. The PhysicalPlaybackControls switch is currently only on the physical branch.
- `app/src/main/kotlin/dev/cubentia/app/ui/settings/SettingsScreen.kt` — SettingSwitch already uses a toggleable row and a passive Material switch.
- `app/src/main/kotlin/dev/cubentia/app/ui/theme/Tokens.kt` — inkSecondary has sufficient contrast across the backed surfaces. The hairline token currently supplies Material's low-contrast outline.
- `_bmad-output/planning-artifacts/epics.md` — Story 8.2 governs entering a Lesson's Physical mode. Story 8.5 retains the Solution Playback call to action.

## Tasks & Acceptance

**Execution:**
- `app/src/main/kotlin/dev/cubentia/app/ui/components/PhysicalModeSwitch.kt` — share the compact labeled row, switch semantics, and high-contrast unchecked colors.
- `app/src/main/kotlin/dev/cubentia/app/ui/lesson/LessonScreen.kt` — wire the shared row into the existing Lesson gate and callback.
- `app/src/main/kotlin/dev/cubentia/app/ui/solve/SolutionPlayback.kt` — wire the shared row for both modes without changing the existing follow-on-my-cube action or playback state transitions.
- `app/src/test/kotlin/dev/cubentia/app/ui/` — exercise actual label taps, toggle semantics, and contrast in the Lesson and playback flows. Update any selectors that referred to the old separate actions.

**Acceptance Criteria:**
- Given a regular Lesson with Physical mode off, when the learner taps the caption, then the switch turns on and the existing Confirm your cube card appears. Tapping the caption again turns Physical mode off.
- Given Solution Playback, when the learner taps the mode caption from either state, then it toggles and moves between the existing virtual and physical controls.
- Given either screen in dark theme, when the switch is off, then the outline and thumb use a theme color with enough contrast against the switch track and surrounding surface.
- Given either switch, when an accessibility service inspects it, then the caption, switch role, and on/off state are available as one action.

## Spec Change Log

## Review Triage Log

### 2026-11-23 — Review pass

- Verdicts: 12 findings — high 0, medium 6, low 4, false 1, maybe-false 1.
- Findings:
  - `[medium]` `[patch]` Playback recreated the focused switch after each toggle. Moved it outside the mode-keyed controls and checked a stable semantics node ID.
  - `[medium]` `[patch]` Resetting the controls' scroll position could move the switch below the fold after a tap. Kept the switch outside the scroll pane and tested consecutive taps in a compact view at 200% type.
  - `[maybe-false]` `[reject]` An unweighted caption could squeeze the switch at sufficiently narrow widths. The current label and switch fit the 320 dp layout at 200% type. A failing supported width or shipped localization would substantiate the concern.
  - `[low]` `[reject]` There is no light-theme screenshot assertion for this switch. The light outline and thumb have at least 5.09:1 contrast against all three light surfaces, and the reported issue and render regressions use dark theme.
  - `[low]` `[patch]` Caption and semantic action tests could miss a partial touch target. Added rendered switch-area and outer-padding taps plus a 48 dp touch-target check.
  - `[medium]` `[patch]` Merged semantics could hide a missing Android accessibility label or duplicate action. Labelled the actionable row directly and asserted the platform node and the single switch action.
  - `[low]` `[reject]` Screenshot sample geometry may need updating after a Material component redesign. Both cases exercise the pinned Material build and the sample offsets hit the relevant parts today.
  - `[medium]` `[patch]` Changing modes replaced the accessibility and keyboard focus node. The switch now retains the same node across both transitions.
  - `[medium]` `[patch]` Compose 1.12.1 queries the unmerged node for the action, label, role description, and check state. Clearing descendant semantics and applying the label to the same row exposes all four together.
  - `[false]` `[reject]` The shared playback change also reaches Algorithm Practice and renders an off switch with the existing playback button. The issue calls for a visible off-state on both screens, and Algorithm Practice already uses the same control and mode transitions.
  - `[medium]` `[patch]` The initial label-tap regressions mounted the real screens directly. Existing normal Learn and Solve navigation tests now tap the rendered caption too. Exact TalkBack speech and physical display remain outside the automated tests.
  - `[low]` `[patch]` An unlabeled action could have evaded the former caption-based count, particularly while checked. The compact playback regression checks the unmerged switch subtree in both states.

## Verification

**Commands:**
- `./gradlew :app:testDebugUnitTest` — app unit and UI regressions pass.
- `./gradlew spotlessCheck build :app:compileDebugAndroidTestKotlin` — repository gates and Android test compilation pass.

## Auto Run Result

The whole Physical mode row toggles from Lessons and Solution Playback, including its caption and track. The off thumb and outline use the secondary ink color. The playback switch now stays visible and retains its accessibility node when the mode changes.

- `PhysicalModeSwitch.kt` provides the shared 48 dp switch, one platform accessibility action, and the unchecked colors.
- `LessonScreen.kt` uses the control through the existing Lesson mode gate and confirmation flow.
- `SolutionPlayback.kt` keeps the switch below and outside the mode-specific scrolling controls. The existing “I'm following on my cube” entry button remains available in virtual playback.
- The Lesson and playback tests exercise actual touch, screenshot contrast, a stable platform accessibility node, and the existing app navigation routes.

Four review layers found three medium patch groups and one low patch group. No findings were deferred and the rejected findings have evidence in the review log. A follow-up review is recommended because the platform-node and compact-render checks use Robolectric, so physical-device TalkBack focus retention is still unverified.

`./gradlew :app:testDebugUnitTest spotlessCheck build :app:compileDebugAndroidTestKotlin --console=plain --max-workers=4` passed with JDK 25 and the configured Android SDK. All 1,370 app tests and 1,561 core tests passed with zero skips. Lint, coverage, release and benchmark assembly, and Android test compilation passed. No physical-device TalkBack test was run.
