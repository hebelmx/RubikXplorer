---
title: 'Story 14.2: Practice an Algorithm on my real cube'
type: 'feature'
created: '2026-11-22'
status: done
baseline_revision: 'ca035d20d48fb6165af94d358ede233ba5bf2c74'
review_loop_iteration: 0
followup_review_recommended: false
context:
  - '{project-root}/AGENTS.md'
  - '{project-root}/_bmad-output/implementation-artifacts/epic-14-context.md'
warnings: ['oversized']
deferred: []
---

<intent-contract>

## Intent

**Problem:** Algorithm detail only plays an on-screen Demonstration; a Learner cannot drill an Algorithm on their own cube one turn at a time (FR-18, #208).

**Approach:** Add *I'm doing it on my cube* to Algorithm detail. A choice (*Set it up from solved* / *My cube already shows this case*) starts a `SOLVE_PLAYBACK` session (no new `SessionKind`) in Physical-cube Mode on the 2D net over the set-up Moves (the Algorithm's inverse, AD-19) then the Algorithm, labeled *Set-up* / *Algorithm* (AD-20), or the Algorithm only. Everything else (Done, Expected State, MoveNote, mismatch ladder, Quick Check) is the existing Solution Playback in Physical-cube Mode, reused unchanged.

Binding ADs (story Refs line, verbatim): **AD-3, AD-5, AD-18, AD-19, AD-20**. UX: UX-DR12, UX-DR14, UX Flow 11. FRs: FR-18, FR-16, FR-26, FR-7. Stories 8.5, 9.5, 10.7.

## Boundaries & Constraints

**Always:**
- AD-3: Algorithm detail stays outside any session; practice is a `SOLVE_PLAYBACK` session started with `SessionEvent.SolvePlaybackStarted` then `ModeSwitched(PHYSICAL)`; no new `SessionKind`; not resumed after app close (`resumesOnColdStart` unchanged).
- AD-19: the set-up is `Notation.parse(algorithm.notation).moves.inverse()` applied from solved in the frame `Demonstration.ofCase` / `LastLayerMatcher.caseState` uses (`RecognitionDiagram.LIBRARY_LAST_LAYER` on U), so the case the Learner set up is the one the detail showed. Nothing stores a Case state.
- AD-20: segments come from the existing `SegmentLabels` (`ui/solve/SolutionStyles.kt`); labels are content `Text` keys naming what is practiced (`msg.practice.segment.set-up`, `msg.practice.segment.algorithm`); per-Move text only from `MoveNote.of`.
- AD-5 / AD-18: Quick Check is the existing `playback:quickCheck` path; shown only when `quickCheck` is non-null (`CAMERA_SCAN` enabled); nothing camera-related in `app/src/main` beyond today's wiring.
- AD-6: all new copy in `core/domain/src/main/resources/content/strings/en.json`; every control labeled for TalkBack; fits at 200% font on 600 dp.

**Never:** a new `SessionKind` or reducer event; a Recognize or Quick Scan step in the *already shows this case* path; the *Solution style* chips in practice; editing planning artifacts.

## I/O & Edge-Case Matrix

| Scenario | Input / State | Expected Output / Behavior | Error Handling |
|----------|--------------|---------------------------|----------------|
| Set up from solved | any of the 78 Algorithms, choice *Set it up from solved* | session start = solved (Library frame); Moves = inverse(alg) + alg; segments [Set-up, Algorithm]; physical; 2D net | — |
| Already shows case | choice *My cube already shows this case* | start = Case state; Moves = alg; one segment [Algorithm] | — |
| Last *Done* | position == size | *Again* shows; tapping restarts from set-up, physical | — |
| Camera OFF | `CAMERA_SCAN` OFF | Done-only practice end to end; no camera icon | — |
| Bad notation | unparseable notation (not shipped) | *I'm doing it on my cube* not shown | — |

</intent-contract>

## Code Map

- `app/src/main/kotlin/dev/cubentia/app/ui/library/AlgorithmDetailScreen.kt` -- detail screen; `result` is `Demonstration.ofCase` (not Ready => error branch, so no practice button). `controls` lambda holds transport, strip, status chips: add the entry button there. `LaunchedEffect(algorithm.id) { if (autoPlay) state.play() }` re-runs when the screen re-enters composition (PO answer 3 needs no new code).
- `app/src/main/kotlin/dev/cubentia/app/ui/shell/EntryLevelScreen.kt` L178 `FindMyPlaceSheet` -- ModalBottomSheet precedent (dragHandle null, surfaceRaised, heading, full-width TextButtons `touchMin`, each closes the sheet first).
- `app/src/main/kotlin/dev/cubentia/app/ui/shell/AppShell.kt` -- L248 `openAlgorithm`/`autoPlayAlgorithm` saveables; L222 `quickCheck` (null when CAMERA_SCAN OFF); L351 `solveOnSolved`; L375-461 SOLVE_PLAYBACK overlay (`restartPlayback`, `onUseMyCube` re-solve + solved branch -> `endSolve` + Solve tab, `onClose`, `onLearn`); L857-880 Library `AlgorithmDetailScreen` call.
- `app/src/main/kotlin/dev/cubentia/app/ui/solve/SolvePlaybackScreen.kt` -- header Row (close + `solvePlayback:length`), `styles?.let` chips, `atEnd` -> `solve:learnOffer`.
- `app/src/main/kotlin/dev/cubentia/app/ui/solve/SolutionPlayback.kt` -- `SolutionPlaybackState`: `remember(session.solution, reduceMotion)` (same Moves => same state object), `origin`, `alignToExpected` (first index whose state == expected), `followOnMyCube`, `switchToVirtual`; physical controls, `playback:solvedNow`.
- `app/src/main/kotlin/dev/cubentia/app/ui/solve/SolutionStyles.kt` -- `SegmentLabels(moves, ends, labels)`; `segmentOf` shows a label only while `labels.moves == solution`.
- `core/application/.../session/Reduce.kt` L427 `startPlayback` (rejects a solution that does not solve the start), L631 `switchMode` (physical: `MoveHistory(virtual)`, PHYSICAL_PREDICTED); read-only.
- `core/domain/.../playback/Demonstration.kt` L68 `ofCase`; `lastlayer/LastLayerMatcher.kt` L126 `caseState` (`SOLVED.copy(orientation = LastLayerView.canonicalFrame(lastLayer)).apply(moves.inverse())`); `cube/Move.kt` L84 `List<Move>.inverse()`; `RecognitionDiagram.LIBRARY_LAST_LAYER` (YELLOW).
- `app/src/main/kotlin/dev/cubentia/app/session/SessionHost.kt` L321 `endSolve`, L700 `dispatch`.
- Tests: `app/src/test/.../ui/library/AlgorithmDetailScreenTest.kt`, `ui/shell/AppShellLibraryTest.kt` (`launch`, `library:case:<id>`), `ui/shell/AppShellSolveTest.kt` (`show(cameraScan, quickCheck)`, `click` waitUntil helper, QuickCheckCapture fakes), `ui/solve/SolutionPlaybackTest.kt`.

## Tasks & Acceptance

**Execution:**
- `core/domain/src/main/kotlin/dev/cubentia/domain/playback/AlgorithmPractice.kt` (new) -- `AlgorithmPractice(start, moves, algorithmStart)` + sealed `AlgorithmPracticeResult { Ready, InvalidNotation }`; `of(algorithm, fromSetUp, lastLayer = LIBRARY_LAST_LAYER)`: set-up => start = solved in `canonicalFrame(lastLayer)`, moves = inverse + alg, algorithmStart = inverse size; else start = `LastLayerMatcher.caseState`, moves = alg, algorithmStart = 0 -- AD-19. JVM test over all 78 shipped Algorithms: start.apply(moves).isSolved, set-up path passes through exactly `caseState` at algorithmStart.
- `app/src/main/kotlin/dev/cubentia/app/ui/solve/SolutionPlayback.kt` -- `alignToExpected` disambiguates equal states (practice: position k and 2n-k hold the same cube, and start == end): among indices whose state == expected prefer the position held when the Learner left physical mode (`switchToVirtual`), then, on a physical rebuild, one consistent with `session.history.done` (index >= done.size and `solution.subList(index - done.size, index) == done`), else the first; unique-match behavior unchanged -- recreation mid-Algorithm must not jump back into the set-up.
- `app/src/main/kotlin/dev/cubentia/app/ui/solve/SolvePlaybackScreen.kt` -- optional `title: String?` (heading `solvePlayback:title`, Move count `solvePlayback:length` under it as bodyMedium) and `onAgain: (() -> Unit)?` (`practice:again` button at `atEnd`, before the learn offer); defaults keep Solution Playback unchanged.
- `app/src/main/kotlin/dev/cubentia/app/ui/library/AlgorithmDetailScreen.kt` -- `onPractice: ((fromSetUp: Boolean) -> Unit)? = null`; button `algorithmDetail:practice` in `controls` after the strip row (Ready only); `PracticeChoiceSheet` (`algorithmDetail:practiceChoice`, options `algorithmDetail:practice:setUp` / `algorithmDetail:practice:alreadyCase`).
- `app/src/main/kotlin/dev/cubentia/app/ui/shell/AppShell.kt` -- saveables `practiceAlgorithm: String?`, `practiceFromSetUp`, `practiceRun: Int`; `startPractice(algorithm, fromSetUp)`; overlay is "practice" only while the marker's `AlgorithmPractice.moves == session.solution`; practice overlay: `key(practiceRun)`, title = Case name, `styles = null`, segments = Set-up/Algorithm labels, `onAgain`, close -> `endSolve` and back to the same detail (Library tab), learn -> `endSolve` + Learn tab; `onUseMyCube` and `solveOnSolved` clear the marker.
- `core/domain/src/main/resources/content/strings/en.json` -- keys `btn.library.practice` "I'm doing it on my cube", `msg.library.practice.question` "Set it up from solved, or does your cube already show this case?", `btn.library.practice.set-up` "Set it up from solved", `btn.library.practice.already-case` "My cube already shows this case", `msg.practice.segment.set-up` "Set-up", `msg.practice.segment.algorithm` "Algorithm", `btn.practice.again` "Again".
- Tests -- domain test above; `SolutionPlaybackTest` rebuild at a symmetric position; `AlgorithmDetailScreenTest` button/sheet; `AppShellLibraryTest` (or new `AppShellAlgorithmPracticeTest`) for every I/O row and AC below.

**Acceptance Criteria:**
- Given an Algorithm detail on the main shell, when the Learner taps *I'm doing it on my cube* then *Set it up from solved*, then playback shows the Case name heading with the Move count, Physical-cube Mode on the 2D net, label *Set-up* for the first inverse Moves and *Algorithm* after, no *Solution style* chips.
- Given practice, when the Learner taps *Done*, then the counter advances one Move with the `MoveNote` and physical controls of Solution Playback; at the last *Done*, *Again* and the learn offer show; *Again* restarts at 1/N physical from the set-up; the learn offer ends playback and opens Learn.
- Given `CAMERA_SCAN` above OFF with a Quick Check capture, when at any step (including mid-Algorithm), then `playback:quickCheck` shows and dispatches `QuickCheckRequested`; given OFF, no camera control shows and Done-only practice reaches *Again*.
- Given *My cube already shows this case*, then practice starts at the Algorithm's first Move (one *Algorithm* segment), with no Recognize or Quick Scan screen.
- Given practice, when the Learner closes it (close or system back), then the same Algorithm detail shows on the Library tab and no session is active; opened from Recognize with auto-play, its Demonstration plays again.
- Given a Full-scan mismatch, when *Use my cube as it is now* re-solves, then the session is a plain Solution Playback (Move count header, style chips on *Shortest*, close -> Solve tab); a solved cube ends playback and opens the Solve tab's already-solved message.

## Spec Change Log

## Review Triage Log

### 2026-11-22 — Review pass
- verdicts: 24 findings — high 0, medium 4, low 12, false 8, maybe-false 0 (intent-alignment auditor: descriptive only; it confirms the diff implements the literal readings — Again from set-up, net by default, no new SessionKind — with no divergence from the intent's surfaces beyond device-only checks)
- findings:
  - `[low]` `[reject]` Blind: no end-to-end Quick Check observation lands on k vs 2n-k mid-Algorithm — a match observation does not move the position (only Done/Undo do; `QuickCheckObserved` changes certainty, not `expected`), so the tie-break is not on that path; adding a camera-observation shell test is more than a direct correction.
  - `[false]` `[reject]` Blind: `leftPhysicalAt` never cleared, follow after scrubbing jumps back — in virtual mode `session.expected` stays at the left-off state, so alignment targets that state by design (pre-existing Follow behavior); the hint only picks among equal states.
  - `[low]` `[reject]` Blind: `filter` over all positions is O(n^2) — the old `firstOrNull` was O(n^2) worst case too; runs only on rebuild/follow over at most ~100 Moves; negligible.
  - `[false]` `[reject]` Blind: *Use my cube* on a solved cube goes to the Solve tab, not the detail — PO clarification (1) requires exactly that.
  - `[false]` `[reject]` Blind: end of practice still offers Learn; Again weaker — PO clarification (2) requires both; learn offer sizing is pre-existing Solution Playback.
  - `[low]` `[reject]` Blind: Again double tap — host dispatch is serialized; two taps restart twice and end at step 1 physical; harmless, a guard adds state.
  - `[false]` `[reject]` Blind: practice recorded as solve attempts / no endSolve before Again — SOLVE_PLAYBACK writes no attempt rows (`attemptOpen = false`), and restarting without `endSolve` is the existing `restartPlayback` pattern.
  - `[false]` `[reject]` Blind: `startPractice` fails silently — the button exists only when `Demonstration.ofCase` is Ready, which needs the same parse and `caseState`; the detail renders only with `ready != null`; unreachable.
  - `[low]` `[reject]` Blind: `AlgorithmPractice.of` built twice, list comparison — cost negligible; the Moves guard is the spec's stale-marker design; no named harm.
  - `[low]` `[reject]` Blind: public constructor accepts invalid values — only `of` builds one; no caller passes other values.
  - `[low]` `[reject]` Blind: notation parsed twice on the already-case path — negligible; both failures map to the same sealed result.
  - `[low]` `[reject]` Blind: hard-coded 78 in the domain test — the story's AC is "any of the 78 Algorithms"; the count is the intended fact.
  - `[low]` `[patch]` Blind: misleading comment in the already-case shell test — comment corrected.
  - `[low]` `[reject]` Blind: fully qualified `dev.cubentia.domain.text.Text` in AppShell — cosmetic, matches the file's existing style.
  - `[low]` `[patch]` Blind: no test that dismissing the choice sheet starts nothing — test added in AlgorithmDetailScreenTest.
  - `[false]` `[reject]` Blind: strings only in English — en.json is the only locale (AD-6); the content key test covers missing keys, and shell tests assert the resolved copy.
  - `[medium]` `[patch]` Verification-gap: physical rebuild right after re-following mid-Algorithm (empty `history.done`) lands in the set-up — the tie-break hint is now kept in `rememberSaveable` and passed into the rebuilt state; test added.
  - `[medium]` `[patch]` Edge: same root cause as above (empty done matches every index) — same fix.
  - `[medium]` `[patch]` Edge: recreated in virtual mid-Algorithm then Follow picks the set-up copy — same root cause (hint not durable); same fix.
  - `[false]` `[reject]` Edge: `SolvePlaybackStarted` ignored leaves the marker set — every shipped Algorithm's start verifies (domain test over all 78) and `ready` is non-null wherever the button shows.
  - `[low]` `[patch]` Edge: Again after an already-case run flips `practiceFromSetUp` before the session lands, flashing plain playback — guard now matches either variant of the marked Algorithm.
  - `[low]` `[reject]` Edge: double tap on Again or a sheet option — same as the Blind double-tap row; harmless.
  - `[false]` `[reject]` Edge: Again tappable while the restart is in flight — a second tap only restarts again at step 1; no wrong state.
  - `[medium]` `[patch]` Edge (claim): spec says recreation mid-Algorithm must not jump back into the set-up, but ambiguous history still picks the first match — same root cause and fix as the verification-gap row.

## Design Notes

Settled from the story, UX spec, code and PO clarifications (2026-11-22):
- Choice: Material3 `ModalBottomSheet` (14.1 *Find my place* precedent, one modal level); heading = the Flow 11 question; options close the sheet then start; back/scrim dismiss and start nothing.
- Opens in Physical-cube Mode on the 2D net (Flow 11; `defaultPlaybackView` picks the net when physical unless the Learner saved a view: per the Story 12.14 PO decision a saved `playbackView` wins in both modes, so it is kept here as in Solution Playback). Done only advances; read-only strip, no scrubber (UX-DR14); no auto-play or new animation.
- *Again* (Flow 11 step 4) replays from the set-up in both paths (after the Algorithm the cube is solved, so only the set-up is coherent), physical at step 1, unscanned baseline.
- End of practice (PO 2): *Again* AND `solve:learnOffer`; end text stays `msg.playback.cube-should-be-solved`; no "Solved!" on a Done tap.
- *Use my cube as it is now* (PO 1): exactly Solution Playback's path; the re-solved session is a Solver playback, so the practice marker is cleared (header, chips, close target, end offers become Solution Playback's).
- Auto-play on return (PO 3): the detail re-enters composition with `autoPlayAlgorithm` unchanged, so its `LaunchedEffect` plays again; opened from the grid it returns paused.
- Header (PO 4): Case name (`strings.resolve(case.name)`, e.g. "OLL 37") as `titleMedium` heading, `msg.playback.solution-length` under it.
- Close / back returns to the same Algorithm detail (UX-DR22: a task flow returns to its caller), via `host.endSolve` (a held Lesson resumes, as from Solve).
- Practice state (marker, run) is UI navigation state in `rememberSaveable`, never in the session or a checkpoint (AD-3, AD-20); a marker whose Moves do not match the session is ignored.

## Verification

**Commands:**
- `./gradlew :core:domain:test :core:application:test` -- pass.
- `./gradlew :app:testDebugUnitTest` -- pass.
- `./gradlew spotlessCheck build` -- pass.

## Auto Run Result

Previous run (planning) halted: intent gap on four UI questions; answered by the PO on 2026-11-22 (recorded in Design Notes as PO 1-4).

Status: done

**Summary:** Algorithm detail gains *I'm doing it on my cube* with a choice sheet (*Set it up from solved* / *My cube already shows this case*). Either starts a `SOLVE_PLAYBACK` session (no new `SessionKind` or event) switched to Physical-cube Mode, with *Set-up* / *Algorithm* segment labels, the Case name heading over the Move count, no style chips, *Again* plus the learn offer at the end, and close/back returning to the same detail (auto-play resumes when opened from Recognize). *Use my cube as it is now* turns it into a plain Solution Playback (a solved cube goes to the Solve tab's already-solved message).

**Files changed:**
- `core/domain/.../playback/AlgorithmPractice.kt` (new) -- start, Moves and Algorithm offset for both paths (AD-19 frame and inverse).
- `app/.../ui/library/AlgorithmDetailScreen.kt` -- practice button and `PracticeChoiceSheet`.
- `app/.../ui/shell/AppShell.kt` -- practice marker and run counter, `startPractice`, *Again*, close/learn/use-my-cube routing; the guard matches either variant of the marked Algorithm.
- `app/.../ui/solve/SolvePlaybackScreen.kt` -- optional `title` and `onAgain`.
- `app/.../ui/solve/SolutionPlayback.kt` -- equal-state tie-break in `alignToExpected`, with the left-physical position saved across recreation.
- `core/domain/src/main/resources/content/strings/en.json` -- 7 new keys.
- Tests: `AlgorithmPracticeTest` (all 78), `AppShellAlgorithmPracticeTest` (8), `AlgorithmDetailScreenTest` and `SolutionPlaybackTest` additions.

**Review findings:** 24 findings. 5 entries patched: the durable tie-break hint (medium, 4 rows), the Again variant flash (low), a test comment (low) and a sheet-dismiss test (low). Nothing deferred. Rejected: 8 false and 9 low, each with its reason in the Review Triage Log.

**Follow-up review recommended:** false. Patched entries by verdict: high 0, medium 1, low 3.

**Verification:** `./gradlew spotlessCheck build` passed before and after the patches; it covers `:core:domain:test`, `:core:application:test`, `:app:testDebugUnitTest`, lint, Kover and the APKs. Every I/O matrix row is covered by a test that ran and passed.

**Residual risks:** the device check on the Lenovo Tab M10 Plus and Galaxy S24 FE (TalkBack, 200% font on 600 dp, real cube, Quick Check with the camera) is the story's human gate and has not been run. Starting a practice sends two events, so one frame may show virtual mode before physical, as the existing *Use my cube* path already does.
