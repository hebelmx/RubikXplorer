---
title: 'GH #298: Highlight the Quick Check icon in Solution Playback and Algorithm practice (parity with 14.3 Lessons)'
type: 'feature'
created: '2026-11-22'
status: 'done'
baseline_revision: '1a0a020ee481b3c3e61fcbd5265e3dab47719e4c'
review_loop_iteration: 0
followup_review_recommended: false
context:
  - '{project-root}/AGENTS.md'
  - '{project-root}/_bmad-output/implementation-artifacts/spec-14-3-practice-every-lesson-step-on-my-real-cube-with-a-quick-chec.md'
warnings: ['oversized']
deferred: []
---

<intent-contract>

## Intent

**Problem:** EXPERIENCE.md:95 says the Quick Check icon stays available at every step of a Lesson, Solution Playback and Algorithm practice and is offered (highlighted) only at the suggested points. Story 14.3 highlights it in Lessons only; Solution Playback (Story 10.7) and Algorithm practice (Story 14.2, a `SOLVE_PLAYBACK` session with *Set-up* / *Algorithm* segments) always draw a plain `IconButton`. The 14.3 "after a mismatch" highlight also ends as soon as the mismatch resolves, while the PO decided (2026-11-22) it lasts until the next *Done* (retro R5, sprint action epic-14-retro-item-229).

**Approach:** Extract the 14.3 Lesson button (`FilledIconButton` with accent / onAccent when highlighted, transparent / inkSecondary when plain, `QuickCheckHighlightedKey`) and a small shared after-mismatch tracker (set by `PHYSICAL_MISMATCH`, cleared by the next *Done*) into one file in `ui/lesson`, and use both from `LessonScreen` and `SolutionPlayback`. In Solution Playback (and so Algorithm practice) the boundary points are segment ends plus the last step.

## Boundaries & Constraints

**Always:**
- One implementation: Lesson and Playback call the same button composable and the same tracker; `QuickCheckHighlightedKey` stays the one test-only semantics key (same package `dev.cubentia.app.ui.lesson`, so existing imports keep working).
- Highlighted = accent container, onAccent icon; plain = transparent container, inkSecondary icon. Same tag per screen as today (`lesson:quickCheck`, `playback:quickCheck`), same click, same `contentDescription` "Quick Check" (`btn.physical.quick-check`) in both states; no stateDescription, no new copy, no animation.
- Suggested points, both screens: `syncState == PHYSICAL_MISMATCH`; after a mismatch until the next *Done* (a confirm of a Move: Lesson `onDone` and the move-key bar `PhysicalConfirmed(move)`, Playback `confirmStep`); a pending `quickCheckRetry`. Lesson adds its 14.3 boundary terms (`decision.advance || objectiveMet`). Playback adds: physical `position > 0` and (`position` is one of the `SegmentLabels.ends` of the segments being played, only while `segments.moves == solution`, or `position == size`).
- "It matches after all" (`PhysicalConfirmed(null)`), Undo / Back and a Quick Check that resolves the mismatch do not clear the after-mismatch highlight; only the next *Done* does.
- AD-3: no reducer, `SessionEvent` or `SessionKind` change; the tracker is UI state (`rememberSaveable`), never in the session or a checkpoint. AD-18: icon only when `quickCheck` is non-null, as today.
- PRACTICE sessions in `LessonScreen` (Stage practice, 2-look LL practice) keep 14.3 behavior: Story 10.7 visibility gate unchanged, always plain.

**Never:** changing when the icon is visible in Playback (every physical step, as today) or in Lessons; reducer/Assess changes; new strings; editing planning artifacts or `sprint-status.yaml`; a second copy of the button or rule.

## I/O & Edge-Case Matrix

| Scenario | Input / State | Expected Output / Behavior | Error Handling |
|----------|--------------|---------------------------|----------------|
| Playback mid-segment | physical, position inside a segment (not 0, not an end, not size) | `playback:quickCheck` plain (`QuickCheckHighlightedKey` false) | — |
| Playback segment end | method style with segments; Done reaches `position == ends[i]` | highlighted; next Done (mid-segment) -> plain | — |
| Playback last step | Shortest (no segments), `position == size` | highlighted | — |
| Playback after mismatch | Quick Check observation differs -> MISMATCH | highlighted; stays highlighted after Back / matches-after-all; next Done -> plain (if mid-segment) | — |
| Practice set-up -> Algorithm | set-up practice, Done n times (`position == n`) | highlighted; Done once more -> plain | — |
| Practice mid-Algorithm / mismatch | as Playback rows | as Playback rows | — |
| Lesson after mismatch resolved | MISMATCH then *It matches after all*, mid-step | still highlighted; next *Done* mid-step -> plain | — |
| Camera OFF | `quickCheck == null` | no icon in any screen | — |

</intent-contract>

## Code Map

- `app/src/main/kotlin/dev/cubentia/app/ui/lesson/LessonScreen.kt` -- L145 `QuickCheckHighlightedKey` (move to the shared file); L654-656 `quickCheckEveryStep` / `quickCheckHighlighted` gate; L724-729 `onDone`; L726-735 `onMatchesAfterAll` (not a Done); L753-790 the `quickCheck` slot with the `FilledIconButton` to extract; L835-838 move-key bar `PhysicalConfirmed(move)` (a Done).
- `app/src/main/kotlin/dev/cubentia/app/ui/solve/SolutionPlayback.kt` -- L455-487 physical controls, plain `IconButton` tagged `playback:quickCheck` (replace with the shared button); L183 `segmentOf` (same `labels.moves == solution` guard for boundaries); L258-263 `confirmStep` (the Done); L211 `onLeftPhysical` / `rememberSolutionPlaybackState` ~L320-345 (precedent for wiring remembered UI state into the state object; key by `session.solution`); `position`, `size`, `atEnd`.
- `app/src/main/kotlin/dev/cubentia/app/ui/solve/SolutionStyles.kt` L70-100 -- `SegmentLabels(moves, ends, labels)`; ends are cumulative Move counts.
- `app/src/main/kotlin/dev/cubentia/app/ui/shell/AppShell.kt` ~L468 -- passes `segments = practiceSegments ?: segmentLabels` (practice Set-up/Algorithm ends = [n, 2n] or [n]); read-only.
- `core/application/.../session/TRANSITIONS.md` L260-270 -- MISMATCH exits: `PhysicalConfirmed(move)` (Done), `PhysicalConfirmed(null)` (matches after all), Undo, EditorEntered; read-only.
- Tests: `app/src/test/.../ui/lesson/PhysicalLessonScreenTest.kt` L1722 `assertQuickCheckHighlighted`, L1729 boundary test, L1749 `quickCheckIsHighlightedAfterAMismatch`, `reportMismatch` L146; `ui/shell/AppShellAlgorithmPracticeTest.kt` (`practice`, `done`, `awaitPhysicalAt`, `observePartial` / `observeFull` L476-500, mid-Algorithm Quick Check L347); `ui/shell/AppShellSolveTest.kt` (`show(cameraScan, quickCheck)`, L497/L562 playback Quick Check tests, L937 method style segment test); `ui/solve/SolutionPlaybackTest.kt`.

## Tasks & Acceptance

**Execution:**
- `app/src/main/kotlin/dev/cubentia/app/ui/lesson/QuickCheckButton.kt` (new) -- move `QuickCheckHighlightedKey` here; add `QuickCheckButton(highlighted, label, modifier/tag, onClick)` (the 14.3 single `FilledIconButton` with switching colors and `ic_camera`); add a `@Stable` after-mismatch tracker with `done()` and a `rememberQuickCheckSuggestion(syncState, key)` that uses `rememberSaveable` and sets the flag when `syncState` enters `PHYSICAL_MISMATCH` (keyed effect, so a Done tapped while still in MISMATCH is not undone); one shared helper combining mismatch / after-mismatch / retry with a screen-supplied boundary flag.
- `LessonScreen.kt` -- use the shared button and tracker; highlighted = `quickCheckEveryStep && (boundary terms || shared suggested)`; visibility gate unchanged (10.7 terms for PRACTICE); call `done()` in `onDone` and the move-key-bar physical confirm.
- `SolutionPlayback.kt` -- replace the plain `IconButton` with the shared button; compute the boundary from `segments` / `size` as in Boundaries; tracker remembered in `rememberSolutionPlaybackState` (keyed by `session.solution`) and cleared in `confirmStep`.
- `PhysicalLessonScreenTest.kt` -- pin the PO rule: mismatch -> *It matches after all* mid-step still highlighted -> *Done* mid-step -> plain; keep existing tests green.
- `AppShellSolveTest.kt` (or `SolutionPlaybackTest.kt` if it drives the composable with a capture) -- Solution Playback: plain mid-segment, highlighted at a method segment end and at the last step, highlighted after a Quick Check mismatch and cleared after the next Done; label "Quick Check" in both states.
- `AppShellAlgorithmPracticeTest.kt` -- practice: highlighted at the Set-up -> Algorithm boundary, plain mid-Algorithm, highlighted after a mismatch and cleared after the next Done; label "Quick Check".

**Acceptance Criteria:**
- Given Solution Playback or Algorithm practice in Physical-cube Mode with a Quick Check capture, when the icon renders, then it is accent-filled exactly at the suggested points and plain inkSecondary otherwise, carrying `QuickCheckHighlightedKey` and the "Quick Check" label in both states.
- Given a Lesson, Solution Playback or Algorithm practice after a mismatch, when the mismatch resolves without a *Done*, then the icon stays highlighted until the next *Done*, then clears (unless that Done lands on a boundary).
- Given `CAMERA_SCAN` OFF, when any of the three screens is in Physical-cube Mode, then no Quick Check icon shows (unchanged).

## Spec Change Log

## Review Triage Log

### 2026-11-22 — Review pass
- verdicts: 20 findings — high 0, medium 0, low 13, false 7, maybe-false 0
- findings:
  - `[low]` `[reject]` (blind) After-mismatch flag set from a LaunchedEffect, so a mismatch that starts and ends before a frame sees it is missed — a mismatch comes from a Learner action or a Quick Check result and leaves only on another Learner action, so a frame always shows it; rewriting state in composition is more than a direct correction
  - `[low]` `[reject]` (blind) Leaving and re-entering physical mode keeps the flag — matches the PO rule literally (lasts until the next Done; no Done happened); the icon is hidden in virtual mode
  - `[false]` `[reject]` (blind) `onConfirmMatches` (Confirm card) is not treated as a Done — it is `PhysicalConfirmed(null)`, which confirms no Move; the spec defines Done as a Move confirm
  - `[low]` `[patch]` (blind) Practice visibility passes a bare `false` for afterMismatch — now a named argument with a comment (PRACTICE keeps the 10.7 gate)
  - `[low]` `[patch]` (blind; verification-gap) No test that the saved flag survives recreation — added `theAfterMismatchHighlightSurvivesRecreationUntilTheNextDone` (StateRestorationTester)
  - `[false]` `[reject]` (blind) EXPERIENCE.md not updated — a planning artifact this run must not edit; the PO decision is recorded in the spec and issue #298
  - `[false]` `[reject]` (blind) Orphan default `QuickCheckSuggestion()` on SolutionPlaybackState — the only construction in main code is `rememberSolutionPlaybackState` (SolvePlaybackScreen.kt:78), which assigns the remembered tracker every composition (the `onLeftPhysical` precedent)
  - `[false]` `[reject]` (blind) Playback button type change needs a layout check — the Lesson row already uses the same FilledIconButton (same 40 dp container as IconButton) with a 200% font test; the playback row size is unchanged
  - `[low]` `[patch]` (blind) Segment ownership check duplicated in `segmentOf` and `atQuickCheckBoundary` — extracted private `ownLabels(labels)`
  - `[low]` `[reject]` (blind) Test tidiness (assertTrue(!x), fully qualified key, duplicated helpers/scaffold) — style only, no named harm
  - `[low]` `[reject]` (edge) LaunchedEffect cancelled before it runs — same root cause and reason as the first row
  - `[false]` `[reject]` (edge) A restarted Lesson with the same id opens highlighted — `LessonStarted` is dispatched only on entry (LessonScreen.kt:291); reopening a Lesson leaves composition, which drops the saveable state; no in-screen restart exists
  - `[low]` `[reject]` (edge) A new playback with an equal Solution inherits the flag — practice *Again* re-keys the overlay (`key(practiceRun)`), and style restarts change the Moves; an equal-Moves new session in the same composition needs a rare path, and only a cosmetic highlight results
  - `[false]` `[reject]` (edge) Done with `stepMove == null` leaves the highlight — that Done confirms nothing, so no Done happened under the rule
  - `[low]` `[patch]` (verification-gap) Recreation not covered — grouped with the restoration row above; same test
  - `[low]` `[patch]` (verification-gap) Flag reset on the practice *Use my cube* re-solve not asserted — added the plain-at-0/1 assertion to the existing test
  - `[false]` `[reject]` (intent) Reading A2 (lasts until the Lesson step advances) — the PO decision reads "until the next Done"; each Done advances one step in Playback, and the parenthetical glosses Done
  - `[low]` `[patch]` (intent) Solution Playback boundaries tested only with hand-written SegmentLabels — added `aMethodStyleHighlightsThePhysicalQuickCheckAtItsSegmentEndsOnly` through the shell with the Beginner style
  - `[low]` `[reject]` (intent) Already-case practice (end-only boundary) untested — same code path as the Shortest no-segments test
  - `[low]` `[reject]` (intent) Retry term untested in Playback — the shared `quickCheckSuggested` is the one rule, and the Lesson retry test covers it

## Design Notes

PO decisions (2026-11-22, issue #298): after-mismatch highlight lasts until the next *Done*; Solution Playback boundaries = segment ends plus the last step. Lessons are aligned to the same rule (resolves epic-14-retro-item-229 / retro R5).

"Segment end" and "last step" read like the Lesson's Objective boundary: the point right after the Learner completed the segment (`position == end`), where a check confirms the finished part; the last step is `position == size` (all Moves done, "your cube should be solved"), which also covers Shortest with no segments. Practice ends [n, 2n] give Set-up -> Algorithm and the end. Position 0 is never a boundary.

## Verification

**Commands:**
- `./gradlew :app:testDebugUnitTest` -- expected: pass.
- `./gradlew spotlessCheck build` -- expected: BUILD SUCCESSFUL.

## Auto Run Result

Status: done

**Summary:** Solution Playback and Algorithm practice now highlight the Quick Check icon (accent container, onAccent icon) at the suggested points: segment ends, the last step, during a mismatch, after a mismatch until the next *Done*, and while a retry is pending. Elsewhere it is plain (inkSecondary). Lessons follow the same after-mismatch rule now: the highlight stays until the next *Done* instead of ending when the mismatch resolves. This resolves epic-14-retro-item-229; sprint-status.yaml is not edited. The label stays "Quick Check", with no new copy and no reducer change.

**Files changed:**
- `app/src/main/kotlin/dev/cubentia/app/ui/lesson/QuickCheckButton.kt` (new) -- the shared `QuickCheckButton`, `QuickCheckHighlightedKey` (moved), `quickCheckSuggested`, and `QuickCheckSuggestion` / `rememberQuickCheckSuggestion` (after-mismatch flag, saved across recreation).
- `app/src/main/kotlin/dev/cubentia/app/ui/lesson/LessonScreen.kt` -- uses the shared button and tracker; Done and the Move key clear the flag; PRACTICE is unchanged.
- `app/src/main/kotlin/dev/cubentia/app/ui/solve/SolutionPlayback.kt` -- the shared button; the boundary is a segment end or the last step (`ownLabels`); `confirmStep` clears the flag.
- Tests: `PhysicalLessonScreenTest` (3), `SolutionPlaybackTest` (2), `AppShellAlgorithmPracticeTest` (1 new, 1 extended), `AppShellSolveTest` (1 shell test with the Beginner style).

**Review findings:** 20 findings. 6 rows patched as 5 fixes, all low: named argument, `ownLabels` helper, restoration test, re-solve reset assertion, shell method-style test. Nothing deferred. 7 rejected as false and 7 rejected as low, each with its reason in the Review Triage Log.

**Follow-up review recommended:** false. Patched entries by verdict: high 0, medium 0, low 5.

**Verification:** `./gradlew spotlessCheck build` -- BUILD SUCCESSFUL after the patches; it covers `:app:testDebugUnitTest`. Every I/O matrix row is covered by a test that passed; the camera-OFF row is covered by the existing tests.

**Residual risks:** the colors are checked by pixels only on the Lesson (same shared button); nothing has been run on a device. The reading that a boundary is the point after the segment's last *Done* (`position == end`, and `size` for the last step) is a planning choice, recorded in Design Notes.
