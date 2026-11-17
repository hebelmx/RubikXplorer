---
title: 'epic-1-retro-item-55: retarget Net2DFrameTimingBenchmark to the Solution playback net with a minimum-frame-count check'
type: 'chore'
created: '2026-11-17'
status: 'done'
baseline_revision: 'f30fbbc7684cdb18c86a0a639606ee0fee4bd087'
review_loop_iteration: 0
followup_review_recommended: false
context:
  - '{project-root}/AGENTS.md'
warnings: [oversized]
deferred:
  - summary: >-
      No automated check proves MinFrameCountMetric rejects a capture below MIN_FRAMES (99 vs 100 frames).
    evidence: |-
      The :macrobenchmark module is com.android.test with no JVM unit-test source set; a trace-backed check needs a device, which the intent excludes. Settle in the #106 device session (for example, temporarily run with a too-high floor and confirm the failure message).
    location: >-
      macrobenchmark/src/main/kotlin/dev/cubentia/macrobenchmark/MinFrameCountMetric.kt:31
    severity: medium
  - summary: >-
      Nothing in CI proves UiAutomator resolves By.res("net") on Solution Playback (testTagsAsResourceId).
    evidence: |-
      Compose tests find the tag with onNodeWithTag, which does not need resource-id export; only the connected benchmark exercises By.res. Settle in the #106 device session: requireNet must pass.
    location: >-
      app/src/main/kotlin/dev/cubentia/app/ui/solve/SolvePlaybackScreen.kt:61
    severity: medium
---

<intent-contract>

## Intent

**Problem:** `Net2DFrameTimingBenchmark` opens the Solve tab and taps Move keys, but that screen is now the GL `Cube3DRenderer`, so the benchmark measures no 2D net and "passed" with `frameCount = 1` (epic-1 retro F2). Nothing fails a run that captured no frames.

**Approach:** Drive the app from launch to Solution Playback: Solve tab, *Enter colors*, enter two flipped edges, Accept, wait for the solver. Then measure `FrameTimingMetric` while the transport plays a fixed number of Moves on the `Net2DRenderer` net. A companion trace metric fails the iteration when fewer than a named minimum of UI frames were captured. The test also fails when the playback counter did not advance.

## Boundaries & Constraints

**Always:** use UiAutomator with existing content descriptions/text (transport `Play`/`Pause`/`Restart`, editor sticker/swatch/face-tab descriptions, `Enter colors`, `Accept`). Expose existing test tags to UiAutomator only on the Solve Playback screen (`testTagsAsResourceId`) so the benchmark can see the existing `net` tag; add no new test tag. Every failure path uses `checkNotNull`/`check` with a message that names the missing screen or element. Target package `com.exxerpro.cubentia`.

**Never:** run anything on a device; close or edit issue #106; touch `sprint-status.yaml`, `_bmad/`, `.claude/skills/`, or planning artifacts; change `Cube3DFrameBenchmark`/`GlFrameMetric` behavior; add Learner text; change playback or editor behavior.

</intent-contract>

## Code Map

- `macrobenchmark/src/main/kotlin/dev/cubentia/macrobenchmark/Net2DFrameTimingBenchmark.kt` -- target; today `openSolveTab()` + R U R' U' keys. Rewrite.
- `macrobenchmark/src/main/kotlin/dev/cubentia/macrobenchmark/GlFrameMetric.kt:36-200` -- pattern for a `TraceMetric` (`@OptIn(ExperimentalMetricApi::class)`, `traceSession.query`, `check` with messages). Reuse its SQL shape; do not modify the file.
- `macrobenchmark/.../Cube3DFrameBenchmark.kt:117-124` -- the Welcome/Solve-tab navigation helper (`SOLVE_OR_WELCOME`).
- benchmark-macro 1.5.0: `FrameTimingMetric.getMeasurements` is `internal`, so its frame count cannot be read by the test. `FrameTimingQuery` counts UI frames as `Choreographer#doFrame%` slices, excluding `- resynced to`.
- `app/src/main/kotlin/dev/cubentia/app/ui/solve/SolveScreen.kt:144-148` -- `Enter colors` button (text from `btn.solve.enter-colors`).
- `app/src/main/kotlin/dev/cubentia/app/ui/editor/ManualCubeEditor.kt` -- starts SOLVED; sticker desc `"<Color> sticker, <face> face, position <n>"` (`editor_sticker`, faces lower-case `up/front/back`); swatch desc = color name (`White`, `Green`, `Blue`); one-per-face mode shows face tabs with desc `"<Face> face"` (`Up face`, `Front face`, `Back face`); `Accept` in a vertical scroll and enabled only when valid.
- `app/src/main/kotlin/dev/cubentia/app/ui/shell/AppShell.kt:278` -- a `SOLVE_PLAYBACK` session shows `SolvePlaybackScreen` full screen (no tabs), also after relaunch.
- `app/src/main/kotlin/dev/cubentia/app/ui/solve/SolvePlaybackScreen.kt:53` -- root `Column` tagged `solvePlayback:screen`. Add `testTagsAsResourceId = true` semantics here.
- `app/src/main/kotlin/dev/cubentia/app/render/Net2DRenderer.kt:161` -- existing `testTag("net")`.
- `app/src/main/kotlin/dev/cubentia/app/ui/playback/PlaybackTransport.kt:231-260,308` -- transport content descriptions `Restart`/`Step back`/`Play`/`Pause`/`Step forward`/`Skip to end`; counter text `"%d / %d"`. One Move per 600 ms at NORMAL (`PlaybackController.STEP_MILLIS`). Restart = rewind and play.
- `docs/benchmarks.md:121-187,652-656` -- the 2D net section and the Out-of-scope bullet to update.
- `app/src/test/kotlin/dev/cubentia/app/ui/solve/SolutionPlaybackTest.kt` -- the touched screen's unit tests. They must still pass.

## Tasks & Acceptance

**Execution:**
- `app/src/main/kotlin/dev/cubentia/app/ui/solve/SolvePlaybackScreen.kt` -- add `.semantics { testTagsAsResourceId = true }` to the root Column, with a comment that it exposes the existing tags to UiAutomator for the macrobenchmark -- this makes the `net` tag visible without adding a tag.
- `macrobenchmark/.../MinFrameCountMetric.kt` (new) -- a `TraceMetric` that counts the target process's main-thread `Choreographer#doFrame%` slices (excluding resynced). It reports `uiFrameCount` and throws `IllegalStateException` when the count is below its `minFrames` parameter -- this is assertion (2).
- `macrobenchmark/.../Net2DFrameTimingBenchmark.kt` -- rewrite: setup navigates to Solution Playback (see Design Notes), asserts the `net` resource, and leaves playback paused with at least `PLAYED_MOVES` remaining. Measure taps `Play`, sleeps `PLAYED_MOVES × STEP_MS`, reads the counter, taps `Pause` if still playing, and `check`s the advance. Metrics: `FrameTimingMetric()` and `MinFrameCountMetric(MIN_FRAMES)`. Update the KDoc.
- `docs/benchmarks.md` -- rewrite the 2D net method paragraph for the new flow and frame floor. Keep the Story 1.11 history. Add that the device measurement is pending under #106, and update the Out-of-scope bullet.

**Acceptance Criteria:**
- Given the macrobenchmark module, when `./gradlew :macrobenchmark:assemble` runs, then it succeeds.
- Given the app change, when `./gradlew :app:testDebugUnitTest --tests 'dev.cubentia.app.ui.solve.*'` and `spotlessCheck` run, then they pass.
- Given the benchmark source, when read, then `MIN_FRAMES` is a named constant with a comment justifying its value against the measured window, and the navigation selects the 2D net explicitly if a view toggle exists.
- Given `docs/benchmarks.md`, when read, then it states the retarget and that the device measurement is pending (#106).

## Spec Change Log

## Review Triage Log

### 2026-11-17 — Review pass
- verdicts: 18 findings — high 0, medium 3, low 11, false 4, maybe-false 0
- findings:
  - `[low]` `[patch]` (blind) Face-tab and sticker lookups are not scroll-aware on short windows — now use the scroll-aware `editorObject` for both.
  - `[low]` `[reject]` (blind) Restored playback of a different cube can be measured — net frame cost does not depend on which Moves play; enforcing the fixture adds session handling; docs already say a warm relaunch can restore playback.
  - `[low]` `[patch]` (blind) Restored physical-mode playback has no transport, so setup times out — failure message now names this case and the remedy; the benchmark never enters physical mode itself.
  - `[low]` `[patch]` (blind) Workload varies: start 0 or 1, about 8–9 Moves per window — KDoc now says "plays for 4.8 s (about eight Moves; Play shows the first at once)"; frame timing per window stays comparable.
  - `[medium]` `[patch]` (blind) With Reduce Motion (animator scale 0) the net snaps, yet frames and counter would pass — setup now reads `animator_duration_scale` and fails with a clear message when it is 0; docs note this.
  - `[low]` `[patch]` (edge) Sticker lookup not scroll-aware — same root cause and fix as the first blind finding.
  - `[low]` `[patch]` (edge) Physical-mode restore times out — same as the blind finding; message patched.
  - `[low]` `[reject]` (edge) Restart with exactly 8 total Moves leaves 7 — only reachable with a foreign restored session; it fails loudly with the counter in the message.
  - `[false]` `[reject]` (edge) Editor reopens seeded with the accepted fixture — `accepted` is in-memory ViewModel state; Macrobenchmark kills the process before measuring, and later iterations restore playback without reopening the editor.
  - `[low]` `[patch]` (edge) More Moves than claimed — same as the blind workload finding; KDoc fixed.
  - `[medium]` `[defer]` (verification-gap) No check that a capture below the floor fails — the module has no JVM test set and the intent excludes device runs; deferred to the #106 device session.
  - `[medium]` `[defer]` (verification-gap) No CI check that UiAutomator sees `net` by resource id — device-only; deferred to the #106 session.
  - `[false]` `[reject]` (intent) Frame floor counts doFrame slices, not FrameTimingMetric frames — FrameTimingQuery builds its frames from these same UI-thread `Choreographer#doFrame` slices, so the counts agree.
  - `[false]` `[reject]` (intent) STEP_MS 600 is assumed — `PlaybackController.STEP_MILLIS = 600` (core/domain/.../PlaybackController.kt:122), and NORMAL factor is 1.0.
  - `[low]` `[reject]` (intent) testTagsAsResourceId reaches release — it only adds view-id names to accessibility nodes of one screen; no Learner impact; it is the enablement the spec chose.
  - `[low]` `[reject]` (intent) Setup depends on Manual Cube Editor descriptions — it is the only main-app route to Solution Playback without a camera; failures name the missing element.
  - `[low]` `[reject]` (intent) Toggle regex is a guess — it is used only when the net is missing, and the net check that follows still fails loudly; #175 has no control yet to target.
  - `[false]` `[reject]` (intent) Docs rewrite the Story 1.11 record — the device table, raw numbers and waiver remain; only the "follow-up" wording was updated to say the retarget is done.

## Design Notes

Cube entry: two flipped edges is a valid, unsolved cube with only 4 sticker edits from SOLVED: U8→Green, F2→White (UF edge) and U2→Blue, B2→White (UB edge). For each edit: tap the face tab if present (one-per-face mode), tap the sticker by its current description, then tap the swatch. Then scroll to `Accept`, wait until it is enabled, tap it, and wait up to `SOLVE_TIMEOUT_MS` (60 s; first-use preparation measured about 10.5 s) for `Play`.

Navigation: first wait for any of `Play`/`Pause` desc, *I'm new*, or the `Solve` tab. If no `net` is found, click a 2D/net view toggle if one is present (#175, `By.desc`/`By.text` matching `2D|Net`), then require `net`.

Frame floor: `PLAYED_MOVES = 8` gives a 4.8 s window. `PlaybackFrameLoop` requests a frame on every vsync while playing, so even 30 Hz yields at least 144 frames. Taps on a static screen render only ripple frames, well under 100. Set `MIN_FRAMES = 100`.

### Device behaviors (checked only in the device session under #106; no device here)

| Scenario | Input / State | Expected Output / Behavior | Error Handling |
|----------|--------------|---------------------------|----------------|
| Fresh install | Welcome screen | taps *I'm new*, Solve tab, enters cube, reaches playback | none |
| Session restored | WARM relaunch shows Solve Playback directly | skips navigation; pauses if playing; rewinds if fewer than `PLAYED_MOVES` remain | none |
| Wrong screen / no net | `net` resource not found (after trying a 2D view toggle if one exists) | iteration fails | `"Solution Playback shows no 2D net …"` |
| Empty capture | trace has `< MIN_FRAMES` target `Choreographer#doFrame` slices | iteration fails | message names count and minimum |
| Playback stalled | counter position did not advance by `PLAYED_MOVES - 1` | iteration fails | message with before/after counter |
| Solution too short | `size - position < PLAYED_MOVES` after rewind | fails | message with the counter |

## Verification

**Commands:**
- `./gradlew :macrobenchmark:assemble` -- expected: BUILD SUCCESSFUL
- `./gradlew :app:testDebugUnitTest --tests 'dev.cubentia.app.ui.solve.*'` -- expected: pass
- `./gradlew spotlessCheck` -- expected: pass (`spotlessApply` to fix)

## Auto Run Result

Status: done

- **Change:** `Net2DFrameTimingBenchmark` now goes from launch to Solution Playback (Welcome → Solve → *Enter colors* → two flipped edges → Accept, or a restored playback). It requires the 2D net (`By.res("net")`, with a 2D/net toggle tried first if present), checks animations are on, plays about 8 Moves (4.8 s), and checks that the counter advanced. A new `MinFrameCountMetric` fails any iteration with fewer than `MIN_FRAMES = 100` UI frames.
- **Files:**
  - `macrobenchmark/.../Net2DFrameTimingBenchmark.kt`: the retargeted flow and assertions.
  - `macrobenchmark/.../MinFrameCountMetric.kt` (new): the frame floor.
  - `app/.../solve/SolvePlaybackScreen.kt`: `testTagsAsResourceId` exposes the existing tags to UiAutomator.
  - `docs/benchmarks.md`: the new method, the frame floor, and that the device measurement is pending under #106.
- **Review:** 7 patches applied (1 medium, 6 low; several share a root cause), 2 deferred (medium, device-only), 9 rejected with reasons in the triage log.
- **Follow-up review recommended:** false. Only 1 medium was patched and no high.
- **Verification:**
  - `./gradlew :macrobenchmark:assemble spotlessCheck :app:testDebugUnitTest --tests 'dev.cubentia.app.ui.solve.*'` passed (SolutionPlaybackTest 29/29, SolveFlowViewModelTest 12/12), before and after the patches.
- **Residual risk:** the navigation and the frame capture are unverified on a device. The Manual Cube Editor flow, the `net` resource id and the frame floor are first exercised in the #106 device session.
