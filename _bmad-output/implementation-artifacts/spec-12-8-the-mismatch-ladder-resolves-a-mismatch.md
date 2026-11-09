---
title: 'The mismatch ladder resolves a mismatch'
type: 'bugfix'
created: '2026-11-08'
baseline_revision: 'd89b6b109647a09671efbfe59ba1cfc94cd96617'
status: 'done'
review_loop_iteration: 0
followup_review_recommended: false
context:
  - '{project-root}/_bmad-output/implementation-artifacts/epic-12-context.md'
  - '{project-root}/_bmad-output/implementation-artifacts/epic-8-retro-2026-11-08.md'
warnings: ['oversized']
deferred:
  - summary: >-
      A correct on-path Move entered on rung 1 is told it was a wrong move.
    evidence: |-
      diagnosePhysicalMove falls back to WRONG_MOVE_OR_DIRECTION when findings returns null and never checks the path; the fallback predates this story (Story 8.3).
    location: >-
      core/domain/src/main/kotlin/dev/cubentia/domain/tutor/Assess.kt diagnosePhysicalMove
    severity: low
  - summary: >-
      Rung 1 has no Move keys in Show me.
    evidence: |-
      The Show me branch of LessonBody renders DemonstrationPlayer instead of LearnerStage, so only Next question, the dismiss and re-entry are reachable. Predates this story.
    location: >-
      app/src/main/kotlin/dev/cubentia/app/ui/lesson/LessonScreen.kt stage branch for SHOW_ME
    severity: low
---

<intent-contract>

## Intent

**Problem:** In `PHYSICAL_MISMATCH` an entered move only sets `lastDiagnosis`, nothing dismisses a false alarm, `PHYSICAL_VERIFIED` is never assigned, and the first physical confirm wipes a Planned Guided recovery, so a Learner's answers never fix the tracked state (Epic 8 retro item 4).

**Approach:** Implement the PO's settled contract in the reducer: a rung-1 move is a normal physical commit carrying its Diagnosis, `PhysicalConfirmed(null)` is *It matches after all*, an accepted editor entry lands in `PHYSICAL_VERIFIED`, and physical confirms follow a Planned recovery exactly as virtual commits do; the UI wires those and `TRANSITIONS.md` states them.

## Boundaries & Constraints

**Always:**
- All changes go through `reduce`; no new `SessionEvent` and no new persisted field. `PhysicalConfirmed(null)` in `PHYSICAL_MISMATCH` is the dismiss.
- A rung-1 move uses the same commit as `PHYSICAL_PREDICTED`: `expected := expected.apply(move)`, `history.commit(move)` (undoable), `observed := null`, next revision, Notice and Checkpoint boundary rules, adaptive counts, `Presentation.Animate`.
- The Diagnosis is `Assess.diagnosePhysicalMove` with `expected` as the base, always (never `history.undo().state`), in every Teaching Mode. `lastMoveFeedback` stays null for a rung-1 move.
- In physical mode `Assess.decide` takes Certainty from `lastDiagnosis` whenever one exists, also when the card hides the Diagnosis because the step is done (`advance`), so a commit that meets the Objective is never a false `VERIFIED`.
- Following a Planned recovery follows the virtual rule: the plan's next Move keeps the plan with the Moves left (none left: `None`); any other Move ends it. `PhysicalConfirmed(null)` never clears a Diagnosis or a Guided recovery.
- *Done* confirms the Planned recovery's next Move while one is shown, else the step's Move as today.
- Rung 2 stays presentation-only; every answer goes to rung 3 and no `PhysicalObservation.Partial` is constructed anywhere in `:app` or `:core:application`.
- The new label is a content key in `en.json` (`msg.physical.mismatch.matches-after-all`), resolved through `ContentStrings`.

**Never:**
- Do not move existing `R.string.physical_mismatch_*` copy (Story 12.9).
- Do not change the frame label, add camera or scan behavior, or edit `prd.md`, the spine, `_bmad/` or `.claude/skills/`.
- No numeric certainty; no `VERIFIED` on a predicted cube, with or without a Diagnosis (AD-5); never a second history entry for a rung-1 move that replaces the last confirmed move.

## I/O & Edge-Case Matrix

| Scenario | Input / State | Expected Output / Behavior | Error Handling |
|----------|--------------|---------------------------|----------------|
| Rung 1 move | `PHYSICAL_MISMATCH`, `PhysicalConfirmed(R)` | `expected`/`virtual` advance by R, `PHYSICAL_PREDICTED`, `observed` null, undoable, revision +1, `decision.certainty == lastDiagnosis.certainty` | — |
| Rung 1 with prior confirms | history has a confirmed move | base is `expected`; the prior move stays in history | — |
| Rung 1 meets Objective | entered move completes the Lesson | Certainty stays the diagnosed one, not `confirm`'s VERIFIED; solve still needs `PhysicalSolveConfirmed` | — |
| Rung 1 unexplained | move outside the enumerator | Certainty `UNKNOWN`, Expected still updates | — |
| False alarm | `PHYSICAL_MISMATCH`, `PhysicalConfirmed(null)` | `PHYSICAL_PREDICTED`, `expected` and revision unchanged | — |
| Rung 2 answer | any corner or *Next question* | rung 3 shown; session state unchanged; `observed` stays null | — |
| Editor from physical | AWAITING, PREDICTED, VERIFIED or MISMATCH + `EditorEntered` | `PHYSICAL_VERIFIED`, `observed = Full`, offer shown as today | VIRTUAL: ignored |
| Next confirm after editor | VERIFIED + `PhysicalConfirmed(move)` | `PHYSICAL_PREDICTED`, `observed` null | — |
| Follow plan | Planned, confirm of plan's next Move | plan keeps the Moves left; last Move: `None` | — |
| Leave plan | Planned, other Move | `None` | — |
| Idle confirm | Planned or Diagnosis shown, `PhysicalConfirmed(null)` | both kept | — |

</intent-contract>

## Code Map

- `core/application/.../session/Reduce.kt` -- `confirmPhysical` (l.547-607): merge the MISMATCH branch into the PREDICTED/VERIFIED commit, null move in MISMATCH dismisses; `committed` (l.~358): extract plan following so both paths share it; `applied` `keeps` (l.~120): any `PhysicalConfirmed`; `enterEditor` (l.655): `PHYSICAL_VERIFIED`.
- `core/domain/.../tutor/Assess.kt` -- `decide` l.217-222: physical Certainty from `lastDiagnosis`. `diagnosePhysicalMove` keeps its signature (the reducer passes `expected` as `stateBeforeMove`).
- `core/application/.../session/TRANSITIONS.md` -- l.77 (`observed` is cleared), l.251-256 physical rules, add dismiss, plan following, no-Partial.
- `core/domain/src/main/resources/content/strings/en.json` -- l.43 add the label key.
- `app/.../ui/lesson/MismatchLadder.kt` -- add `matchesAfterAllLabel` + `onMatchesAfterAll` button (tag `mismatch:matchesAfterAll`) under every rung; remove `candidate` (MISMATCH no longer holds a Diagnosis); KDoc rung 2.
- `app/.../ui/lesson/LessonPanel.kt` -- l.174 pass new params; physical controls branch l.179 shows the candidate line (tag `mismatch:candidate`).
- `app/.../ui/lesson/LessonScreen.kt` -- `stepMove` l.532 prefers the Planned next Move; `onMoveKey` l.675 always hides the key bar; panel wiring l.607; `diagnosisCandidate` l.924 only for `LIKELY`.
- Tests: `core/application/src/test/.../PhysicalModeSessionTest.kt` (l.417-430 old contract, l.538 invariant), `TransitionsTableTest.kt` (l.251 samples), `core/domain/src/test/.../tutor/AssessTest.kt`; `app/src/test/.../ui/lesson/PhysicalLessonScreenTest.kt` (l.614-660, l.662-710), `app/src/sharedTest/.../EndToEndSyncScenario.kt` if it asserts editor entry.

## Tasks & Acceptance

**Execution:**
- `core/application/.../session/Reduce.kt` -- rung-1 commit, dismiss, editor to VERIFIED, plan following, `keeps` -- the contract lives in the reducer (AD-3).
- `core/domain/.../tutor/Assess.kt` -- physical Certainty from the Diagnosis -- never a false VERIFIED (AD-5).
- `core/application/.../session/TRANSITIONS.md` -- every case above -- normative table must match.
- `core/domain/.../content/strings/en.json` -- add the dismiss label -- content key, not a screen literal.
- `app/.../ui/lesson/MismatchLadder.kt`, `LessonPanel.kt`, `LessonScreen.kt` -- dismiss button, candidate after commit, Done follows the plan, key bar closes -- the Learner can reach the contract.
- `core/application/src/test/.../PhysicalModeSessionTest.kt`, `TransitionsTableTest.kt` -- one test per matrix row, `observed` never Partial in the random walk, sample rows for the new branches.
- `core/domain/src/test/.../tutor/AssessTest.kt` -- physical Certainty with a hidden Diagnosis.
- `app/src/test/.../ui/lesson/PhysicalLessonScreenTest.kt` -- rung 1 commit and candidate, dismiss, every rung-2 answer, editor to VERIFIED, Done follows a plan.

**Acceptance Criteria:**
- Given `PHYSICAL_MISMATCH`, when the Learner enters the move they made (rung 1), then it is committed as their real move: Expected updates, the state returns to `PHYSICAL_PREDICTED`, and Certainty is the diagnosed one (never a false VERIFIED).
- Given `PHYSICAL_MISMATCH`, when the Learner taps *It matches after all*, then the state returns to `PHYSICAL_PREDICTED` with Expected unchanged.
- Given rung 2, when it is answered, then it guides to re-entry (rung 3), and no `PhysicalObservation.Partial` is produced in v1.0.
- Given a full state entered in the Manual Cube Editor from physical mode, when accepted, then the state is `PHYSICAL_VERIFIED`, and an offered Guided recovery survives the physical confirms that follow.
- Given the cases above, when reducer tests run, then each case has a test, and `TRANSITIONS.md` matches the reducer for every case.

## Spec Change Log

### 2026-11-08 — Product owner decisions after review
- A rung-1 move after a confirmed move **replaces** it (Expected = state before the last *Done* + entered move, one history entry, Certainty at most `LIKELY`, Diagnosis from that earlier state); on top only when there is no confirmed move to take back. Supersedes the "applied on top" and "`expected` as the base, always" lines under Always and the "Rung 1 with prior confirms" row of the matrix; `TRANSITIONS.md` is normative.
- A Checkpoint holding the replaced move follows the replacement, and the net snaps (it is not one turn away).
- The candidate line is hidden when the only candidate is the Learner's own entered move; other candidates still show.
- Rung 1 has its Move keys in *Show me*; a move on the authored path entered on rung 1 has no Diagnosis; a predicted cube with no Diagnosis is `LIKELY`, never `VERIFIED`. These resolve the two items still listed under `deferred`.
- Known limit, same as a physical `Undo`: an attempt closed by a *Done* that met the Objective is not reopened when that move is replaced.

## Review Triage Log

### 2026-11-08 — Review pass
- verdicts: 28 findings — high 0, medium 4, low 20, false 4, maybe-false 0
- findings:
  - `[low]` `[reject]` Blind: a false alarm loses a plan or offer because `MismatchReported` clears them — real, but it needs a plan or offer, then a report, then a dismiss, and keeping them through `PHYSICAL_MISMATCH` would also need the chip's `UNKNOWN` guarded.
  - `[medium]` `[patch]` Blind: Move keys stay live on rungs 2 and 3 and a tap now commits — fixed: `mismatchRung` hoisted to `LessonBody`, the key bar and swipes show only on rung 1, test added.
  - `[low]` `[reject]` Blind: *Done* then a different turn ends in a wrong Expected — real for that sequence, but "most common" is refuted by EXPERIENCE Flow 1 step 5 (turns wrong, reports, no *Done*); the fix changes the settled commit contract; re-entry resolves it. Recorded as a residual risk.
  - `[low]` `[defer]` Blind: a correct on-path Move on rung 1 is told it was wrong — pre-existing fallback from Story 8.3; deferred.
  - `[low]` `[defer]` Blind: `PHYSICAL_VERIFIED` can show Unknown or Likely — the same chip text showed before; the AC names only the state; deferred.
  - `[false]` `[reject]` Blind: "never a false VERIFIED" lasts one commit — `decision.certainty` reaches only `CertaintyChip`, shown when `lastDiagnosis != null` or the state is VERIFIED or MISMATCH, and `FrameLabel` ignores it in physical mode, so no Learner sees it after the Diagnosis clears.
  - `[low]` `[reject]` Blind: a plan can be dropped when following it reaches a step boundary — needs a plan Move that also completes a step with Moves left, and a precedence rule is a new branch.
  - `[low]` `[patch]` Blind: `lesson:menuNotSure` lost its only test — fixed: the rung-1 test reports through it.
  - `[low]` `[reject]` Blind: `matchesAfterAllLabel` defaults to empty — `LessonScreen` is the only production caller and passes it; a required parameter churns every `LessonPanel` test call.
  - `[low]` `[reject]` Blind: `diagnosePhysicalMove` has a redundant parameter — no caller harm; removing it rewrites the ambiguous-candidate `AssessTest`.
  - `[medium]` `[patch]` Edge: keys live past rung 1 — same entry as the Blind row.
  - `[low]` `[reject]` Edge: plan or offer vanishes after a false alarm — as the Blind row.
  - `[low]` `[patch]` Edge: stale Full after a dismiss from a report made in VERIFIED — fixed: the dismiss sets `observed = null`, reducer test added.
  - `[low]` `[defer]` Edge: `PHYSICAL_VERIFIED` chip reads Likely or Unknown — as the Blind row.
  - `[low]` `[reject]` Edge: candidate line for non-ladder LIKELY Diagnoses — the candidates are real and UX shows them for Likely; a provenance flag is new state.
  - `[false]` `[reject]` Edge: a LIKELY commit flips to VERIFIED on the next tap — as the Blind row: with `lastDiagnosis` null in PREDICTED no chip is drawn.
  - `[medium]` `[patch]` Edge: confirming before *Guide me back* removes the offer — fixed: a commit with no Diagnosis of its own keeps one for which `canGuideBack` held, capped by `Diagnosis.carried()` to at most LIKELY so a carried offer cannot show a false VERIFIED; tests added.
  - `[low]` `[patch]` Verification: key-bar assertions pass without the edited lines — fixed: new test opens the bar with *I did something else* first.
  - `[low]` `[patch]` Verification: reporting from `PHYSICAL_VERIFIED` unasserted — fixed: reducer test added.
  - `[low]` `[patch]` Verification: VERIFIED chip with no Diagnosis unasserted — fixed: screen test added.
  - `[low]` `[defer]` Verification other: a clean editor entry can read Unknown — same deferred entry as the chip rows.
  - `[low]` `[defer]` Verification other: rung 1 has no keys in *Show me* — pre-existing; deferred.
  - `[low]` `[reject]` Intent: rung-1 base, on top of Expected versus replacing the prediction — as the Blind *Done* row; Flow 1 step 5 selects on top of Expected.
  - `[false]` `[reject]` Intent: a predicted state cannot be verified by self-report — the AC says "the diagnosed one" and Story 8.3 defines VERIFIED by a unique candidate.
  - `[low]` `[reject]` Intent: rung 2 has no reducer case — it has no event by the contract; covered by a screen test and the random-walk no-`Partial` assertion, and recorded in `TRANSITIONS.md`.
  - `[medium]` `[patch]` Intent: "offered" read as a Planned plan only — same entry as the Edge offer row; both readings now hold.
  - `[low]` `[defer]` Intent: `PHYSICAL_VERIFIED` chip versus state — as the Blind row.
  - `[false]` `[reject]` Intent: additions beyond the ACs — the adaptive count, candidate move and key-bar close follow from "committed as their real move" and Story 8.3's candidate rule.

### 2026-11-08 — Review pass (follow-up)
- verdicts: 31 findings — high 0, medium 5, low 21, false 5, maybe-false 0
- findings:
  - `[medium]` `[patch]` Blind: `Diagnosis.carried()` makes a stale Candidate line appear — fixed: a VERIFIED editor Diagnosis passed the LIKELY filter after one confirm and named Moves from before it; `carried()` now also empties `candidates`, reducer test asserts it.
  - `[medium]` `[patch]` Blind: the carry checks `canGuideBack` on the pre-commit state — fixed: `commitPhysical` drops a carried Diagnosis when `canGuideBack` no longer holds after the commit (Notice included), so a confirm that meets the goal stops the explanation and chip; test added and mutation-checked, `TRANSITIONS.md` updated.
  - `[low]` `[reject]` Blind: a confirm during `Planning` or off a plan loses the offer and the re-offer — needs a turn inside the planner window or *I did something else* during a plan, and the off-plan half is the contract's "any other Move ends it"; a fix adds a branch.
  - `[low]` `[reject]` Blind: `recoveryGoal` drifts for a carried Diagnosis — the goal keeps the Checkpoint and path-entry references, so only pieces preserved since the Checkpoint can drop; pinning the diagnosed reference needs a stored field, which the intent forbids.
  - `[low]` `[reject]` Blind: Undo removes a carried offer — `undo` clearing the Diagnosis predates this story and restoring it needs saved state.
  - `[low]` `[patch]` Blind: the key bar closes on only two of the four ways out of a mismatch — fixed: `LaunchedEffect(session.syncState)` closes it in physical mode on leaving `PHYSICAL_MISMATCH`; editor-exit test added and mutation-checked.
  - `[low]` `[reject]` Blind: rungs 2 and 3 have no way back to rung 1 — *It matches after all* then a fresh report, or re-entry, gets the Learner there; a back control is new UI and copy.
  - `[low]` `[reject]` Blind: a mistaken rung-1 key has no safe correction — real for a mis-tap, but the contract makes the commit undoable with the physical Undo instruction; a confirmation step or an Undo back to the ladder changes that contract.
  - `[low]` `[reject]` Blind: rung 1 does not say whether the turn replaces or follows the last *Done* — carried: as the logged *Done*-then-a-different-turn row; Flow 1 step 5 selects on top of Expected.
  - `[low]` `[patch]` Blind: the carry-cap test is too weak — fixed: asserts VERIFIED before the confirm, exactly LIKELY after, and no candidates.
  - `[low]` `[reject]` Blind: rung-1 coverage lacks a step Notice, a rotation key and Practice — all run the same `commitPhysical` the `PHYSICAL_PREDICTED` tests cover and the unchanged `diagnosePhysicalMove`; no defect shown.
  - `[low]` `[patch]` Blind: the hoisted `mismatchRung` is only partly tested — fixed by the restoration test in the Verification row below; every exit shares the one `LaunchedEffect` reset.
  - `[low]` `[patch]` Blind: the `Assess.decide` KDoc is wrong beside the edited line — fixed: `diagnosis` now reads *Test me* or physical, while not `advance`.
  - `[low]` `[patch]` Blind: the `PHYSICAL_MISMATCH` branch is split oddly (`if (move ==` / `null`) — a contract branch a next reader must decode; fixed: `move?.let { commitPhysical(state, it) } ?: ...`.
  - `[low]` `[reject]` Edge: a second rung-1 tap before the state updates commits twice — the window is one `host.dispatch`, and the guard offered is an out-of-range rung value.
  - `[low]` `[patch]` Edge: key bar stays open after an editor accept or Undo — same entry as the Blind key-bar row.
  - `[low]` `[reject]` Edge: a false alarm cancels a plan or offer — carried: as the logged false-alarm row; `MismatchReported` still clears them.
  - `[medium]` `[patch]` Edge: Candidate line shows Moves from an earlier state — same entry as the Blind Candidate row, for the carried Diagnosis; the non-ladder LIKELY half is the logged rejected row.
  - `[low]` `[defer]` Edge: a move that disturbs nothing is told it moved a placed piece — carried: the deferred `diagnosePhysicalMove` fallback (Story 8.3), already in `deferred`, not added again.
  - `[false]` `[reject]` Edge: a Verified chip rests only on the Learner's word — carried: the AC says "the diagnosed one" and Story 8.3 defines VERIFIED by a unique candidate.
  - `[low]` `[reject]` Edge: in *Test me* a classified confirm replaces a carried offer — the last committed Move's own Diagnosis is the rule, as the spec states ("no Diagnosis of its own"); a precedence rule is a new branch.
  - `[low]` `[patch]` Verification: nothing pins that a Diagnosis without an offer ends on the next commit — fixed: two reducer tests (a shown non-offering Diagnosis, and a rung-1 Diagnosis then a confirm).
  - `[low]` `[patch]` Verification: the hoisted rung's survival across restoration is unverified — fixed: `StateRestorationTester` test at rung 3, keys still hidden; mutation-checked with `remember`.
  - `[medium]` `[patch]` Verification other: `carried()` makes a stale Candidate line — same entry as the Blind Candidate row.
  - `[low]` `[reject]` Intent: report then dismiss loses a plan or Diagnosis — carried: as the logged false-alarm row.
  - `[false]` `[reject]` Intent: plan following in `commitPhysical` is unreachable from `PHYSICAL_MISMATCH` — no bad outcome: the shared commit only has nothing to follow there, so no rule breaks.
  - `[medium]` `[patch]` Intent: the carried Diagnosis can surface a stale Candidate line — same entry as the Blind Candidate row.
  - `[false]` `[reject]` Intent: a no-Diagnosis commit can read VERIFIED, and two standards apply — carried: as the logged "lasts one commit" and "self-report" rows; no chip is drawn without a Diagnosis in `PHYSICAL_PREDICTED`.
  - `[false]` `[reject]` Intent: presentation changes the intent does not state — carried: as the logged "additions beyond the ACs" row; the rung hoist is the fix for the medium keys-past-rung-1 entry.
  - `[low]` `[reject]` Intent: tests sit below some clauses (helper-based base check, runtime no-`Partial`, editor via dispatch) — each clause has an independent signal: the `candidates` assertion, the Verification grep, and the pre-existing Accept test.
  - `[false]` `[reject]` Intent: the playback sentence in `TRANSITIONS.md` changed — the removed code also cleared `observed`, so it corrects the document and changes no behaviour.

## Design Notes

**Rung 1 applies on top of Expected.** "Committed as their real move" with "Expected updates" and `TRANSITIONS.md`'s "commits it" name the one commit the reducer has; the retro lists the old `history.undo().state` base as a defect because its meaning depends on hidden history. A Learner who tapped *Done* and turned something else first takes *Undo* or re-entry.

**"Survives the physical confirms that follow" is a Planned recovery being followed.** The retro's finding and its contract question ("Can a Planned recovery be followed by physical confirms?") name the `withHistory` reset of `guidedRecovery`. An off-plan Move invalidates the plan's Moves, so it ends the plan as in virtual mode, and the Diagnosis still belongs to the last committed Move (UX: the Verified chip reverts on the next *Done*).

**Addendum from review.** An unaccepted offer also survives: a confirm with no Diagnosis of its own keeps one for which `canGuideBack` held, at most LIKELY (`Diagnosis.carried`), so both readings of "offered" hold. The chip therefore stays while the offer does, rather than reverting on the next *Done*.

## Verification

**Commands:**
- `export JAVA_HOME=/usr/lib/jvm/java-25-openjdk-amd64 ANDROID_HOME=$HOME/Android/Sdk; ./gradlew spotlessApply` -- formatting applied
- `./gradlew spotlessCheck build` -- expected: BUILD SUCCESSFUL (lint, JVM tests, Kover, gates)
- `./gradlew compileDebugAndroidTestKotlin` -- expected: BUILD SUCCESSFUL (shared test sources)
- `grep -rn 'PhysicalObservation.Partial' app/src/main core/application/src/main` -- expected: no match

## Auto Run Result

Status: done
Blocking condition: none

**Summary:** The mismatch ladder now resolves a mismatch. A rung-1 Move is committed on top of Expected and returns to `PHYSICAL_PREDICTED` with its diagnosed Certainty, `PhysicalConfirmed(null)` is *It matches after all*, rung 2 stays a prompt that leads to re-entry, and an accepted Manual Cube Editor entry lands in `PHYSICAL_VERIFIED`. Physical confirms follow a Planned recovery and keep an offered one, and `TRANSITIONS.md` states every case.

**Files changed:**
- `core/application/.../session/Reduce.kt` -- rung-1 commit, dismiss, editor to VERIFIED, plan following, carried offer.
- `core/domain/.../tutor/Assess.kt` -- physical Certainty from the Diagnosis; `Diagnosis.carried()`.
- `core/application/.../session/TRANSITIONS.md`, `SessionEvent.kt`, `SessionState.kt` -- normative rules and KDoc.
- `core/domain/.../content/strings/en.json` -- `msg.physical.mismatch.matches-after-all`.
- `app/.../ui/lesson/LessonScreen.kt`, `LessonPanel.kt`, `MismatchLadder.kt` -- dismiss on every rung, candidate after the commit, *Done* follows a plan, keys only on rung 1.
- Tests in `PhysicalModeSessionTest.kt`, `TransitionsTableTest.kt`, `AssessTest.kt`, `PhysicalLessonScreenTest.kt`.

**Review:** 28 findings: high 0, medium 4, low 20, false 4. Patches applied: 2 medium entries (keys live past rung 1; offer lost on a confirm) and 5 low entries (stale observation on dismiss, `lesson:menuNotSure` test, key-bar test, report-from-VERIFIED test, VERIFIED chip test). Deferred: 3 entries, all low. Rejected: every `false` and remaining `low` row, each with its reason in the Review Triage Log.

**Follow-up review recommended:** true (patched: high 0, medium 2, low 5). Unverified risk: the hoisted `mismatchRung` and the carried offer, including `Diagnosis.carried()`, were written after the review layers ran, so no independent layer has read them.

**Verification:** `./gradlew spotlessApply`, `./gradlew spotlessCheck build` and `./gradlew compileDebugAndroidTestKotlin`: BUILD SUCCESSFUL after the patches (JUnit XML: 1320 tests, 0 failures, 0 errors, 0 skipped). `grep 'PhysicalObservation.Partial' app/src/main core/application/src/main`: no match. Matrix audit: every row has a covering test that ran and passed.

**Residual risks:** Nothing ran on a device or emulator. A Learner who tapped *Done* and had actually turned something else gets a wrong Expected from rung 1 (the Move goes on top, as the contract says), with a chip that can read Verified; only re-entry corrects it, and nothing on the ladder says so. `Cube3DEmphasisTest` failed once during implementation and passed on the next full run; it is outside this diff and may be flaky in CI. Deferred items above.

### Follow-up review pass, 2026-11-08

**Summary:** A fresh review of the hoisted `mismatchRung` and the carried offer. A carried Diagnosis now ends once `canGuideBack` no longer holds after the commit and names no candidates, the key bar closes on every exit from `PHYSICAL_MISMATCH`, and the gaps it found have tests.

**Files changed in this pass:**
- `core/application/.../session/Reduce.kt` -- carried Diagnosis rechecked after the commit; dismiss branch reshaped.
- `core/domain/.../tutor/Assess.kt` -- `Diagnosis.carried()` empties `candidates`; `decide` KDoc corrected.
- `core/application/.../session/TRANSITIONS.md` -- carried Diagnosis rule.
- `app/.../ui/lesson/LessonScreen.kt` -- key bar closes on any exit from `PHYSICAL_MISMATCH`.
- `PhysicalModeSessionTest.kt` and `PhysicalLessonScreenTest.kt` -- 3 reducer tests, a stronger carry assertion, editor-exit and restoration screen tests.

**Review:** 31 findings: high 0, medium 5, low 21, false 5. Patched entries: 2 medium (stale Candidate line; carry checked before the commit) and 6 low (key bar exits, carry-cap test, non-offering Diagnosis test, rung restoration test, `decide` KDoc, dismiss branch shape). Deferred: none new (one carried row is already in `deferred`). Rejected: every remaining row, with its reason in the Review Triage Log.

**Follow-up review recommended:** false (follow-up pass, patched: high 0, medium 2, low 6).

**Verification:** `./gradlew spotlessCheck build` BUILD SUCCESSFUL on the final tree (JUnit XML: 1426 tests, 0 failures, 0 errors, 0 skipped), `./gradlew compileDebugAndroidTestKotlin` BUILD SUCCESSFUL, `grep 'PhysicalObservation.Partial' app/src/main core/application/src/main`: no match. Two earlier full-gate runs in this pass failed and were not code failures of this diff: one `spotlessKotlin` could not read a generated KSP file during a parallel build, and one `Cube3DEmphasisTest` assertion (`Cube3DEmphasisTest.kt:101`, not in this diff) failed and passed on an unchanged rerun. Mutations confirmed the new tests catch removal of the post-commit recheck (reducer), of the key-bar close and of `rememberSaveable` (screen).

**Residual risks:** Nothing ran on a device or emulator. `Cube3DEmphasisTest` is intermittent and can fail CI. Unchanged from the first pass: a Learner who tapped *Done* and turned something else gets a wrong Expected from rung 1, and only re-entry corrects it.
