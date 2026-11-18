---
title: 'Story 11.2: Start the Course from my cube (Placement)'
type: 'feature'
created: '2026-11-17'
status: 'done'
baseline_revision: '6e180b068c976ed6b2ea2cb43a28352fff968868'
review_loop_iteration: 0
followup_review_recommended: false
context:
  - '{project-root}/AGENTS.md'
  - '{project-root}/_bmad-output/implementation-artifacts/epic-11-context.md'
warnings: ['oversized']
deferred:
  - summary: >-
      Welcome's Start from my cube label (and the other Welcome labels) come from strings.xml, not content strings.
    evidence: |-
      app/src/main/res/values/strings.xml welcome_* keys predate Story 11.2; AD-6 keeps Learner text in en.json.
    location: >-
      app/src/main/kotlin/dev/cubentia/app/ui/welcome/WelcomeScreen.kt
    severity: low
---

<intent-contract>

## Intent

**Problem:** A Learner who can already do part of the Course has no way to start from their real cube: Welcome's *Start from my cube* is unwired (`AppShell` never passes `onStartFromCube`), nothing evaluates the Stage Predicates on a scan, and the existing `PlaceFromScan` writer has no caller.

**Approach:** Welcome → *Start from my cube* → Guided Scan (or the Manual Cube Editor on fallback/denial) → a pure domain evaluation of each Stage Predicate on the accepted Full state → a Placement screen with the EXPERIENCE.md outcome copy and options → on acceptance `PlaceFromScan` records the skips/unlock, onboarding is persisted, and the Learner lands on Learn. Debug raises `PLACEMENT` to GA in the same change.

Refs: FR-27, FR-4 · AD-6, AD-10, AD-18 · AR-21 · UX-DR19, UX-DR25 · UX Flow 4. Binding ADs: **AD-6**, **AD-10**, **AD-18**.

## Boundaries & Constraints

**Always:**
- AD-6: Stage Predicates come from content: a Stage's Stage Predicate is its **last Lesson's `completion`**. Stage 1 (its completion is `state-reached`, a Notation exercise) and Stage 7 (no Lessons) have none. All Learner text is `Text(key, args)` from `core/domain/src/main/resources/content/strings/en.json`; no new Learner text in `strings.xml` or hard-coded.
- AD-10 / AR-21: `PlaceFromScan` stays the only writer of `StageSkippedByPlacement`; its existing `invoke` body is reused unchanged (an added read-only `suggest` method is allowed). Writes go through `SessionHost` with the serial `writer`, like `passSkipCheck`.
- AD-18: *Start from my cube* shows only when `flags.isEnabled(PLACEMENT)` **and** a placement scan action exists; at BETA it carries the shared `BetaLabel` (`msg.feature.beta`) inside the button plus `BetaNote(msg.feature.camera.beta-note)` below it, and TalkBack reads "<label>, BETA" — same pattern as Solve's *Scan my cube* (`SolveScreen.kt` L61-91). Debug sets `PLACEMENT=GA`; release/benchmark stay OFF.
- Placement never calls the Solver and never touches a session.

**Never:** Rewrite `PlaceFromScan.invoke`; add a Recognize screen or link (Story 11.3); add per-Stage progress sentences; add a new `ProgressEvent`, Room change or content-schema field; edit planning artifacts.

## I/O & Edge-Case Matrix

Evaluation order on the accepted `CubeState` (domain `Placement.evaluate`), first match wins:

| Scenario | Input / State | Expected Output / Behavior | Error Handling |
|---|---|---|---|
| Solved | `state.isSolved` | Text `msg.placement.solved` ("Your cube is solved — scramble it and scan again, or start Stage 1."); buttons *Scan again* (relaunches the Guided Scan at once) and *Start Stage 1* (exactly the *I'm new* path: `Onboarded(true)`, no progress write, Stage 1 detail opens). No *Choose another Stage*. | — |
| Non-white cross | white-cross Stage Predicate (Stage 2) unmet, and some non-WHITE center's face has its 4 cross edges solved (face color on that face, side stickers match their centers) | Suggest Stage 1. Text `msg.placement.non-white-cross` ("Start with a white cross for this course."); buttons *Start* (accepts Stage 1) and *Choose another Stage* | — |
| Nothing solved | white-cross Stage Predicate unmet, no other cross | Suggest Stage 1: `msg.placement.suggest` "Start at {stage}?" with Stage 1's title; *Start*, *Choose another Stage* | — |
| At the last layer | first unmet Stage Predicate (in Course order) is Stage 5's (`last-layer-oriented`) | `msg.placement.suggest` with Stage 5's title; *Start* (Stage 5), *Faster last layer*, *Choose another Stage* | — |
| Partly solved | first unmet Stage Predicate is Stage 3, 4 or 6's | `msg.placement.suggest` with that Stage's title; *Start*, *Choose another Stage* | — |
| Scan cancelled | `ScanOutcome.Cancelled` | Back on Welcome, nothing written | — |
| Fallback / denial | `ScanOutcome.FellBackToEditor`, or `cameraDenied` true | Manual Cube Editor (initial = solved cube) with note `msg.solve.scan-fell-back` / `msg.solve.scan-camera-denied`; the cube accepted there is evaluated exactly like a scanned Full state | Editor validation as in Solve |
| Write fails | `PlaceFromScan` returns `Failed` | Same as the full-Course skip check: not surfaced; navigation still happens and Learn shows the real derived status | No new copy |

</intent-contract>

## Code Map

- `core/application/src/main/kotlin/dev/cubentia/application/usecase/PlaceFromScan.kt` -- the writer to reuse; `invoke(targetStageId, stages, at, …)` marks earlier LOCKED/IN_PROGRESS Stages skipped by placement and unlocks the target. Do not change `invoke`.
- `core/application/.../usecase/PassSkipCheck.kt`, `app/.../session/SessionHost.kt` L482-542 -- `passSkipCheck` wrapper (writer.read, refresh, in-memory fallback when no repository) is the pattern for a new `SessionHost.placeFromScan`. Constructor wiring: L81, L98, L113, L182; DI already provides `PlaceFromScan` (`app/.../di/PersistenceModule.kt:82`).
- `core/domain/.../tutor/StageProgress.kt` -- status derivation; `core/domain/.../predicate/WhiteLayer.kt` -- `correctPieces` is white-only; generalize with a center-color parameter (default WHITE) for the non-white cross check. `FaceletTables.EDGE_FACELETS`, `FaceletTables.center`.
- `core/domain/src/main/resources/content/stages/*.json` -- final completions: 02 `white-cross-solved`, 03 cross+corners, 04 +middle, 05 `last-layer-oriented`, 06 `cube-solved`; 01 `state-reached`; 07 no Lessons. 2-look: `content/two-look/stage-08-two-look-oll.json`, `stage-09-two-look-pll.json` (`ContentBundle.twoLookStages`).
- `app/src/main/kotlin/dev/cubentia/app/ui/welcome/WelcomeScreen.kt` L49-131 -- `onStartFromCube` param and `welcome:startFromCube` button (label `R.string.welcome_start_from_cube`, already chrome); no BETA handling yet.
- `app/.../ui/shell/AppShell.kt` -- `ShellContent` state (L197-230: `tab`, `welcome`, `selectedStage`, `scrollToTwoLook` + its LaunchedEffect), Welcome call L332-354 (*I'm new* L335-345 is the landing pattern), course skip-check pass L513-525, `allStagesFinished` L567. `AppShell(...)` L120-146 takes `onScan`, `solveFlow` with test defaults.
- `app/.../ui/shell/LearnScreen.kt` L113-121 -- a 2-look `selectedStage` resolves only when `showFasterLastLayer` (all Course Stages finished); 2-look rows L266-278.
- `app/.../MainActivity.kt` L99-131 -- `ScanFlowContract` registration + `cameraDenied` check routed to `solveFlow`; `scanInFlight` guard. Registration must happen in `onCreate`.
- `app/.../ui/solve/SolveFlowViewModel.kt` -- `@HiltViewModel` sealed-phase pattern; `ScanOutcome` in `app/.../scan/ScanOutcome.kt`.
- `app/.../ui/editor/ManualCubeEditor.kt:101` -- `ManualCubeEditor(strings, onAccept: (CubeState) -> Unit, modifier, initial)`; SolveScreen L104-118 shows the editor with a Close button (`btn.solve.close`) and note.
- `app/.../ui/beta/BetaLabel.kt` -- `BetaLabel`, `BetaNote`.
- `build-logic/.../policy/FeatureFlagLevels.kt` L50 debug map; `build-logic/src/test/.../FeatureFlagLevelsTest.kt` L11 pins the debug string.
- Tests: `app/src/test/.../ui/shell/AppShellSkipCheckTest.kt`, `AppShellSolveTest.kt` (fake scan lambda L252, `show()` L147-171), `app/src/sharedTest/.../session/TestLessons.kt:154` `testFlags(placement=…)`, `app/src/test/.../ui/welcome/WelcomeScreenTest.kt`.

## Tasks & Acceptance

**Execution:**
- `core/domain/src/main/kotlin/dev/cubentia/domain/predicate/WhiteLayer.kt` -- add a center-color parameter (default `Color.WHITE`) or a sibling `crossSolved(state, color)`; white predicates unchanged -- reuse for non-white cross.
- `core/domain/src/main/kotlin/dev/cubentia/domain/tutor/Placement.kt` (new) -- `sealed interface PlacementOutcome { Solved; NonWhiteCross(stage); Suggested(stage); AtLastLayer(stage) }` and `object Placement { fun evaluate(state: CubeState, stages: List<Stage>): PlacementOutcome }` per the matrix; Stage Predicate = last Lesson's `completion`, skipping Stages without Lessons or whose completion is `StateReached`; "first unmet is the first predicate-bearing Stage" ⇒ suggest `stages.first()` (Stage 1); AtLastLayer when the first unmet Stage's predicate is `LastLayerOriented` -- pure, content-driven.
- `core/domain/src/test/.../tutor/PlacementTest.kt` (new) -- one test per matrix row: SOLVED; solved cube after `U` (non-white cross: D cross intact, white cross misaligned); a real scramble (nothing solved); Lesson entry states of `lesson-03-01` (Stage 3), `lesson-04-01` (Stage 4), `lesson-05-01` (at LL → Stage 5), `lesson-06-01` (Stage 6); plus the whole-cube rotation invariance of one case.
- `core/application/.../usecase/PlaceFromScan.kt` -- add `fun suggest(state: CubeState, stages: List<Stage>): PlacementOutcome = Placement.evaluate(state, stages)`; KDoc; `invoke` untouched. Test it in the existing/new `PlaceFromScanTest`, plus a test that `invoke(stage-08, course + listOf(stage-08))` marks Stages 1–7 `StageSkippedByPlacement`.
- `app/.../session/SessionHost.kt` -- add `suspend fun placeFromScan(target: StageId, stages: List<Stage>): Boolean` mirroring `passSkipCheck` (optional injected `PlaceFromScan`, writer.read, `refreshProgressAfterWrite`, in-memory fallback appending the same events when no repository).
- `app/.../ui/placement/PlacementFlowViewModel.kt` (new, `@HiltViewModel`) -- phases `Idle`, `Editing(note: Text)`, `Result(outcome)`, `Choosing(outcome)`; `onScanOutcome` (Accepted → evaluate → Result; FellBackToEditor → Editing(`msg.solve.scan-fell-back`); Cancelled → Idle), `scanDenied()` → Editing(`msg.solve.scan-camera-denied`), `editorAccepted(state)` → evaluate → Result, `chooseAnother()`, `back()` (Choosing → Result; Result/Editing → Idle), `reset()`. Evaluation uses `PlaceFromScan.suggest` with the Course stages handed in by the shell.
- `app/.../ui/placement/PlacementScreen.kt` (new) -- full-screen: Result (prompt text as polite live region + buttons per matrix, tags `placement:prompt`, `placement:start`, `placement:fasterLastLayer`, `placement:chooseAnother`, `placement:scanAgain`, `placement:startStage1`); Choosing (title `msg.placement.choose-title` + one row per Course Stage 1–7 then each 2-look Stage, label "{number}. {title}" via existing stage title keys, tag `placement:stage:<id>`; tapping a row accepts it); Editing (Close `btn.solve.close`, note, `ManualCubeEditor` → `editorAccepted`). System back → `back()`.
- `app/.../ui/welcome/WelcomeScreen.kt` -- take `placementLevel`/`strings` (or equivalent) so BETA shows `BetaLabel` + `BetaNote` with TalkBack "<label>, BETA"; hide the button when `onStartFromCube` is null.
- `app/.../ui/shell/AppShell.kt` -- new params `onPlacementScan: (() -> Unit)?` and `placementFlow: PlacementFlowViewModel` (test default); pass `onStartFromCube = onPlacementScan.takeIf { flags.isEnabled(PLACEMENT) }`; while `welcome == true` and the placement phase ≠ Idle show `PlacementScreen` instead of Welcome. Acceptance `accept(target)`: if target is Stage 1 → *I'm new* path; else in `scope.launch` await `host.placeFromScan(target, stagesFor(target))` where `stagesFor` = Course stages for a Course target, `Course + listOf(target)` for a 2-look target; then `Onboarded(true)`, `welcome = false`, `tab = LEARN`, `placementFlow.reset()`, and `selectedStage = target` — except *Faster last layer* (target = first 2-look Stage, same write) sets `selectedStage = null` and `scrollToTwoLook = true`. *Scan again* calls `onPlacementScan`.
- `app/.../MainActivity.kt` -- register a second `ScanFlowContract` whose result goes to `placementFlow.onScanOutcome`; build `onPlacementScan` like `onScan` (shared `scanInFlight` guard, `cameraDenied` → `placementFlow.scanDenied()`), only when `CAMERA_SCAN` and `PLACEMENT` are enabled and the launcher is present; pass it and `placementFlow` to `AppShell`.
- `core/domain/src/main/resources/content/strings/en.json` -- add `msg.placement.suggest` "Start at {stage}?", `msg.placement.non-white-cross` "Start with a white cross for this course.", `msg.placement.solved` "Your cube is solved — scramble it and scan again, or start Stage 1.", `msg.placement.choose-title` "Choose a Stage", `msg.placement.stage-row` "{number}. {stage}", `btn.placement.start` "Start", `btn.placement.choose-another` "Choose another Stage", `btn.placement.faster-last-layer` "Faster last layer", `btn.placement.scan-again` "Scan again", `btn.placement.start-stage-1` "Start Stage 1".
- `build-logic/.../policy/FeatureFlagLevels.kt` + `FeatureFlagLevelsTest.kt` -- debug `PLACEMENT=GA` (QUICK_SCAN, CASE_RECOGNITION stay OFF); update the pinned string.
- `app/src/test/.../ui/shell/AppShellPlacementTest.kt` (new) + `WelcomeScreenTest.kt` -- see ACs.

**Acceptance Criteria:**
- Given `PLACEMENT` GA and a placement scan action, when the Learner taps `welcome:startFromCube` in `AppShell`, then the scan action is invoked (AppShell test).
- Given the scan returns `Accepted` for a cube with Stage 2's predicate met and Stage 3's unmet, when the Placement screen shows, then it reads "Start at First-layer corners?" with *Choose another Stage*; when *Start* is tapped, then Stages 1–2 derive `SKIPPED_BY_PLACEMENT`, Stage 3 is unlocked, `Onboarded(true)` is persisted, and Learn shows Stage 3's detail.
- Given an at-LL cube, when *Faster last layer* is tapped, then Stages 1–7 derive `SKIPPED_BY_PLACEMENT`, Learn shows the stage list with the *Faster last layer* rows visible.
- Given *Choose another Stage*, when the list shows, then it has rows for all 7 Course Stages and both 2-look Stages; tapping `stage-09-two-look-pll` skips Stages 1–7 and opens its detail.
- Given a solved cube, when *Start Stage 1* is tapped, then no progress event is written, onboarding is persisted and Stage 1's detail opens; when *Scan again* is tapped, the scan action runs again.
- Given `FellBackToEditor`, when the Learner accepts a cube in the editor, then the Placement screen shows that cube's outcome.
- Given `PLACEMENT` OFF (or no scan action), when Welcome renders, then `welcome:startFromCube` is absent and Welcome's onboarding behavior is unchanged; given BETA, the button shows the BETA label and camera note.

## Spec Change Log

## Review Triage Log

### 2026-11-17 — Review pass
- verdicts: 26 findings — high 0, medium 4, low 12, false 10, maybe-false 0
- findings:
  - `[low]` `[patch]` (blind) Welcome offers *Start from my cube* when the catalog failed to load, so the scan result is silently discarded — AppShell now requires `ready != null` before passing the placement scan action (also hides the button whenever `strings` is null).
  - `[low]` `[reject]` (blind) Activity recreation during an accepted write can persist skips without onboarding — recoverable: the Placement ViewModel keeps the result and re-accepting writes nothing new; same pattern as the shipped course skip check; fix needs retained acceptance state (added complexity), unlikely in everyday use.
  - `[low]` `[reject]` (blind) A late scan callback can repopulate Placement after the Learner left Welcome — needs a scan that returns after another Welcome action during the brief denial check; fix needs attempt tokens; unlikely.
  - `[false]` `[reject]` (blind) Empty Course makes `stages.first()` throw — shipped content always has 7 Stages and `I'm new` already relies on the same; unreachable.
  - `[low]` `[reject]` (blind) *Faster last layer* scrolls to the bottom rather than the section heading — reuses the existing Library → Learn `scrollToTwoLook` behavior; the section is the last block, so it is in view on supported viewports; cosmetic.
  - `[medium]` `[patch]` (blind) Shell tests bypass MainActivity, so mis-routing the placement result would pass — added `app/src/testDebug/.../PlacementFromScanRouteTest.kt` (Hilt + Robolectric): taps Welcome, returns an encoded result through the activity, asserts the Placement prompt and that Solve stays Home; also covers Cancelled.
  - `[low]` `[reject]` (blind) Fallback test accepts the editor's untouched solved default — editor wiring is a single `onAccept` lambda already covered by Solve's editor tests; entering 54 stickers in a test is disproportionate.
  - `[low]` `[reject]` (blind) No visible Back on the result/chooser — spec settles system Back (Material convention, EXPERIENCE.md "follow Android conventions"); adding UI the spec does not call for.
  - `[medium]` `[reject]` (edge) Recreation during acceptance (same root as the blind recreation finding) — see above; recoverable, low in practice; graded medium by the reviewer, verified low.
  - `[false]` `[reject]` (edge) Failed write still onboards — this is the spec's settled behavior (I/O matrix "Write fails", mirroring the course skip check); fix would edit the spec.
  - `[low]` `[patch]` (edge) Placement offered after catalog failure — same root as the blind catalog finding; patched by the `ready != null` gate.
  - `[low]` `[reject]` (edge) Start Stage 1 while *Scan again* is in its denial check opens the scan after navigation — same root as the late-callback finding; unlikely; fix adds cancellation plumbing.
  - `[false]` `[reject]` (edge) Empty Stage list throws — unreachable with shipped content (as above).
  - `[low]` `[patch]` (edge) BETA button shown without its label when `strings` is null — same root as the catalog finding; the `ready != null` gate guarantees strings.
  - `[false]` `[reject]` (edge) lesson-06-01 fixture does not show Stage 6 — the entry state is genuinely not LL-oriented, so Stage 5 is correct; the Stage 6 row is covered with lesson-06-02. Spec task wording was inaccurate; behavior matches the matrix.
  - `[medium]` `[patch]` (verification-gap) Welcome→MainActivity result routing untested — same fix as the blind route finding (`PlacementFromScanRouteTest`).
  - `[medium]` `[patch]` (verification-gap) Non-white-cross shell rendering untested — added `a non-white cross shows the white-cross guidance and still offers another Stage` to `AppShellPlacementTest`.
  - `[low]` `[patch]` (verification-gap) Faster last layer unlock target unchecked — the faster test now asserts the only `StageUnlocked` is the first 2-look Stage.
  - `[low]` `[patch]` (verification-gap) Cancel after *Scan again* untested — added `cancelling a rescan from the solved result returns to Welcome`.
  - `[false]` `[reject]` (intent) Recognize's link not wired — no Recognize screen exists yet (Story 11.3); "absent everywhere when OFF" holds trivially and 11.3 owns the link.
  - `[false]` `[reject]` (intent) "At the LL" could include an OLL-done cube — EXPERIENCE offers "*Faster last layer* or Stage 5", which is only coherent when Stage 5's predicate is unmet; an OLL-done cube is partly solved (Stage 6).
  - `[false]` `[reject]` (intent) Stage 2 is never suggested — settled by EXPERIENCE "Nothing solved: suggest Stage 1" and Flow 4 (solved cross skips Stages 1–2).
  - `[false]` `[reject]` (intent) Non-white-cross screen does not name Stage 1 — uses exactly the PO's copy; *Start* opens Stage 1.
  - `[false]` `[reject]` (intent) Extra `StageUnlocked` for the 2-look target — 2-look status is derived per Stage in isolation, so it changes nothing visible (Design Notes); Stages 1–7 are skipped as clarification 2 requires.
  - `[low]` `[reject]` (intent) Chooser numbers 2-look rows 8 and 9 while Learn numbers them 1 and 2 — cosmetic; the chooser is one flat list.
  - `[low]` `[defer]` (intent) Welcome's *Start from my cube* label is still `strings.xml`, not en.json — pre-existing Welcome chrome (all Welcome labels live there); moving Welcome copy is outside this change.

### 2026-11-17 — Review pass (follow-up)
- verdicts: 28 findings — high 0, medium 3, low 17, false 8, maybe-false 0
- findings:
  - `[false]` `[reject]` (blind) A failed write breaks the 2-look destinations — the I/O matrix's "Write fails" row settles this: navigation still happens and Learn shows the real derived status (an unresolvable 2-look `selectedStage` lands on the Learn list). The fix would edit the spec.
  - `[low]` `[reject]` (blind) Activity recreation during an accepted write splits the outcome — carried: low, reject. `welcome` is `rememberSaveable`, so a config change keeps Placement. After process death, Welcome is suppressed because the skips persisted, so the Learner lands on Learn with placement applied.
  - `[low]` `[reject]` (blind) A pending scan or denial check can outlive the screen that requested it — carried: low, reject (needs attempt tokens; unlikely).
  - `[false]` `[reject]` (blind) The skip-ahead failure's "Let's find where to start" is not wired to Placement — that text is Welcome's `welcome_abandoned` banner (`WelcomeScreen.kt` L97). It sits above the same *Start from my cube* button, so Placement is reachable from it.
  - `[low]` `[reject]` (blind) The chooser numbers 2-look Stages 8 and 9, while Learn numbers them 1 and 2 — carried: low, reject (cosmetic).
  - `[low]` `[reject]` (blind) The fallback test accepts the editor's untouched solved default — carried: low, reject (the editor's `onAccept` wiring is covered by Solve's editor tests).
  - `[low]` `[patch]` (blind) The catalog-ready gate has no regression test — added `a failed catalog hides the entry point even with Placement on and a scan action` to `AppShellPlacementTest`.
  - `[low]` `[reject]` (blind) The `suggest` "never accesses progress" test checks outcomes, not calls — `suggest` delegates to the pure `Placement.evaluate(state, stages)`, which has no repository parameter. A counting fake adds test machinery for a defect nobody would meet.
  - `[low]` `[patch]` (blind) Back transitions have no UI coverage — added `system Back steps from the chooser to the suggestion, then to Welcome, without writing`, which covers chooser → result → Welcome and editor → Welcome.
  - `[medium]` `[patch]` (blind) A recorded camera denial is never routed through MainActivity's Placement action — added `recordedDenialFromWelcomeOpensThePlacementEditorAndStartsNoScan` to `PlacementFromScanRouteTest`, with a configurable `cameraDenied` on the fake launcher.
  - `[low]` `[reject]` (edge) Recreation while the queued write runs — carried: low, reject (same as above).
  - `[low]` `[reject]` (edge) The Learner leaves during the asynchronous denial check — carried: low, reject (same root as the late callback).
  - `[false]` `[reject]` (edge) An empty Course makes `Placement.evaluate` throw — carried: false (shipped content always has 7 Stages).
  - `[false]` `[reject]` (edge) An empty Course makes the solved result screen throw — same refutation: unreachable with shipped content.
  - `[false]` `[reject]` (edge) The lesson-06-01 fixture does not exercise Stage 6 — carried: false (lesson-06-02 covers the Stage 6 row; 06-01's entry state is genuinely not LL-oriented).
  - `[low]` `[patch]` (verification-gap) Welcome's Placement action is unverified when the catalog fails — same fix as the blind catalog-gate finding.
  - `[medium]` `[patch]` (verification-gap) The Welcome route never exercises a recorded camera denial — same fix as the blind denial-route finding.
  - `[low]` `[reject]` (verification-gap) The manual fallback test cannot tell the entered cube from the solved default — carried: low, reject (same row as the blind fallback finding).
  - `[medium]` `[patch]` (intent) MainActivity-level fallback, denial and Hilt-backed writes are not exercised — the denial route is now covered (grouped with the denial patch). Accepted and Cancelled were already covered. Persistence is verified at the `SessionHost` surface with a real `SerialWriter`, per the device-free JVM scope.
  - `[low]` `[reject]` (intent) The fallback test only shows the Solved outcome from the editor — carried: low, reject.
  - `[low]` `[reject]` (intent) BETA rendering is tested only on `WelcomeScreen`, and no build ships `PLACEMENT=BETA` — AppShell passes `flags` straight through to `WelcomeScreen`, whose test covers the label, the note and TalkBack. No build can reach BETA today.
  - `[false]` `[reject]` (intent) Release and benchmark staying OFF is not asserted anew — `FeatureFlagLevelsTest.kt` L19 pins the release string with `PLACEMENT=OFF`, and the build-logic suite passes.
  - `[low]` `[reject]` (intent) The real *Scan again* relaunch through `scanInFlight` is untested — Placement uses the same `guidedScanAction` helper whose guard `ScanRouteTest` covers for Solve. *Scan again* only re-invokes that action.
  - `[false]` `[reject]` (intent) *Faster last layer* skipping Stages 5–7 comes from the spec, not the intent — settled by PO clarification 2 and EXPERIENCE (Design Notes). Same refutation as the first pass's 2-look rows.
  - `[false]` `[reject]` (intent) Stage 1 accepts bypass `PlaceFromScan` — the matrix makes Stage 1 the *I'm new* path. `PlaceFromScan` would write nothing for Stage 1, so the observable outcome is identical.
  - `[low]` `[reject]` (intent) The `PlaceFromScan.inMemory` factory is extra surface beyond `suggest` — `invoke` is unchanged and remains the only writer of `StageSkippedByPlacement`. The factory only backs `SessionHost`'s no-repository fallback, with no named harm.
  - `[low]` `[defer]` (intent) The Welcome label (and the TalkBack text built from it) comes from `strings.xml` — carried: defer (already in `deferred`; not added again).
  - `[low]` `[reject]` (intent) The write-fail exception path is untested — `SessionHost.placeFromScan` maps exceptions to `Failed`, mirroring `passSkipCheck`. The `Failed` return is tested at the shell level, and the gap would only matter on a storage exception.

## Design Notes

- Why Stage 1 for an unmet white cross: EXPERIENCE.md "Nothing solved → Stage 1" and Flow 4 (a solved cross skips Stages 1–2), so Stage 2 is never suggested on its own; Stage 1 opens the Course from the start.
- 2-look targets are passed as `Course + listOf(target)` so `PlaceFromScan` skips exactly Stages 1–7 (PO clarification 2); 2-look rows derive status per Stage in isolation (`StageProgress.status(stage, listOf(stage))`), so the extra `StageUnlocked` for the 2-look Stage changes nothing visible and the other 2-look Stage is never marked skipped.
- The write is awaited before navigation because a 2-look `selectedStage` resolves only after all Course Stages are finished.

## Verification

**Commands:**
- `export JAVA_HOME=/usr/lib/jvm/java-25-openjdk-amd64 ANDROID_HOME=$HOME/Android/Sdk; ./gradlew spotlessApply spotlessCheck build` -- expected: BUILD SUCCESSFUL
- `./gradlew -p build-logic test` -- expected: BUILD SUCCESSFUL

## Auto Run Result

**Summary:** Welcome's *Start from my cube* now launches the Guided Scan (debug `PLACEMENT=GA`). The accepted Full state, or the cube accepted in the Manual Cube Editor after *Enter colors instead* or a camera denial, is evaluated by the pure domain `Placement.evaluate` (exposed as `PlaceFromScan.suggest`). The Placement screen shows the EXPERIENCE.md outcome copy and options. On acceptance, the unchanged `PlaceFromScan` writer records skips and unlocks through `SessionHost.placeFromScan`. Onboarding is then persisted, and the Learner lands on the Stage detail, or on Learn scrolled to *Faster last layer*.

**Files changed:**
- `core/domain/.../tutor/Placement.kt` (new): `PlacementOutcome`, plus the Stage Predicate evaluation (last Lesson `completion`).
- `core/domain/.../predicate/WhiteLayer.kt`: `correctPieces` takes a center color, so a non-white cross can be detected.
- `core/application/.../usecase/PlaceFromScan.kt`: adds a read-only `suggest` and an `inMemory` factory; `invoke` is unchanged.
- `app/.../session/SessionHost.kt`: `placeFromScan`, with the serial writer and a progress refresh.
- `app/.../ui/placement/PlacementFlowViewModel.kt`, `PlacementScreen.kt` (new): the Placement flow, the outcome screen, the Stage chooser and the editor fallback.
- `app/.../ui/shell/AppShell.kt`: wires Welcome → Placement, handles acceptance and navigation, and offers the entry only when content is ready.
- `app/.../ui/welcome/WelcomeScreen.kt`: hides the button when there is no scan action; adds the BETA label, the note and the TalkBack text.
- `app/.../MainActivity.kt`: a second `ScanFlowContract` routed to Placement, with denial handling.
- `core/domain/.../strings/en.json`: Placement copy keys.
- `build-logic/.../FeatureFlagLevels.kt` and its test: debug `PLACEMENT=GA`.
- Tests: `PlacementTest`, `StageProgressUseCasesTest`, `AppShellPlacementTest` (12 cases), `WelcomeScreenTest`, `BuildConfigFeatureFlagsTest`, `FeatureFlagLevelsTest`, and `PlacementFromScanRouteTest` (new, testDebug).

**Review findings:** 26 findings. 7 were patched, in 4 entries: the catalog-ready gate (3 findings), the MainActivity route test (2), the non-white-cross shell test, the *Faster last layer* unlock-target assertion, and the cancelled-rescan test. 1 was deferred (Welcome labels in strings.xml). 18 were rejected, each with its reason in the triage log: recreation or late-callback races, the empty Course, the scroll anchor, the editor-default test, the visible Back, the failed-write behavior settled by the spec, the lesson-06-01 fixture, the Recognize link, the "at the LL" reading, the Stage 2 suggestion, the non-white-cross copy, the extra 2-look unlock, and the chooser numbering.

**Follow-up review recommendation:** true. Two medium entries were patched (the route test and the non-white-cross shell test), and a third patch, the catalog-ready gate, is untested. No reviewer has re-checked the post-review patches: the `ready != null` gate in AppShell and the new Hilt route test. Patched counts: high 0, medium 2, low 2 (entries).

**Verification:**
- `./gradlew spotlessCheck build --max-workers=4`: BUILD SUCCESSFUL, before and after the patches.
- `./gradlew -p build-logic test`: BUILD SUCCESSFUL.
- Targeted: `PlacementFromScanRouteTest` (2 tests) and `AppShellPlacementTest` (12 tests) both pass with 0 failures.

**Residual risks:**
- If the Activity is recreated while an accept write is in flight, the skips can be persisted without onboarding. This is recoverable by accepting again.
- A scan callback that arrives after the Learner has left Welcome can leave a stale Placement phase.
- Physical camera capture was not exercised; that needs a device session.

### Follow-up review pass (2026-11-17)

**Summary:** A fresh four-layer review of the full Story 11.2 diff ran (three layers on Codex, plus the Claude intent-alignment layer). It found no production defect. Three test gaps were closed: the catalog-ready gate, system Back through the Placement phases, and the MainActivity camera-denial route.

**Files changed:**
- `app/src/test/kotlin/dev/cubentia/app/ui/shell/AppShellPlacementTest.kt`: adds a failed-catalog entry-point test and a system-Back test. `show()` gains `failedCatalog` and captures the back dispatcher.
- `app/src/testDebug/kotlin/dev/cubentia/app/PlacementFromScanRouteTest.kt`: adds a recorded-denial route test, with a configurable `cameraDenied` on the fake launcher.

**Review findings:** 28 findings, triaged as follows:
- **Patched:** 7 findings in 3 entries: the catalog gate (2), the Back transitions (1) and the denial route (3, plus 1 intent row grouped with it).
- **Deferred:** 0 new. The Welcome-label row was carried as an existing defer.
- **Rejected:** 21, each with its reason in the triage log:
  - Carried recreation and late-callback races (4).
  - Carried fallback-default test (3).
  - Carried chooser numbering, carried empty Course (2), and carried lesson-06-01.
  - Failed-write 2-look landing (settled by the spec), and the skip-ahead link (already reachable).
  - The `suggest` call-count test, BETA through AppShell, release flags (already pinned), the *Scan again* guard, the *Faster last layer* semantics, the Stage 1 bypass, the `inMemory` factory, and the exception path.

**Follow-up review recommendation:** false. This is a follow-up pass and it patched no high. Patched entries by verdict: high 0, medium 1, low 2.

**Verification:**
- `./gradlew spotlessApply :app:testDebugUnitTest --tests AppShellPlacementTest --tests PlacementFromScanRouteTest`: passed. `AppShellPlacementTest` ran 14 tests and `PlacementFromScanRouteTest` ran 3, with 0 failures.
- `./gradlew spotlessCheck build --max-workers=4`: BUILD SUCCESSFUL.
- `./gradlew -p build-logic test`: BUILD SUCCESSFUL.

**Residual risks:** These are unchanged from the first pass: a recreation or process death during the accept write, a stale late scan callback, and the physical camera, which is untested pending a device session.
