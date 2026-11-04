---
title: 'Guided recovery by the shortest correction'
type: 'feature'
created: '2026-11-03'
status: 'done'
baseline_revision: e62d8d75cff4a6521550d7e294dee051c032a89b
review_loop_iteration: 0
followup_review_recommended: false
context:
  - '{project-root}/AGENTS.md'
  - '{project-root}/_bmad-output/implementation-artifacts/epic-4-context.md'
warnings: ['oversized']
deferred: []
---

<intent-contract>

## Intent

**Problem:** After a diagnosed mistake, the recovery prompt shows *Guide me back* but always disables it. `TutorDecision.recovery` is always `Unavailable`, and there is no `RecoveryPlanner` or `PlanRecovery`. So the Learner cannot get the shortest correction that puts their solved pieces back (FR-12, UX-DR11, AD-3, AD-5, AD-8).

**Approach:** Add a pure, bounded IDA* `RecoveryPlanner` in `:core:domain/tutor`. It searches over the configured face turns for a state where the Objective's preserved pieces hold again. A `PlanRecovery` use case runs it as an Effect, with a 3 s timeout on `Dispatchers.Default`. A new `SessionEvent.GuidedRecoveryRequested` issues `Effect.PlanRecovery`. Its result comes back as `RecoveryPlanned` or `RecoveryPlanFailed`, echoing `effectId`/`revision`. A stale result is discarded and the plan is offered again. The decision exposes `recovery: Pending(effectId) | Ready(moves) | Unavailable`, and a new inline `GuidedRecoveryLine` renders the pending, plan and no-plan copy, with the preserved pieces highlighted.

## Boundaries & Constraints

**Always:**
- **Goal.** `RecoveryGoal(keep: List<Predicate>, pieces: Set<Piece>)` comes from `RecoveryPlanner.goal(lesson, references)`. The references are the Checkpoint's state and the state before the diagnosed Move (`history.undo().state`).
  - For each reference `r`: `keep` gets `lesson.preserve` and the `done` of every step with `Tutor.stepDone(lesson, i, r)`; `pieces` gets the union of those predicates' `preservedPieces(r)`.
  - `isMet(s)` means `pieces ⊆ keep.flatMap { it.preservedPieces(s) }`.
- **Planner.** `RecoveryPlanner.plan(from, goal, maxDepth, moveSet, shouldContinue)` returns `RecoveryPlan.Found(moves) | NotFound | Stopped`.
  - It is IDA* with `h = max(ceil(missingCorners/4), ceil(missingEdges/4))`, since one face turn moves 4 corners and 4 edges.
  - It never puts two turns of the same face in a row, and it tries Moves in `moveSet` order, so the result is deterministic.
  - It checks `shouldContinue()` every 1024 nodes and returns `Stopped` when that is false.
  - Over piece-local predicates, the result is shortest; a goal already met gives `Found([])`. It never calls the Solver.
- **Config.** `TutorConfig` gains a required `recoveryMaxDepth: Int`, validated to `1..8` (`RECOVERY_MAX_DEPTH_FIELD`, `OUT_OF_RANGE`). The shipped value is `5`; the schema and the test fixture JSON add it too. The planner's `moveSet` is `TutorConfig.moves`.
- **Session.** It gets a new field, `guidedRecovery: GuidedRecovery`: `None | Planning(effect: Effect.PlanRecovery) | Planned(moves, pieces, text) | NotFound`. IDLE and new sessions start at `None`.
  - **Derived flag.** `SessionState.canGuideBack` is true when all of these hold:
    - it is a Lesson;
    - `decision.diagnosis` offers `GUIDE_ME_BACK`;
    - `guidedRecovery == None`;
    - `checkpoint != null`;
    - the goal is not met on `virtual`.
  - **`GuidedRecoveryRequested`.** When `canGuideBack`, it issues `Effect.PlanRecovery(nextEffectId, revision, virtual, goal, recoveryMaxDepth, moves)` and sets `Planning`. `nextEffectId` goes up by 1; `revision` does not change. Otherwise nothing changes.
  - **Current result.** A result is current when `guidedRecovery` is `Planning` with the same `effectId` and `revision`, and that `revision == state.revision`.
    - A current `RecoveryPlanned(moves)` is re-verified: if `virtual.apply(moves)` meets the goal, the state becomes `Planned(moves, goal.pieces, RecoveryPlanner.describe(moves, goal.pieces))`, else `NotFound`.
    - A current `RecoveryPlanFailed` becomes `NotFound`.
  - **Stale result.** A result that is not current sets `lastDiscarded`. When it was overtaken (`event.revision < state.revision`), it re-offers: if `canGuideBack` now holds, it issues a fresh `PlanRecovery` at the current revision (the `askReset` precedent).
  - **Clearing.** The three recovery events never clear `lastMoveFeedback`/`lastDiagnosis`. The generic non-commit clear in `reduce` also sets `guidedRecovery = None`, and so does every `withHistory`. The AD-3 cancel follows from this.
  - **Following a plan.** A `MoveCommitted` equal to the first Move of a `Planned` plan keeps `Planned` with the remaining Moves. When nothing remains, it is `None`.
- **Decision.** `Assess.decide` gains a `recovery: Recovery` parameter, passed through to `TutorDecision.recovery`. The session maps `Planning` to `Pending(effectId)`, `Planned` to `Ready(moves)`, and anything else to `Unavailable`. `TutorDecision`'s field set is unchanged.
- **Copy.** It goes in `en.json`, keyed:
  - `msg.recovery.pending` "Finding the shortest way back…";
  - `msg.recovery.plan` "{count, plural, one {# move} other {# moves}} — {layer, select, cross {your cross stays} first_layer {your first layer stays} other {your solved pieces stay}}" (`layer` comes from `DiagnosisRules.layerId`, made internal);
  - `msg.recovery.not-found` "I can't find a short way back. Undo, or start this step again."
- **Screen.**
  - *Guide me back* is enabled exactly when `session.canGuideBack` holds, and tapping it sends `GuidedRecoveryRequested`.
  - While `guidedRecovery` is `Planning`, a runner keyed on that effect calls `PlanRecovery` and dispatches the result. It is cancelled when the effect is no longer the current one.
  - `GuidedRecoveryLine` sits inline in the step card, after the recovery prompt, and shows whenever `guidedRecovery != None`, even without a Diagnosis. It is a polite live region:
    - pending text, tagged `lesson:guidedRecovery:pending`;
    - the plan text plus `NotationChip`s, tagged `lesson:guidedRecovery:plan`;
    - the no-plan text, tagged `lesson:guidedRecovery:notFound`.
  - While a plan is `Ready`, the net emphasis is `NetEmphasis(ALL_PIECES, highlighted = planned pieces)`, which takes precedence over the disturbed outline. Stickers are never recolored, and there is no dialog and no red.

**Never:**
- calling `CubeSolver` from the planner or `PlanRecovery`, or running the search inside `reduce`;
- adding fields to `TutorDecision` or variants to `Recovery`;
- auto-applying the plan's Moves to the cube (the Learner turns them);
- Stage unlocking, persistence or adaptive assistance (4.6, Epic 6);
- editing planning artifacts, `_bmad/` or `.claude/skills/`.

## I/O & Edge-Case Matrix

| Scenario | Input / State | Expected Output / Behavior | Error Handling |
|----------|--------------|---------------------------|----------------|
| Plan ready | shipped `lesson-03-01`, *Test me*, `R`, then `GuidedRecoveryRequested` | one `Effect.PlanRecovery` at the current revision; `decision.recovery == Pending(id)`; the Diagnosis is kept; running `PlanRecovery` + dispatching gives `Ready([R'])`, `Planned.pieces` = the four cross edges, and the text args are `count=1` and `layer=cross` | — |
| Multi-move plan | the goal from entry `lesson-03-01`, observed `entry·R U F` | `Found` of length ≤ 3 that meets the goal, and shortest: brute force finds no shorter sequence that meets it | — |
| No plan | the same with `recoveryMaxDepth = 1` | `NotFound`; in the session, `RecoveryPlanFailed` → `NotFound`, `decision.recovery == Unavailable`, not-found copy | — |
| Timeout | `PlanRecovery` with a planner that spins until `shouldContinue` is false, deadline 50 ms | returns no plan (→ `RecoveryPlanFailed`) | timed out, never thrown |
| Stale and re-offered | Plan ready request, then commit `F` (disturbs another cross edge), then the old `RecoveryPlanned` | `lastDiscarded = (id, n, n+1)`; a fresh `PlanRecovery(id+1, n+1)` is issued and `Planning` | discarded |
| Stale, nothing to offer | request, then `Undo`, then the old result | discarded, `None`, no effect | — |
| Not offerable | no Diagnosis, or a REORIENT Diagnosis, or already `Planning` | `GuidedRecoveryRequested` changes nothing | ignored |
| Follow plan | `Planned([F', R'])`, commit `F'` | `Planned([R'])`; commit `R'` → `None` | — |
| Recovery paths | each `DiagnosisScenarioTest` category fixture | applying its Recovery Path gives a state where `lesson.preserve` holds: UNDO → `expected`; GUIDED_RECOVERY → the planner's plan; RETRY → entry (Checkpoint); REORIENT → `Tutor.reorient` rotations; RE_ENTER → the valid state re-entered through `CubeValidator` | — |
| Screen | `lesson-03-01` *Test me*, `R`, tap *Guide me back* | the plan node reads "1 move — your cross stays" with chip `R'`; highlighted stickers = the cross edges' stickers; *Guide me back* disabled; pressing `R'` removes the line; no dialog | — |
| Screen, no plan | the same, with an injected `PlanRecovery` whose planner returns `NotFound` | the not-found copy is shown | — |

</intent-contract>

## Code Map

- `core/domain/.../tutor/RecoveryPlanner.kt` (new) -- `RecoveryGoal`, `RecoveryPlan`, the `RecoveryPlanner` object (`goal`, `plan`, `describe`, the `PENDING`/`NOT_FOUND` `Text`s). The redundancy rule matches `CandidateEnumerator.redundant`. Missing pieces are split into corners and edges by `Piece.colors.size` (3 or 2).
- `core/domain/.../tutor/DiagnosisRules.kt:175` -- make `layerId` internal for `describe`.
- `core/domain/.../tutor/Assess.kt:152-190` -- add `recovery: Recovery` to `decide` (replacing the hard-coded `Unavailable` at :185); update the KDoc at :143.
- `core/domain/.../tutor/ErrorCategory.kt` -- KDoc only: "(enabled by Story 4.5)" becomes present tense.
- `core/domain/.../content/Model.kt:74`, `ContentValidator.kt:19,49-75` -- the `recoveryMaxDepth` field, its range and the field constant.
- `core/domain/src/main/resources/content/{tutor-config.json, schema/tutor-config.schema.json, strings/en.json:24-32}`, plus `core/domain/src/test/resources/content-fixtures/valid/tutor-config.json`.
- `TutorConfig(` constructions to update:
  - `ContentValidatorTest.kt:182-216`, with a new out-of-range case;
  - `ContentLoaderTest.kt:321`;
  - `ShippedContentTest.kt:215`;
  - `TutorFixtures.kt:33`;
  - any test calling `Assess.decide`.
- `core/application/.../session/Effect.kt` -- `Effect.PlanRecovery(effectId, revision, state, goal, maxDepth, moveSet)`.
- `core/application/.../session/SessionEvent.kt` -- `GuidedRecoveryRequested(at)`, `RecoveryPlanned(effectId, revision, moves, at)`, `RecoveryPlanFailed(effectId, revision, at)`.
- `core/application/.../session/SessionState.kt` -- the `guidedRecovery` field (constructor, IDLE :128) and `GuidedRecovery` (new sealed type in this package, with a `text: Text?`); `canGuideBack`; `decision` (:93) passes the mapped `Recovery`.
- `core/application/.../session/Reduce.kt`:
  - the generic clear :32-36 (exempt the three recovery events; also clear `guidedRecovery`);
  - dispatch :43-57;
  - `move` :150 (following a plan);
  - `withHistory` :291 (`None`);
  - `start`/`startPlayback` set `None`;
  - the new `guideBack`/`planned`/`planFailed`, modelled on `confirmed` :229 and `isCurrent`/`discarded` :214-227.
- `core/application/.../usecase/PlanRecovery.kt` (new) -- modelled on `SolveCube.kt`: `withTimeoutOrNull(3_000)` + `withContext(Dispatchers.Default)`, `shouldContinue = { context.isActive }`, exceptions (except cancellation) → no plan. `suspend operator fun invoke(effect): List<Move>?`, plus `fun Effect.PlanRecovery.result(moves: List<Move>?, at: Instant): SessionEvent`. The constructor takes an injectable planner function and deadline.
- `core/application/.../session/TRANSITIONS.md` -- `guidedRecovery` joins the `tutor` token list; rows `GuidedRecoveryRequested | tutor | no | no`, `RecoveryPlanned | tutor | no | no`, `RecoveryPlanFailed | tutor | no | no`; prose for Guided recovery, stale handling and re-offer.
- `core/application/src/test/.../session/TransitionsTableTest.kt` -- `tutor()` :194 adds `guidedRecovery`; scenarios and `samples` :165 for the 3 events; the count :214 goes from 13 to 16. `Lessons.kt` gets `guideBack`; `DiagnosisSessionScenarioTest.testMe` is the shipped-Lesson helper.
- `app/.../ui/lesson/LessonScreen.kt`:
  - `recoveryActions` :417-433 (`enabled = session.canGuideBack`);
  - `onRecovery` :229-238 (GUIDE_ME_BACK → `send(SessionEvent::GuidedRecoveryRequested)`);
  - the emphasis `when` :267;
  - `stepCard` :383 (new content field);
  - a `planRecovery: PlanRecovery = PlanRecovery()` parameter on `LessonScreen` :100, passed to the runner.
- `app/.../ui/lesson/LessonPanel.kt:62-80,263-290` -- `StepCardContent.guidedRecovery: GuidedRecoveryContent?`, rendered after `RecoveryPrompt`, outside the diagnosis branch.
- `app/.../ui/lesson/GuidedRecoveryLine.kt` (new) -- `GuidedRecoveryContent(kind, text, moves)`, `GuidedRecoveryLine`, and a `GuidedRecoveryRunner(host, planRecovery)` composable (the `ResetConfirmation.kt` pattern: `LaunchedEffect(effect)`).
- `app/src/test/.../ui/lesson/LessonScreenTest.kt:115-124` (`open`; add the `planRecovery` pass-through), the existing recovery-prompt test, `stickersWith`; `rule.waitUntil` for the Default-dispatcher result. `app/src/test/.../content/DiagnosisCopyTest.kt` pins the three new strings (plural 1/3, each layer).

## Tasks & Acceptance

**Execution:**
- [x] `core/domain` config files and tests -- `recoveryMaxDepth` -- the search bound is data (AR-22).
- [x] `core/domain/.../tutor/RecoveryPlanner.kt` + `RecoveryPlannerTest.kt` (new) -- the goal, IDA*, describe; tests for the Multi-move, No plan and Stopped rows and a goal already met.
- [x] `core/domain/src/test/.../tutor/RecoveryPathScenarioTest.kt` (new) -- the Recovery paths row for all 7 categories -- AC 4.
- [x] `core/domain/.../tutor/{Assess,DiagnosisRules,ErrorCategory}.kt` -- the `recovery` pass-through, `layerId` internal, KDoc.
- [x] `core/application/.../session/{Effect,SessionEvent,SessionState,Reduce}.kt` and `TRANSITIONS.md` -- per Boundaries.
- [x] `core/application/.../usecase/PlanRecovery.kt` + `PlanRecoveryTest.kt` (new) -- Found, NotFound, Timeout.
- [x] `core/application/src/test/.../session/GuidedRecoverySessionTest.kt` (new) and `TransitionsTableTest.kt` -- every non-screen session row (Plan ready end to end through `PlanRecovery`, stale both ways, Not offerable, Follow plan, No plan) -- AC 1-3.
- [x] `en.json` + `DiagnosisCopyTest` -- the three strings.
- [x] `app/.../ui/lesson/{GuidedRecoveryLine,LessonPanel,LessonScreen}.kt` + `LessonScreenTest.kt` -- the screen rows -- AC 1-2.

**Acceptance Criteria:**
- Given a COMPLETED_LAYER_DISTURBED Diagnosis in *Test me*, when *Guide me back* is tapped, then "Finding the shortest way back…" shows while the plan is pending, and the result arrives as `recovery: Ready(moves)` with the plan copy ("1 move — your cross stays") and the preserved pieces highlighted.
- Given no plan within `recoveryMaxDepth`, or a timeout, when the effect completes, then "I can't find a short way back. Undo, or start this step again." shows.
- Given the Learner commits a Move while the plan is pending, when the result arrives, then it is recorded as `EffectResultDiscarded`, never applied, and re-offered.
- Given the scenario fixture of each Error Category, when its Recovery Path is applied, then the state satisfies the Lesson's `preserve` predicate.

## Spec Change Log

## Review Triage Log

### 2026-11-03 — Review pass
- verdicts: 21 findings — high 0, medium 0, low 19, false 1, maybe-false 1
- findings:
  - `[low]` `[reject]` BH-1: same-face pruning loses corrections when `moveSet` lacks a face's other turns (e.g. only `R`) — the shipped config has all 18 face turns, so a Learner never meets this. The fix is a guard on the move set, and `CandidateEnumerator` already prunes the same way.
  - `[low]` `[reject]` BH-2: WRONG_PIECE_ORIENTATION offers *Guide me back* but leaves it disabled when nothing preserved is broken — by design (Design Notes): there is nothing to restore, and *Undo*/*Try again* stay available. Changing it would mean editing the spec's goal definition.
  - `[low]` `[reject]` BH-3: on screen the commit cancels the runner, so the reducer's stale-result path rarely runs — by design: the reducer covers the race, and the screen re-offers by enabling the button again (Design Notes). The screen side is now tested (see VG-1).
  - `[maybe-false]` `[reject]` BH-4: two `StateReached` predicates with different targets for one piece could count a piece as restored by the wrong one — no shipped Lesson combines overlapping `StateReached` targets. Settled by finding such content; if true it would only be low.
  - `[low]` `[reject]` BH-5: the heuristic may be inadmissible for `anyOf` branch switches — every plan is re-verified, so it is always valid, just possibly not the shortest; the spec limits the shortest guarantee to piece-local predicates.
  - `[low]` `[patch]` BH-6: the plan copy reused `DiagnosisRules.layerId`, so a goal of one edge read "your cross stays" — patched: `describe` now says `cross` only for exactly the 4 white edges and `first_layer` only for exactly the 8 white-layer pieces, else `other`. `layerId` is private again, and the single-edge and cross-plus-corner cases are tested.
  - `[low]` `[reject]` BH-7: events that change nothing (a repeated mode choice, a `StepAdvanced` that cannot apply) still clear the plan — the screen never sends those (the switcher filters the same mode; Next and Hint are disabled when they cannot apply). It matches the existing Diagnosis clear.
  - `[low]` `[reject]` BH-8: an intermediate plan Move could be classified Harmful — in virtual *Test me* most plans are one Move. Recovery-aware classification would add branches for a rare case.
  - `[low]` `[reject]` BH-9: the result check does not enforce `maxDepth` or `moveSet` — the result comes from our own planner, which honours both, and the goal is re-checked anyway.
  - `[low]` `[patch]` BH-10: the AC 4 UNDO and RETRY paths were only simulated in the domain — patched: `GuidedRecoverySessionTest` now runs real `undo` (lesson-02-05 WRONG_PIECE) and `retry` (lesson-03-01 after R U F) and checks `lesson.preserve` on `virtual`.
  - `[low]` `[reject]` ECH-1: same claim as BH-1 — same reason.
  - `[low]` `[patch]` VG-1: cancelling a pending search from the screen was not verified — patched: a new `LessonScreenTest` case commits F while a spinning planner is pending, and asserts the planner is told to stop within 1 s, the pending line goes away and *Guide me back* is enabled again.
  - `[low]` `[reject]` VG-2 (other finding): same claim as BH-1 — same reason.
  - `[low]` `[reject]` IA-1: no screen test produces a real three-Move plan — the plan count is covered by the session test (two Moves) and the copy test (one and three).
  - `[low]` `[reject]` IA-2: the timeout is tested only with an injected planner — that is the deterministic way to test a 3 s deadline, and the constant is asserted.
  - `[low]` `[reject]` IA-3: the screen's no-plan case injects `NotFound` — real depth exhaustion is tested in the domain and use case, and the screen only renders the state.
  - `[low]` `[patch]` IA-4: no screen test committed a Move while a plan was pending — grouped with VG-1, same patch.
  - `[low]` `[reject]` IA-5: "re-offered" ships two ways (automatic in the reducer, the enabled button on screen) — consistent and documented in Design Notes and TRANSITIONS.md.
  - `[low]` `[reject]` IA-6: WRONG_PIECE_ORIENTATION Guided recovery is not tested end to end — the button is disabled there by design (BH-2), and the planner path is covered in the domain.
  - `[low]` `[patch]` IA-7: AC 4 is checked mostly by simulation — grouped with BH-10, same patch. Note: UNKNOWN_DISCREPANCY cannot be reached through one `MoveCommitted` (a candidate always explains one turn), so its RETRY path is exercised from a disturbed state.
  - `[false]` `[reject]` IA-8: the story's IDA* `[ASSUMPTION]` marker stays in the planning artifacts — editing planning artifacts is forbidden here. The decision is recorded in this spec, and the marker itself causes no defect.

## Design Notes

**Which preserved pieces.** "The current Objective's preserved pieces" are the union of those at the Checkpoint (the last Objective boundary, so earlier mistakes are repaired too) and those just before the diagnosed Move (the Diagnosis's `disturbed` is always a subset of these). In virtual *Test me* a single bad Move usually gets a 1-Move plan, its inverse. After several bad Moves the plan restores the whole cross, so multi-Move plans ("3 moves — your cross stays") happen. A Diagnosis with nothing to restore (a twisted piece) leaves *Guide me back* disabled, because `canGuideBack` requires the goal to be unmet.

**Re-offer.** This follows the `askReset` precedent: a stale, overtaken result re-issues the plan when *Guide me back* still applies. On screen, the runner is cancelled as soon as `Planning` ends (AD-3), and the button is enabled again, which is also a re-offer.

## Verification

**Commands:**
- `export JAVA_HOME=/usr/lib/jvm/java-25-openjdk-amd64 ANDROID_HOME=$HOME/Android/Sdk && ./gradlew spotlessApply && ./gradlew spotlessCheck build` -- expected: BUILD SUCCESSFUL, with the new tests passing and Kover ≥ 80% on `:core:*`.

## Auto Run Result

- **Summary:** Guided recovery (FR-12, UX-DR11).
  - **Planner:** `RecoveryPlanner` is a bounded IDA* over the configured face turns. It finds the shortest correction that restores the preserved pieces at the Checkpoint and just before the diagnosed Move.
  - **Running it:** `PlanRecovery` runs the search as `Effect.PlanRecovery` with a 3 s timeout on `Dispatchers.Default`.
  - **Session:** it gets `GuidedRecoveryRequested`, `RecoveryPlanned`, `RecoveryPlanFailed` and the `guidedRecovery` state. A stale result is discarded and re-offered, and a plan can be followed Move by Move.
  - **Decision:** `TutorDecision.recovery` now carries `Pending` and `Ready`.
  - **Screen:** *Guide me back* is enabled, and a new inline line shows the pending, plan and no-plan copy, with the preserved pieces outlined.
- **Files:**
  - `core/domain`:
    - `tutor/RecoveryPlanner.kt` (new): the goal, IDA* and the plan copy;
    - `tutor/Assess.kt`: the `recovery` pass-through;
    - `tutor/ErrorCategory.kt`: KDoc;
    - `cube/Cubies.kt`: `PER_FACE_TURN`;
    - `content/{Model,ContentValidator}.kt`: `recoveryMaxDepth`;
    - `tutor-config.json`, its schema and the test fixture: `recoveryMaxDepth` 5;
    - `en.json`: 3 strings.
  - `core/application`:
    - `session/{Effect,SessionEvent,SessionState,Reduce}.kt` and `GuidedRecovery.kt` (new): the effect, the events, the state and the reducer;
    - `TRANSITIONS.md`: 3 rows and prose;
    - `usecase/PlanRecovery.kt` (new): the timed effect runner.
  - `app`:
    - `ui/lesson/GuidedRecoveryLine.kt` (new): the line and the runner;
    - `LessonPanel.kt`, `LessonScreen.kt`: wiring, enabling *Guide me back*, emphasis, and the `planRecovery` injection.
  - Tests:
    - new: `RecoveryPlannerTest`, `RecoveryPathScenarioTest`, `PlanRecoveryTest`, `GuidedRecoverySessionTest`;
    - updated: `TransitionsTableTest`, `LessonScreenTest`, `DiagnosisCopyTest`, `AssessTest`, and the content tests and fixtures.
- **Review:** 21 findings.
  - Patched: 3 low groups — BH-6; VG-1 with IA-4; BH-10 with IA-7.
  - Deferred: none.
  - Rejected: 16 low, 1 maybe-false and 1 false, with reasons in the triage log.
- **Follow-up review recommended:** false. Patched counts by entry verdict: high 0, medium 0, low 3.
- **Verification:** `./gradlew spotlessApply && ./gradlew spotlessCheck build` gave BUILD SUCCESSFUL before review and again after the patches. Every I/O matrix row has a passing test.
- **Residual risks:**
  - The worst case at depth 5 relies on the 3 s timeout (it is reported as the no-plan copy).
  - Move-set subsets without a face's other turns would lose corrections to the same-face pruning.
  - Following a multi-Move plan may still show a Diagnosis on an intermediate Move.
  - UNKNOWN_DISCREPANCY cannot be reached through one virtual commit.

