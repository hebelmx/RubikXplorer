---
title: 'gh-201 Solution Playback shows the full solution length up front'
type: 'feature'
created: '2026-11-19'
status: 'done'
baseline_revision: '2294dab8a6341cb8d454c2e8152bf60600587b6c'
review_loop_iteration: 0
followup_review_recommended: false
context: []
warnings: []
deferred: []
---

<intent-contract>

## Intent

**Problem:** GitHub issue #201. On Solution Playback the Move strip shows about 7 Moves and the `0 / 13` counter is small (`data`, `ink-secondary`), so a 13-move Solution looks like 7 moves until the Learner starts stepping.

**Approach:** (a) Show "<N> moves" (localized ICU plural from `en.json`) in the 56dp top row of `SolvePlaybackScreen`, beside the Close button, as that row's only text. (b) Render the position counter on Solution Playback only in a larger, higher-contrast existing style (`RubikTheme.textStyles.notation`, `tokens.inkPrimary`), through an opt-in parameter on `PlaybackTransport` whose default keeps Demonstrations and Algorithm detail exactly as they are.

## Boundaries & Constraints

**Always:** Learner text only in `core/domain/src/main/resources/content/strings/en.json`, resolved via `strings.resolve(ContentText(key, mapOf("count" to n)))`. Only existing type and colour tokens. The label reads the live Solution size, so it updates after a re-solve. Layout holds in virtual and Physical-cube playback, light and dark themes, up to 200% font scale with no truncation or overlap.

**Never:** No plan title or any other new Learner copy. No overflow arrow, chevron, or gradient fade (DESIGN.md:175). No change to how `PlaybackTransport` looks or behaves when the new parameter is left at its default. No new string in `strings.xml`. No change to Move strip, transport buttons, or session logic.

## I/O & Edge-Case Matrix

| Scenario | Input / State | Expected Output / Behavior | Error Handling |
|----------|--------------|---------------------------|----------------|
| Long solution | 13-move Solution, position 0 | Top row shows "13 moves"; counter `0 / 13` in prominent style | No error expected |
| Singular | 1-move Solution | Top row shows "1 move" | No error expected |
| Physical mode | Follow on my cube | "<N> moves" still in top row; physical counter in prominent style | No error expected |
| Empty solution | 0 Moves (already solved) | "0 moves" (ICU `other`); existing solved notice unchanged | No error expected |
| 200% font scale | fontScale 2.0, phone width | Label and Close do not overlap; label not truncated | No error expected |

</intent-contract>

## Code Map

- `app/src/main/kotlin/dev/cubentia/app/ui/solve/SolvePlaybackScreen.kt:62-66` -- 56dp top `Row` holding only the Close `IconButton` (`solvePlayback:close`); add the label here. `playback.size` is the Solution length.
- `app/src/main/kotlin/dev/cubentia/app/ui/playback/PlaybackTransport.kt:242-316` -- shared transport; counter `Text` (`playback:counter`, `styles.data`, `tokens.inkSecondary`) at ~310-316. Callers: `DemonstrationPlayer.kt:132`, `AlgorithmDetailScreen.kt:134`, `SolutionPlayback.kt:455`.
- `app/src/main/kotlin/dev/cubentia/app/ui/solve/SolutionPlayback.kt:455` (virtual transport call) and `:504-509` (`PhysicalPlaybackControls` counter, Solution-Playback-only) -- both counters to make prominent.
- `app/src/main/kotlin/dev/cubentia/app/ui/theme/Type.kt:53-65` -- `RubikTextStyles.notation` (Mono 500, Title Medium) vs `data` (Mono 400, Body Medium, tnum).
- `core/domain/src/main/resources/content/strings/en.json:449-454` -- `msg.playback.*` keys; plural precedent `msg.recovery.plan` (line 147).
- `app/src/test/kotlin/dev/cubentia/app/ui/solve/SolutionPlaybackTest.kt` -- Robolectric Compose tests; `SolvePlaybackScreen(session(...), strings, {}, {}, {})` usage at ~781; `GetTextLayoutResult` style-reading precedent in `transportColorsReflectEnabledStateAtStart`.
- `app/src/test/kotlin/dev/cubentia/app/ui/playback/PlaybackTransportScrubTest.kt` -- default-transport test home.

## Tasks & Acceptance

**Execution:**
- `core/domain/src/main/resources/content/strings/en.json` -- add `"msg.playback.solution-length": "{count, plural, one {# move} other {# moves}}"` next to the other `msg.playback.*` keys -- localized plural.
- `app/src/main/kotlin/dev/cubentia/app/ui/playback/PlaybackTransport.kt` -- add opt-in `prominentCounter: Boolean = false`; when true the counter uses `styles.notation` with `fontFeatureSettings = "tnum"` and `tokens.inkPrimary`; default path byte-for-byte the current style -- Solution Playback only.
- `app/src/main/kotlin/dev/cubentia/app/ui/solve/SolutionPlayback.kt` -- pass `prominentCounter = true` at line 455; apply the same prominent style to the `PhysicalPlaybackControls` counter -- both modes.
- `app/src/main/kotlin/dev/cubentia/app/ui/solve/SolvePlaybackScreen.kt` -- in the top row, after Close, a vertically centred `Text` (tag `solvePlayback:length`, `MaterialTheme.typography.titleMedium`, `tokens.inkPrimary`, weight 1f, wraps rather than truncates) showing the resolved plural for `playback.size` -- the header.
- `app/src/test/kotlin/dev/cubentia/app/ui/solve/SolutionPlaybackTest.kt` -- tests: 13-move Solution at position 0 shows "13 moves" and counter `0 / 13`; 1-move shows "1 move"; counter text style is `notation` size with `inkPrimary` in virtual and physical mode; label present in physical mode; at fontScale 2.0 the label's bounds do not overlap Close and its text has no visual overflow; dark theme renders the label in `DarkTokens.inkPrimary`.
- `app/src/test/kotlin/dev/cubentia/app/ui/playback/PlaybackTransportScrubTest.kt` -- test default `PlaybackTransport` counter keeps `data` style and `inkSecondary` -- shared transport unchanged for Demonstrations and Algorithm detail.

**Acceptance Criteria:**
- Given a Solution longer than the visible Move strip at position 0, when Solution Playback opens, then "<N> moves" is shown in the top row beside Close before any step.
- Given Solution Playback, when the counter renders in virtual or Physical-cube playback, then it uses the `notation` size and `ink-primary`, larger and higher-contrast than before.
- Given Demonstrations or Algorithm detail, when their transport renders, then the counter keeps `data` and `ink-secondary` and no length label appears.
- Given light or dark theme and font scale up to 200%, when Solution Playback renders, then the label and counter are neither truncated nor overlapping.

## Spec Change Log

## Review Triage Log

### 2026-11-19 — Review pass
- verdicts: 12 findings — high 0, medium 0, low 11, false 1, maybe-false 0
- findings:
  - `[low]` `[patch]` (blind) 200% layout check used a 3-move `0 / 3` counter; no multi-digit counter beside a Move picture — large-font check now uses a 13-move Solution with Move pictures on, steps to `10 / 13` in virtual mode, then checks `0 / 13` in physical mode.
  - `[low]` `[patch]` (blind) line-width check can pass while a parent clips the text — added `assertWithin`: unclipped bounds of label, counter and Move label lie inside the screen; counter must not overlap the Move label.
  - `[low]` `[patch]` (blind) dark theme not checked at 200% nor for the counter colour — large-font check now also runs as a dark-theme test asserting `DarkTokens.inkPrimary` for label and counter in both modes.
  - `[low]` `[reject]` (blind) no test of a physical-mode re-solve replacing the Solution — label reads `playback.size` from the live session, already shown updating on session replacement; a re-solve flow test adds a whole scenario for an unlikely defect.
  - `[low]` `[reject]` (blind) spec's matrix row calls an empty Solution "already solved" — the fix is to edit this build's spec; the code shows "0 moves" and leaves the existing notice untouched.
  - `[low]` `[patch]` (edge) clipped label/counter could pass the 200% test — same root cause as the clipping row above; fixed by `assertWithin`.
  - `[low]` `[patch]` (verification-gap) header could count down (`size - position`) and still pass — length test now steps forward once and asserts `1 / 13` with "13 moves".
  - `[low]` `[patch]` (verification-gap) dark-theme counter colour unverified — same root cause as the dark-theme row above; fixed by the dark large-font test.
  - `[low]` `[reject]` (intent-alignment) test never shows the strip is shorter than the Solution — the label is unconditional, so strip width cannot change its outcome; no bad outcome to catch.
  - `[low]` `[patch]` (intent-alignment) "Demonstrations and Algorithm detail unchanged" tested only on the bare transport — added `assertDefaultCounterStyle()` (`data`, `ink-secondary`) to `DemonstrationPlayerTest` and `AlgorithmDetailScreenTest`.
  - `[low]` `[patch]` (intent-alignment) layout matrix only partly covered (dark at 200%) — same root cause as the dark-theme row above; fixed by the dark large-font test.
  - `[false]` `[reject]` (intent-alignment) new tests are Robolectric, not instrumented — Robolectric Compose tests are Compose UI tests, and AGENTS.md prefers JVM tests over instrumented ones.

## Verification

**Commands:**
- `export JAVA_HOME=/usr/lib/jvm/java-25-openjdk-amd64 ANDROID_HOME=$HOME/Android/Sdk && ./gradlew :app:testDebugUnitTest --tests '*SolutionPlaybackTest' --tests '*PlaybackTransportScrubTest'` -- expected: pass
- `./gradlew spotlessCheck build` -- expected: success (content validation, lint, Kover, gates)

## Auto Run Result

Status: done

**Summary:** Solution Playback now shows "<N> moves" (ICU plural `msg.playback.solution-length`) in the 56dp top row beside Close, and both its counters (virtual transport and Physical-cube controls) use `notation` with tabular figures in `ink-primary`. `PlaybackTransport` gained `prominentCounter = false`, so Demonstrations and Algorithm detail are unchanged.

**Files changed:**
- `core/domain/src/main/resources/content/strings/en.json` -- new plural key `msg.playback.solution-length`.
- `app/src/main/kotlin/dev/cubentia/app/ui/playback/PlaybackTransport.kt` -- opt-in `prominentCounter` parameter.
- `app/src/main/kotlin/dev/cubentia/app/ui/solve/SolutionPlayback.kt` -- prominent counter in virtual and physical playback.
- `app/src/main/kotlin/dev/cubentia/app/ui/solve/SolvePlaybackScreen.kt` -- "<N> moves" header text.
- `app/src/test/kotlin/dev/cubentia/app/ui/solve/SolutionPlaybackTest.kt` -- label (13/1/0, stays at full length after a step), prominent counter, 200% layout in light and dark, both modes.
- `app/src/test/kotlin/dev/cubentia/app/ui/playback/PlaybackTransportScrubTest.kt` -- default counter style unchanged.
- `app/src/test/kotlin/dev/cubentia/app/ui/lesson/DemonstrationPlayerTest.kt`, `app/src/test/kotlin/dev/cubentia/app/ui/library/AlgorithmDetailScreenTest.kt` -- counter keeps `data` / `ink-secondary`.

**Review findings:** 12 findings; 8 patched (all low, grouped into 5 fixes); 0 deferred; 4 rejected (physical re-solve test: unlikely defect, costly fix; spec wording: fix would edit the spec; strip-width demonstration: no outcome to catch; Robolectric vs instrumented: false, project prefers JVM tests).

**Follow-up review recommended:** false -- patched 0 high and 0 medium (8 low).

**Verification:** `./gradlew :app:testDebugUnitTest` for SolutionPlaybackTest (39), PlaybackTransportScrubTest (3), DemonstrationPlayerTest (22), AlgorithmDetailScreenTest (18) -- all pass, 0 skipped. `./gradlew spotlessCheck build` -- exit 0. The previous run's halt note was deleted; its decisions (ICU plural, opt-in counter style) are carried in this spec.

**Residual risks:** Layout checked in Robolectric at 200% font scale only, not on a device. An empty Solution shows "0 moves".
