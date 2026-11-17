---
title: 'Story 10.6: Quick Check evidence and Certainty'
type: 'feature'
created: '2026-11-17'
status: 'blocked'
baseline_revision: '2c409816aee189c13a44588a5c871affb28529e7'
review_loop_iteration: 0
followup_review_recommended: false
context:
  - '{project-root}/AGENTS.md'
  - '{project-root}/_bmad-output/implementation-artifacts/epic-10-context.md'
warnings: [oversized]
deferred: []
---

<intent-contract>

## Intent

**Problem:** The tutor has no way to judge a camera observation of the physical cube. `Assess` only confirms a whole `CubeState`, a `PhysicalObservation.Partial` has no consistency check, nothing applies AD-5's coverage rule, and the session has no Quick Check request/result with stale-revision handling.

**Approach:** Pure domain first. Add a consistency check for `Partial`, a coverage function for the faces Moves touch, a Quick Check face planner (at most 2 captures), and `Assess.check`, which turns an observation into Certainty plus its consistent candidates. Then add a revisioned Quick Check request/result pair to the session reducer: a current result updates the Sync State, and a stale one is discarded and offered again. Software only: no UI, no camera, no flag change.

## Boundaries & Constraints

**Always:**
- Only `tutor/Assess.kt` names a `Certainty` value (`CertaintyProducerTest`). Candidates come only from one `CandidateEnumerator` over the session's `expected`, configured by `tutorConfig`.
- A `Partial` is checked for internal consistency only. It is never completed from `expected`, never becomes a `CubeState` or `Facelets`, and is never adopted. Comparison against a candidate reads the candidate's home-frame colors on the observed faces only.
- Observations compare by home-frame colors (`cubies`, centers fixed), never by `CubeState` equality: scans carry no hold orientation. `Partial.faces` is keyed by home face (the face whose center color is `Color.home(face)`), with colors in Kociemba net order, as `ScanAssembler` emits them.
- VERIFIED requires both: (1) the observed faces ⊇ the faces touched since the last Full VERIFIED observation (all six when there was none in this physical run); (2) exactly one consistent candidate. Otherwise 0 candidates → UNKNOWN; else LIKELY.
- A result applies only when its `effectId` is the pending Quick Check's and its `revision` is current (AD-3). Otherwise the reducer records `lastDiscarded`, and if the Learner started that check it sets the re-offer text `msg.quick-check.stale`.
- New Learner text only in `core/domain/src/main/resources/content/strings/en.json`: `"msg.quick-check.stale": "Your cube changed while scanning — scan again?"`.

**Never:**
- No UI, chip, SEEN strip, ladder rung, entry point, `AdoptObserved`, Diagnosis from a Partial, or Solve Playback Quick Check. Those belong to Story 10.7. No `:app` or camera source-set change beyond what compiling a new `Effect`/`SessionEvent` forces.
- No feature-flag level change, no new dependency, no numeric confidence across a module boundary.
- Never weaken or delete an existing test. Extending `TransitionsTableTest` (rows, samples, scenarios, event count) is required. Existing physical-mode invariants stay as they are.

## I/O & Edge-Case Matrix

| Scenario | Input / State | Expected Output / Behavior | Error Handling |
|----------|--------------|---------------------------|----------------|
| Verified match | after a Full VERIFIED obs, confirmed `R`, `U`. Partial of R+U = expected's colors | `Assess.check` → VERIFIED, single candidate `[]`. Session → `PHYSICAL_VERIFIED`, `observed` = that Partial | — |
| Verified mismatch | confirmed `R`, Learner did `R'`. Partial of the planned faces | VERIFIED, single candidate `[R2]` (≠ expected). Session → `PHYSICAL_MISMATCH` | — |
| Not covered | single consistent candidate, but a touched face not observed | LIKELY | — |
| No Full verified yet | physical run entered via *It matches* (no Editor/Full obs). Any Partial | at most LIKELY (all six faces required) | — |
| Several fit | e.g. one face observed | LIKELY. Sync State unchanged unless `[]` is not among the candidates | — |
| Nothing fits | Partial fits no candidate | UNKNOWN. Session → `PHYSICAL_MISMATCH` (`[]` not consistent) | — |
| Inconsistent Partial | wrong center, ≠ 9 stickers, a color > 9 times, or a seen edge/corner that can't exist | UNKNOWN, no candidates. Sync State and `observed` unchanged | never throws |
| Full observation | validated `CubeState` | covers every face. VERIFIED iff exactly one candidate matches its cubies. A VERIFIED match resets the touched set to empty | — |
| Stale, Learner-started | `PhysicalConfirmed(move)` between request and result | `lastDiscarded` set, pending cleared, `quickCheckRetry = Text("msg.quick-check.stale")`, Sync State/expected/observed unchanged | — |
| Stale, not Learner-started | same, `byLearner = false` | discarded, no re-offer | — |
| Unknown effect id | result whose `effectId` is not pending | `lastDiscarded` only | — |

</intent-contract>

## Code Map

- `core/domain/src/main/kotlin/dev/cubentia/domain/recognition/PhysicalObservation.kt` -- `Full(state)`, `Partial(faces: Map<Face, List<Color>>, orientationHint)`. Extend in place; don't duplicate.
- `core/domain/src/main/kotlin/dev/cubentia/domain/recognition/ScanAssembler.kt` (L196) -- shows how a `Partial` is keyed (home slot by center color, net order).
- `core/domain/src/main/kotlin/dev/cubentia/domain/cube/{CubeState,Facelets,Orientation,Move,Color}.kt` -- `CubeState.apply`, `toFacelets()` (Learner frame), `reoriented(Orientation.HOME)` gives home-frame facelets. Internal `CubeState.parts(WideTurn|SliceTurn)` gives face-turn parts plus a rotation. Internal `FaceletTables.{EDGE_FACELETS,EDGE_FACES,CORNER_FACELETS,CORNER_FACES,center}`. `Orientation.homeFaceAt`. `Face.opposite`. `Color.home(face)` (no `Color.opposite`; derive it via the home faces).
- `core/domain/src/main/kotlin/dev/cubentia/domain/tutor/Assess.kt` -- the only Certainty producer. `confirm`, `certaintyOf`, `decide(isVerified)` stay unchanged.
- `core/domain/src/main/kotlin/dev/cubentia/domain/tutor/CandidateEnumerator.kt` -- `candidates()`, `consistentWith`, `CandidateEnumerator.of(expected, config)`. `tutor-config.json`: maxDepth 2, 18 face turns.
- `core/domain/src/test/kotlin/dev/cubentia/domain/tutor/{AssessTest,CertaintyProducerTest}.kt` -- test style and the producer guard.
- `core/application/src/main/kotlin/dev/cubentia/application/session/SessionState.kt` -- `observed` (L149), `syncState`, `lastDiscarded` (L72), `pendingReset` pattern, `IDLE` (L278). New fields go last with defaults.
- `core/application/src/main/kotlin/dev/cubentia/application/session/Reduce.kt` -- `reduce`/`applied` (L31-140): the `playbackAllowed` gate and the generic clearing of feedback/Diagnosis/guidedRecovery for non-`keeps` events. Exhaustive `transition` `when` (~L150-262). Physical `undo` (L523). `switchMode` (L546). `confirmPhysical`/`commitPhysical` (L601-667). `enterEditor` (L711, the Full VERIFIED path). Restore paths set `observed = null` (L1042, L1119). The `planRecovery`/`planned` handling shows the stale-result pattern.
- `core/application/src/main/kotlin/dev/cubentia/application/session/{SessionEvent,Effect,TRANSITIONS.md}.kt|md` -- add events/effect. TRANSITIONS.md needs one row per event plus a prose paragraph.
- `core/application/src/test/kotlin/dev/cubentia/application/session/{TransitionsTableTest,StaleEffectTest,GuidedRecoverySessionTest,PhysicalModeSessionTest}.kt` -- table test (`samples`, `scenarios`, `assertEquals(31, eventTypes.size)`). Stale-result test style. Physical fixtures (`ModeSwitched(PHYSICAL)` → `PhysicalConfirmed(null)` → predicted; `EditorEntered` → verified).
- `app/src/main/kotlin/dev/cubentia/app/session/SessionHost.kt` (L650) -- its effect `when` has `else`. Check for any other exhaustive `when` over `SessionEvent`/`Effect` in `:app` that fails to compile.

## Tasks & Acceptance

**Execution:**
- `core/domain/.../recognition/PartialConsistency.kt` (new) -- `sealed interface PartialCheck { data object Consistent; data class Inconsistent(val faces: Set<Face>) }` and `object PartialConsistency { fun check(partial: PhysicalObservation.Partial): PartialCheck }`. Rules: every listed face has 9 colors, and its center is `Color.home(face)`. No color appears more than 9 times across the observed stickers. Every edge with both stickers observed shows two colors that are neither equal nor opposite. Every corner with ≥ 2 stickers observed shows pairwise such colors, and with all 3 observed it is a real corner color set. `Inconsistent` names the faces involved. It uses only the partial.
- `core/domain/.../tutor/Coverage.kt` (new) -- `object Coverage { fun touched(before: CubeState, move: Move): Set<Face> }`, in home faces. `FaceTurn` → `before.orientation.homeFaceAt(face)`. Wide/slice → the home faces of their face-turn parts, each part resolved through the orientation in effect at that part. `CubeRotation` → empty. Plus `fun touched(before, moves: List<Move>)` folding through `before.apply`.
- `core/domain/.../tutor/QuickCheck.kt` (new) -- `object QuickCheck { const val MAX_CAPTURES = 2; fun faces(mustCover: Set<Face>): Set<Face> }`. Size 2 → itself. Size 1 → plus the first face in URFDLB order that is neither it nor its opposite. Size 0 → {U, F}. More than 2 → all six (a Guided 6-face Scan). It never produces Certainty.
- `core/domain/.../tutor/Assess.kt` -- add `data class ObservationCheck(val certainty: Certainty, val consistent: List<Candidate>) { val matchesExpected get() = consistent.any { it.moves.isEmpty() } }` (constructed only in Assess.kt) and `fun check(enumerator: CandidateEnumerator, observation: PhysicalObservation, mustCover: Set<Face>): ObservationCheck`, following the rules in Always and the matrix. Update the class KDoc (the "coverage never lowers it" sentence applies to Full only).
- `core/domain/src/main/resources/content/strings/en.json` -- add `msg.quick-check.stale` (exact copy above).
- `core/application/.../session/Effect.kt` -- `Effect.QuickCheck(effectId, revision, faces: Set<Face>, byLearner: Boolean)`: capture these home faces, answered by `QuickCheckObserved` or `QuickCheckCancelled`.
- `core/application/.../session/SessionEvent.kt` -- `QuickCheckRequested(byLearner: Boolean = true, at)`, `QuickCheckObserved(effectId, revision, observation: PhysicalObservation, at)`, `QuickCheckCancelled(effectId, at)`.
- `core/application/.../session/SessionState.kt` -- add `touchedSinceVerified: Set<Face>? = null` (null = no Full VERIFIED observation since entering physical mode), `pendingQuickCheck: Effect.QuickCheck? = null`, `quickCheck: ObservationCheck? = null` (latest applied result) and `quickCheckRetry: Text? = null`. Add derived `mustCover` = `touchedSinceVerified ?: all six`. Write KDoc for each.
- `core/application/.../session/Reduce.kt`:
  - `QuickCheckRequested` applies only in a session with a `tutorConfig` in `PHYSICAL_PREDICTED`/`VERIFIED`/`MISMATCH`. It issues `Effect.QuickCheck(nextEffectId, revision, QuickCheck.faces(mustCover), byLearner)`, records it pending, and clears `quickCheckRetry`. Elsewhere it changes nothing.
  - `QuickCheckObserved` handles a stale or unknown result as in the matrix. A current one, still physical: `Assess.check(CandidateEnumerator.of(expected, tutorConfig), observation, mustCover)`, which stores `quickCheck` and clears pending. Then:
    - VERIFIED and `matchesExpected` → `PHYSICAL_VERIFIED`, `observed` = observation. A Full observation also resets `touchedSinceVerified = emptySet()`.
    - not `matchesExpected` and the observation is Full or a consistent Partial → `PHYSICAL_MISMATCH` (from PREDICTED/VERIFIED; MISMATCH stays), `observed` = observation.
    - otherwise (LIKELY with `[]` among the candidates, or an inconsistent Partial, which only a recapture can fix) the Sync State and `observed` are unchanged.
    It never changes `expected`, `virtual`, the history or the revision.
  - `QuickCheckCancelled` with the pending id clears pending and the retry text.
  - `touchedSinceVerified`:
    - `commitPhysical` adds `Coverage.touched(base.expected, move)` when non-null.
    - Physical `undo` adds the undone Move's faces.
    - `enterEditor` sets it to empty.
    - Any other transition that changes `expected`, or leaves the state in `VIRTUAL` or `PHYSICAL_AWAITING_CONFIRM`, sets it to null.
  - Add the new events to the `transition` `when`. Decide explicitly whether they are in `keeps`. Default: not kept, like `MismatchReported`.
- `core/application/.../session/TRANSITIONS.md` -- add the three rows (cells as the table test observes them) and a "Quick Check (Story 10.6, AD-3/AD-5)" paragraph describing the rules above.
- `core/application/src/test/.../session/TransitionsTableTest.kt` -- add samples and scenarios for the three events (physical predicted/verified/mismatch, stale, unknown id, idle, virtual) and update the event count.
- `core/domain/src/test/.../recognition/PartialConsistencyTest.kt` -- one test per rule, plus a consistent 2-face partial.
- `core/domain/src/test/.../tutor/CoverageTest.kt`, `QuickCheckTest.kt` -- face turns through a rotated orientation, wide/slice parts, rotations touch nothing; the planner's cases.
- `core/domain/src/test/.../tutor/QuickCheckScenarioTest.kt` -- the scenario suite. It is seeded and deterministic, with ≥ 2000 scenarios. For each scenario:
  - S0 is a random reachable state (random scramble plus a random Cube Rotation). The Learner confirms 1–3 random face turns, so expected = S0 + confirmed.
  - The actual cube = S0 + the confirmed Moves with one perturbation: none, the wrong direction on one Move, the wrong face on one Move, an extra Move, a missing Move, or an extra 3-Move sequence.
  - Observations are drawn from the actual cube: the `QuickCheck.faces` plan, random 1–2 face subsets, and Full. `mustCover` is either the touched set or all six faces.
  - Assert 0 false verified (VERIFIED whose single candidate's cubies ≠ the actual cubies).
  - Assert the suite is not vacuous: it contains VERIFIED matches, VERIFIED mismatches, LIKELY and UNKNOWN results. Print the confirmation rate for matching cubes with the plan.
  It never fills unseen faces from expected.
- `core/domain/src/test/.../tutor/AssessTest.kt` -- the `check` matrix rows (verified match/mismatch, not covered, several, none, inconsistent, Full).
- `core/application/src/test/.../session/QuickCheckSessionTest.kt` (new) -- the matrix rows through `reduce`:
  - the effect carries the planned faces and the current revision;
  - each current-result transition;
  - a Partial VERIFIED keeps `touchedSinceVerified`, and a Full VERIFIED match empties it;
  - with no Editor/Full observation, the result is at most LIKELY;
  - the stale cases, with and without the Learner having started the check;
  - `expected` is never changed by a result, and `observed` stays the Partial (never completed).

**Acceptance Criteria:**
- Given a Quick Check of the faces the recent Moves touched (`QuickCheck.faces`, ≤ 2 captures when ≤ 2 faces were touched), when it returns a `Partial`, then it is checked only by `PartialConsistency` and against candidates' colors on its own faces, and no code path builds `Facelets`/`CubeState` from it or reads `expected` to fill it.
- Given `Assess.check`, when the observed faces cover `mustCover` and exactly one candidate fits, then Certainty is VERIFIED; otherwise LIKELY or UNKNOWN; and `QuickCheckScenarioTest` reports 0 false verified.
- Given a stale result (a Move confirmed during the scan), when `QuickCheckObserved` arrives, then it is discarded (`lastDiscarded`), and `quickCheckRetry` is `msg.quick-check.stale` exactly when the Learner started it.
- Given the full build, when `./gradlew spotlessCheck build` runs, then it passes, including `CertaintyProducerTest`, `TransitionsTableTest`, the content tests and Kover ≥ 80% on `:core:*`.

## Spec Change Log

## Review Triage Log

## Design Notes

**What "touched" means.** It is the layer a Move turns, in home faces: `R` touches R. It is not every face whose stickers move (for `R` that is R, U, F, D and B). Two things pick this reading. The AC and FR-26 promise a Quick Check of "the faces the latest Moves affected" in ≤ 2 captures, which the sticker reading makes impossible even after one Move. And "could have touched" covers the direction errors (`R'`, `R2`) the coverage rule exists for (adversarial review H9). Uniqueness does the rest. A single face never verifies, because every turn of the opposite face leaves it unchanged, which is why the planner always asks for 2 faces.

**No earlier Full observation → all six faces.** Entering physical mode with *It matches* gives a predicted cube, not an observed one (AD-5). So until a Full VERIFIED observation (Editor or Full scan), only a 6-face check can verify. This is the literal AD-5 rule and errs toward "0 false verified".

**Candidates compare by cubies.** A scan has no hold orientation, so `Full`/`Partial` match on center-relative colors. Example: expected `S0·R`, Learner did `R'`. With `[R2]` the only candidate whose R and U faces match, the result is VERIFIED mismatch, "you turned `R'` instead of `R`".

## Verification

**Commands:**
- `export JAVA_HOME=/usr/lib/jvm/java-25-openjdk-amd64 ANDROID_HOME=$HOME/Android/Sdk && ./gradlew spotlessApply` -- expected: formatting applied
- `./gradlew :core:domain:test :core:application:test` -- expected: BUILD SUCCESSFUL, scenario suite prints 0 false verified
- `./gradlew spotlessCheck build` -- expected: BUILD SUCCESSFUL

## Auto Run Result

Status: blocked
Blocking condition: implementation verification failed: `QuickCheckScenarioTest` finds a false VERIFIED. The AC's two clauses conflict for Learner errors outside the AD-5 candidate model, and choosing between them is a product decision.

**State of the work:** Codex implemented the whole spec. That covers `PartialConsistency`, `Coverage`, `QuickCheck.faces`, `Assess.check`/`ObservationCheck`, `Effect.QuickCheck`, `QuickCheckRequested`/`Observed`/`Cancelled`, the reducer and Sync State rules, `touchedSinceVerified`, `msg.quick-check.stale`, the TRANSITIONS rows, and the tests. Application tests 467/467 pass. Domain tests 715/716 pass, and the one failure is the scenario suite. The work is committed as WIP on the branch.

**Failure:** seed 106, scenario 620. Confirmed `B2 F2`; the Learner turned `L2` instead of `B2`. The plan observes home faces {R, L}, which covers `mustCover`, and exactly one depth-2 candidate (`R2 B2`) fits them. That is VERIFIED by the AC's rule, but it is false: unseen stickers differ.

**Measured** with a throwaway 40-seed experiment, since removed: 168,000 Partial checks, `mustCover` = the touched faces. Results per perturbation:

| Perturbation | False VERIFIED |
|---|---|
| None | 0 |
| Extra Move | 0 |
| Missing Move | 0 |
| Wrong direction on an earlier Move | 1 |
| Wrong face on an earlier Move | 88 |
| Extra 3-Move sequence | 11 |

That is 100 false out of about 70,400 VERIFIED (≈ 0.14%). Requiring the 2 faces to be adjacent still leaves 17 false out of 62,589. Every false VERIFIED had a non-empty candidate. A matching cube was confirmed in 18,449 of 18,723 plan checks (98.5%).

**Why:** within the candidate model (actual = expected + at most `maxDepth` Moves), a false VERIFIED is impossible by construction. Errors outside the model are a different matter: an earlier confirmed Move done wrong and then followed by more Moves, or 3 or more unconfirmed Moves. Two captures (16 non-center stickers) can then alias a wrong candidate. The coverage rule narrows this but cannot close it.

**Options for the PO:**
- (a) A Partial verifies only a match (unique candidate `[]`), and a Partial mismatch is at most LIKELY, with VERIFIED mismatches only from a Full scan. This gave 0 false VERIFIED in the experiment and kept the match rate, but it contradicts the PRD UJ-2 example ("Quick Check shows `R` instead of `R'` … verified").
- (b) Define the scenario suite within the AD-5 candidate model, and record out-of-model aliasing as a known residual risk.
- (c) Require adjacent faces and accept a non-zero rate (fails the gate).
- (d) Raise `maxDepth`, which costs bounded reducer work and still does not close the gap.
