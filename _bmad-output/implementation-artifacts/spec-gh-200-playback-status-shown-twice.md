---
title: 'GH-200 Solution Playback: certainty shown once, no frame label over the net'
type: 'bugfix'
created: '2026-11-19'
status: 'done'
baseline_revision: 'f5bec70f6e76fb1d51bd5967372d772b4e06a0a7'
review_loop_iteration: 0
followup_review_recommended: false
context:
  - '{project-root}/_bmad-output/planning-artifacts/sprint-change-proposal-2026-11-19-playback-status-label.md'
warnings: []
deferred: []
---

<intent-contract>

## Intent

**Problem:** In Physical-cube Mode, Solution Playback shows Certainty twice: a `FrameLabel` (EXPECTED / ✓ VERIFIED) in the stage's top-left overlay, which overlaps the white face of the 2D net on a portrait phone, and the certainty chip below the stage (GitHub issue #200, Galaxy S24 FE).

**Approach:** Per the approved SCP (2026-11-19), Solution Playback, virtual or physical, renders no frame label in either view; the certainty chip below the stage is the only Certainty indicator, and the View rotated badge becomes the first (only) item in the top-left overlay.

## Boundaries & Constraints

**Always:** Keep the certainty chip, Quick Check retry text, SEEN strip and Quick Check button exactly as they are. Keep the badge's top-left overlay traversal semantics (`isTraversalGroup`, `traversalIndex = -1f`) so TalkBack reaches the badge before the cube. Certainty still comes only from session state (`tutor/Assess`).

**Never:** Do not change `FrameLabel`, `LessonScreen`, `DemonstrationPlayer`, `FreeCubeScreen`, `AlgorithmDetailScreen` or their tests. Do not edit planning artifacts. Do not add new strings, flags or `SessionEvent`s. No other layout changes to Solution Playback.

## I/O & Edge-Case Matrix

| Scenario | Input / State | Expected Output / Behavior | Error Handling |
|----------|--------------|---------------------------|----------------|
| Physical, 2D net, no evidence | Follow on my cube, NET_2D | No `stage:frame` node; no chip | No error expected |
| Physical, matching Quick Check | Quick Check observed equal to Expected | No `stage:frame` in 2D or 3D; `certainty:label` = "Verified" in both | No error expected |
| Physical, after next Done | Verified, then Done | Still no `stage:frame` | No error expected |
| Physical, 3D, view rotated | Rotate 3D orbit | `stage:viewRotated` exists, is first in overlay (traversal badge → `cube3d`), no `stage:frame` | No error expected |
| Virtual, 3D, view rotated | Unchanged behavior | Badge first, no frame label | No error expected |

</intent-contract>

## Code Map

- `app/src/main/kotlin/dev/cubentia/app/ui/solve/SolutionPlayback.kt:385-408` -- stage overlay: `if (state.isPhysical || cube != null) FlowRow { if (state.isPhysical) FrameLabel(...); if (cube != null) ViewRotatedBadge(cube, strings) }`. This is the only change site. After the change only the badge remains, so the guard becomes `cube != null`.
- `SolutionPlayback.kt:51` -- `import dev.cubentia.app.ui.lesson.FrameLabel` becomes unused; remove. `SyncState` (line 69) and `Certainty` (line 74) imports: `SyncState` is still used at ~425; check `Certainty` and remove only if unused. `FlowRow` import stays if FlowRow is kept.
- `SolutionPlayback.kt:412-418` -- certainty chip below the stage (`CertaintyChip`, tag `certainty:label`); read-only.
- `app/src/main/kotlin/dev/cubentia/app/render/ViewRotatedBadge.kt` -- badge, tag `stage:viewRotated`, renders nothing at home orbit; read-only.
- `app/src/main/kotlin/dev/cubentia/app/ui/lesson/LessonScreen.kt:1536` -- `FrameLabel`, tag `stage:frame`; read-only (Lesson users unchanged).
- `app/src/test/kotlin/dev/cubentia/app/ui/solve/SolutionPlaybackTest.kt` -- Robolectric tests. Assertions that now contradict the SCP: lines ~185-194 (physical 3D badge after frame label), ~227, ~448-449, ~493-494 (`stage:frame` EXPECTED), ~889-899 (`stage:frame` VERIFIED/EXPECTED). Helpers: `show()`, `click()`, `assertDirectTraversal(first, second)`, `netPreferences()`.
- `app/src/androidTest/kotlin/dev/cubentia/app/render/Cube3DRenderSyncTest.kt:274-276` -- `assertPhysicalEvidence()` on Solution Playback asserts `stage:frame` EXPECTED; must become `assertDoesNotExist()` (instrumented; compiled by `build`, not run).

## Tasks & Acceptance

**Execution:**
- `app/src/main/kotlin/dev/cubentia/app/ui/solve/SolutionPlayback.kt` -- remove the `FrameLabel` branch from the stage overlay; render the overlay only when `cube != null` with the badge as its first item, keeping the traversal semantics; drop now-unused imports -- SCP decisions 1, 2, 4.
- `app/src/test/kotlin/dev/cubentia/app/ui/solve/SolutionPlaybackTest.kt` -- replace every Solution Playback `stage:frame` text/border assertion with `assertDoesNotExist()`; in the physical 3D rotated case assert no frame label, badge exists and `assertDirectTraversal("stage:viewRotated", "cube3d")`, and the badge's left bound equals the `cube3d` stage's left bound (within 1 px), proving nothing precedes it in the overlay; in `physicalPlaybackOffersQuickCheckAtEveryStepAndShowsEvidence` assert no `stage:frame` in 2D and 3D while `certainty:label` = "Verified", and still none after Done; add a physical 2D net test asserting no `stage:frame` at start -- done-when item 5.
- `app/src/androidTest/kotlin/dev/cubentia/app/render/Cube3DRenderSyncTest.kt` -- in `assertPhysicalEvidence()` assert `stage:frame` does not exist instead of EXPECTED -- keep the instrumented suite consistent.

**Acceptance Criteria:**
- Given Physical-cube Mode on Solution Playback, when the 2D net or 3D view is shown at any step (before evidence, after a matching Quick Check, after the next Done), then no node tagged `stage:frame` exists.
- Given a matching Quick Check on Solution Playback, when either view is shown, then the certainty chip below the stage reads "Verified" and is the only Certainty indicator.
- Given Solution Playback in 3D with a rotated view (virtual or physical), when the overlay renders, then `stage:viewRotated` is the first item in the top-left overlay and TalkBack goes from it directly to `cube3d`.
- Given a portrait phone width, when Solution Playback shows the net, then no stage overlay node overlaps the net (the existing `netFitsPhone` / `netFitsShortPhone` tests still pass and no overlay exists over the net).
- Given Lesson, Demonstration, Free Cube and Algorithm Detail screens, when their existing tests run, then they pass unchanged.

## Verification

**Commands:**
- `export JAVA_HOME=/usr/lib/jvm/java-25-openjdk-amd64 ANDROID_HOME=$HOME/Android/Sdk; ./gradlew spotlessApply :app:testDebugUnitTest --tests 'dev.cubentia.app.ui.solve.*' --tests 'dev.cubentia.app.ui.lesson.FrameLabelTest'` -- expected: all pass.
- `./gradlew spotlessCheck build` -- expected: BUILD SUCCESSFUL (compiles androidTest too).

## Spec Change Log

## Review Triage Log

### 2026-11-19 — Review pass
- verdicts: 11 findings — high 0, medium 0, low 3, false 8, maybe-false 0
- findings:
  - `[false]` `[reject]` (blind-hunter) Spec omits the Galaxy S24 FE portrait device check — the fix is to edit this build's spec; a device session is outside an unattended run and is listed under residual risks.
  - `[false]` `[reject]` (blind-hunter) `netFitsPhone`/`netFitsShortPhone` run in Virtual-cube Mode, so the overlay bug is not covered — in Physical-cube Mode the net now has no overlay node at all (`SolutionPlayback.kt` overlay guarded by `cube != null`); `physicalNetStartsWithoutAStageOverlayOrCertaintyChip` and the Quick Check test assert no `stage:frame`/`stage:viewRotated` on the net, so nothing can overlap it.
  - `[low]` `[patch]` (blind-hunter) The Verified chip is not asserted to clear after the next Done — reducer clears `quickCheck` when Expected changes (`Reduce.kt:58`), but the test did not prove it; added `certainty:label` assertDoesNotExist after Done in 3D and 2D.
  - `[false]` `[reject]` (blind-hunter) `build` may not compile the changed androidTest — `:app:compileDebugAndroidTestKotlin` was run explicitly and succeeded.
  - `[low]` `[patch]` (blind-hunter) Rotated badge top edge not checked — grouped with the verification-gap row; added `assertEquals(stageBounds.top, badgeBounds.top, 1f)`.
  - `[low]` `[patch]` (verification-gap) Badge top alignment not verified in physical 3D — same root cause and fix as the row above.
  - `[false]` `[reject]` (intent-alignment) Overlap (done-when 3) never measured in physical mode — same refutation as the netFits row: no overlay node exists on the physical net.
  - `[false]` `[reject]` (intent-alignment) Badge still overlays the 3D stage, in tension with a literal "nothing overlaps" — intent item (2) and SCP decision 4 require the badge in the top-left overlay, so the intent selects this reading.
  - `[false]` `[reject]` (intent-alignment) "Only Certainty indicator" checked only via absence of `stage:frame` — the frame label was the only other Certainty indicator on Solution Playback; no other status node exists in the composable.
  - `[false]` `[reject]` (intent-alignment) "At the end" is not tested after a matching Quick Check at the last step — behaviour is step-independent (the overlay guard does not read position); the 3/3 checkpoint asserts no frame label.
  - `[false]` `[reject]` (intent-alignment) Divergence note on out-of-list changes (spec file, import cleanup, extra assertions) — descriptive only; all support the stated items, no bad outcome.

## Auto Run Result

Status: done

**Summary:** Solution Playback no longer renders `FrameLabel` in Physical-cube Mode; the stage overlay exists only in 3D and holds only the View rotated badge, so the certainty chip below the stage is the only Certainty indicator and nothing overlays the 2D net.

**Files changed:**
- `app/src/main/kotlin/dev/cubentia/app/ui/solve/SolutionPlayback.kt` -- removed the frame label branch and unused imports; overlay guarded by `cube != null`.
- `app/src/test/kotlin/dev/cubentia/app/ui/solve/SolutionPlaybackTest.kt` -- frame-label assertions become absence checks; new physical 2D test; badge first (left/top edge, traversal) in physical 3D; chip Verified in 2D and 3D, cleared after Done.
- `app/src/androidTest/kotlin/dev/cubentia/app/render/Cube3DRenderSyncTest.kt` -- playback evidence asserts no frame label.

**Review findings:** 3 low patched (badge top edge x2 grouped, chip clears after Done); 0 deferred; 8 rejected (reasons in the Review Triage Log).

**Follow-up review recommended:** false (patched: high 0, medium 0, low 3).

**Verification:** `./gradlew spotlessApply :app:testDebugUnitTest --tests 'dev.cubentia.app.ui.solve.*' --tests 'dev.cubentia.app.ui.lesson.FrameLabelTest'` -- BUILD SUCCESSFUL (SolutionPlaybackTest 36/36). `./gradlew spotlessCheck build :app:compileDebugAndroidTestKotlin` -- BUILD SUCCESSFUL.

**Residual risks:** Not checked on a Galaxy S24 FE in portrait or with TalkBack; the instrumented `Cube3DRenderSyncTest` was compiled, not run.
