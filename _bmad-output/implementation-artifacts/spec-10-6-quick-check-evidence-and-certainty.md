---
title: 'Story 10.6: Quick Check evidence and Certainty'
type: 'feature'
created: '2026-11-17'
status: 'done'
baseline_revision: '2c409816aee189c13a44588a5c871affb28529e7'
review_loop_iteration: 0
followup_review_recommended: false
context:
  - '{project-root}/AGENTS.md'
  - '{project-root}/_bmad-output/implementation-artifacts/epic-10-context.md'
warnings: [oversized]
deferred:
  - summary: >-
      Candidates are counted as Move sequences, not distinct cube states, so commuting pairs (U D and D U) make a Full mismatch LIKELY instead of VERIFIED.
    evidence: |-
      CandidateEnumerator only prunes same-face repeats; two opposite-face orders reach the same cubies and both match. Pre-existing AD-5 counting shared with Assess.confirm and diagnose; conservative (never a false VERIFIED).
    location: >-
      core/domain/src/main/kotlin/dev/cubentia/domain/tutor/CandidateEnumerator.kt:40
    severity: low
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
- VERIFIED requires: (1) the observed faces ⊇ the faces touched since the last Full VERIFIED observation (all six when there was none in this physical run); (2) exactly one consistent candidate; (3) for a `Partial`, that candidate is `[]`. Only a `Full` observation can VERIFY a mismatch (PO decision, option (a); see Spec Change Log). Otherwise 0 candidates → UNKNOWN; else LIKELY.
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
| Verified mismatch | confirmed `R`, Learner did `R'`. Partial of the planned faces | LIKELY for a Partial (VERIFIED only for a Full), single candidate `[R2]` (≠ expected), kept as the leading hypothesis. Session → `PHYSICAL_MISMATCH` | — |
| Full mismatch | same cube, `Full` observation | VERIFIED, single candidate `[R2]`. Session → `PHYSICAL_MISMATCH` | — |
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
- `core/domain/.../tutor/Assess.kt` -- (WIP exists) make `check` return LIKELY, not VERIFIED, for a `Partial` whose single consistent candidate is non-empty; the KDoc says a Partial verifies only a match. Original task: add `data class ObservationCheck(val certainty: Certainty, val consistent: List<Candidate>) { val matchesExpected get() = consistent.any { it.moves.isEmpty() } }` (constructed only in Assess.kt) and `fun check(enumerator: CandidateEnumerator, observation: PhysicalObservation, mustCover: Set<Face>): ObservationCheck`, following the rules in Always and the matrix. Update the class KDoc (the "coverage never lowers it" sentence applies to Full only).
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
  - Keep all six perturbations, including the out-of-model ones (wrong earlier face or direction, 3+ extra Moves).
  - Assert 0 false verified (VERIFIED whose single candidate's cubies ≠ the actual cubies).
  - Assert the match-confirmation rate (plan observation of a cube whose cubies equal expected → VERIFIED) is ≥ 98%.
  - Assert no Partial is VERIFIED with a non-empty candidate.
  - Assert the suite is not vacuous: it contains VERIFIED matches, VERIFIED mismatches (from Full), LIKELY and UNKNOWN results. Print the confirmation rate.
  - A second, deliberate test of Move sequences that leave the observed faces unchanged: (i) U observed only, actual = expected + a `D` turn → never VERIFIED; (ii) a state whose U and D faces are solid, observed {U, D}, actual = expected + `U D'` → LIKELY with `[]` among the candidates; (iii) seeded: for ≥ 10 random states and their `QuickCheck.faces` plans (sizes 1 and 2 of `mustCover`), enumerate every face-turn sequence of length ≤ 3 applied to expected; each one that leaves the observed faces' colors equal to expected's while changing the cubies must not give VERIFIED when its length is ≤ `maxDepth` (in-model), and any length-3 alias that does verify must alias a match (`[]`), never name a wrong mismatch. The test prints the alias count.
  It never fills unseen faces from expected.
- `core/domain/src/test/.../tutor/AssessTest.kt` -- the `check` matrix rows (verified match, Partial mismatch LIKELY with `[R2]`, Full mismatch VERIFIED, not covered, several, none, inconsistent, Full).
- `core/application/src/test/.../session/QuickCheckSessionTest.kt` (new) -- the matrix rows through `reduce`:
  - the effect carries the planned faces and the current revision;
  - each current-result transition, including a Partial `R'`-for-`R` mismatch: LIKELY, `[R2]` kept in `quickCheck.consistent`, Sync State `PHYSICAL_MISMATCH`; and a Full mismatch: VERIFIED, `PHYSICAL_MISMATCH`;
  - a Partial VERIFIED keeps `touchedSinceVerified`, and a Full VERIFIED match empties it;
  - with no Editor/Full observation, the result is at most LIKELY;
  - the stale cases, with and without the Learner having started the check;
  - `expected` is never changed by a result, and `observed` stays the Partial (never completed).

**Acceptance Criteria:**
- Given a Quick Check of the faces the recent Moves touched (`QuickCheck.faces`, ≤ 2 captures when ≤ 2 faces were touched), when it returns a `Partial`, then it is checked only by `PartialConsistency` and against candidates' colors on its own faces, and no code path builds `Facelets`/`CubeState` from it or reads `expected` to fill it.
- Given `Assess.check`, when the observed faces cover `mustCover` and exactly one candidate fits (for a Partial: the candidate `[]`), then Certainty is VERIFIED; otherwise LIKELY or UNKNOWN; and `QuickCheckScenarioTest`, with the out-of-model perturbations, reports 0 false verified and a match-confirmation rate ≥ 98%.
- Given a stale result (a Move confirmed during the scan), when `QuickCheckObserved` arrives, then it is discarded (`lastDiscarded`), and `quickCheckRetry` is `msg.quick-check.stale` exactly when the Learner started it.
- Given the full build, when `./gradlew spotlessCheck build` runs, then it passes, including `CertaintyProducerTest`, `TransitionsTableTest`, the content tests and Kover ≥ 80% on `:core:*`.

## Spec Change Log

- **2026-11-17 — PO decision, option (a) (delegated to the party panel).** The first build attempt (WIP `06748d17cc97ec63064a02da99ca1e51b545af48`) blocked: seed 106 scenario 620 (confirmed `B2 F2`, Learner turned `L2` for `B2`, observed {R, L}) gave a false VERIFIED with the single candidate `R2 B2`. A 40-seed experiment over 168,000 Partial checks measured 100 false VERIFIED out of about 70,400 (88 wrong earlier face, 11 extra 3-Move sequence, 1 wrong earlier direction), all with a non-empty candidate, and a 98.5% match-confirmation rate. Out-of-model Learner errors (an earlier Move done wrong then followed by more Moves, or 3+ unconfirmed Moves) let 16 non-center stickers alias a wrong depth-≤2 candidate. Decision: a `Partial` is VERIFIED only when coverage holds and its single consistent candidate is `[]`; a Partial whose single candidate is non-empty is LIKELY, still moves Sync State to `PHYSICAL_MISMATCH`, and keeps that candidate as the leading hypothesis. Only a `Full` observation can VERIFY a mismatch. This **deviates** from FR-26's consequence-3 example (a Quick Check verifying "`R` instead of `R'`") and from AD-5's VERIFIED rule (coverage + one candidate), which this story narrows for Partials. The I/O matrix row *Verified mismatch* now reads LIKELY for a Partial. **Residual risk (theoretical):** a sequence of 3+ Moves that leaves the observed faces' colors unchanged but changes unseen stickers, and is equivalent to no depth-≤2 candidate, would make `[]` the single consistent candidate and alias a match as VERIFIED. The deliberate test in `QuickCheckScenarioTest` measures it: over 123,500 face-turn sequences of length ≤ 3 from 10 seeded states, 44 leave the plan faces unchanged while changing the cubies, and 1 of them (`R U2 R'`, plan {B, D}, a color coincidence on that state) aliases a match as VERIFIED. None of length ≤ 2 verifies, and no alias names a wrong mismatch. The random scenario suite (2,100 scenarios, all six perturbations) shows 0 false VERIFIED and 99.8% match confirmation. The risk is accepted as residual and is not closed by construction.
- **2026-11-17 — Planning correction.** The first version of the deliberate test required that no length-3 face-preserving sequence verify; implementation found the `R U2 R'` alias above. The test now asserts the in-model guarantee and records out-of-model aliases, as the PO's residual-risk clause anticipated.

## Review Triage Log

### 2026-11-17 — Review pass
- verdicts: 15 findings — high 0, medium 2, low 9, false 4, maybe-false 0
- findings:
  - `[low]` `[reject]` Blind: `R U2 R'` aliases a Partial match as VERIFIED — real (the deliberate test finds it), but the PO's option (a) explicitly accepts face-preserving aliasing of a match as residual risk; recorded in the Spec Change Log.
  - `[low]` `[defer]` Blind: candidates counted per sequence, so commuting `U D`/`D U` lower a Full mismatch to LIKELY — pre-existing AD-5 enumerator semantics, conservative; deferred.
  - `[medium]` `[patch]` Blind: `quickCheck` survives commits/Undo/Editor and describes an old Expected — `reduce` now clears `quickCheck` whenever Expected changes; tested.
  - `[medium]` `[patch]` Blind: session starts keep `pendingQuickCheck`/`quickCheckRetry`, so a stale re-offer could leak into a new Lesson — `reduce` clears pending, retry and result whenever the state leaves active physical mode (covers every start and a switch to virtual); tested.
  - `[false]` `[reject]` Blind: a second request replaces the pending one without cancelling — the replacement is the policy: the older result then has an unknown id and is discarded (`lastDiscarded`), which the matrix specifies.
  - `[low]` `[patch]` Blind: a cancellation with a foreign id still runs the generic feedback/Diagnosis clearing — `applied` now returns unchanged for it; tested with feedback present.
  - `[false]` `[reject]` Blind: an empty Partial is Consistent — every candidate fits it, so it is LIKELY with `[]` among the candidates and changes neither Sync State nor `observed`; it cannot confirm.
  - `[low]` `[reject]` Blind: PartialConsistency misses cross-piece duplicates and corner chirality — such a Partial matches no real candidate and ends UNKNOWN/mismatch rather than unchanged; rare misread, and the fix adds rules beyond the spec's list.
  - `[low]` `[patch]` Blind: CoverageTest checks wide/slice coverage against the same `parts` decomposition — added explicit expected faces (Rw→L, Uw→D, M→R+L, E→U+D, S→F+B, and through a y hold).
  - `[low]` `[reject]` Blind: some perturbations degenerate (inverse of a half turn, cancelling extras) — the suite still asserts every Certainty occurs and the PO's thresholds; making them non-degenerate adds generator branches for no change in the guarantee.
  - `[low]` `[patch]` Verification gap: Undo's coverage update was untested (the confirm already added R) — added confirm R → Full match → Undo, asserting `{R}` and that the next plan includes R.
  - `[low]` `[reject]` Intent: the deliberate test tolerates a demonstrated alias although the PO called the risk "theoretical" — the PO scoped 0 false VERIFIED to the perturbation suite and accepted face-preserving aliasing; the Change Log now records the measured case.
  - `[false]` `[reject]` Intent: the residual alias is not exercised through `reduce` — the reducer adopts any VERIFIED match the same way, covered by the session tests; nothing new to observe at that surface.
  - `[false]` `[reject]` Intent: `epics.md`, PRD and architecture still state the un-narrowed rule — editing planning artifacts is forbidden for this build; the deviation is recorded in the spec Change Log as instructed.
  - `[low]` `[reject]` Intent: the 98% and 0-false thresholds rest on one seed — the suite is deterministic by design (seed 106, 2,100 scenarios); the 40-seed experiment is recorded in the Change Log.

### 2026-11-17 — Review pass
- verdicts: 18 findings — high 0, medium 1, low 9, false 8, maybe-false 0
- findings:
  - `[low]` `[reject]` Blind: `R U2 R'` aliases a Partial match as VERIFIED — carried: the PO's option (a) accepts face-preserving aliasing of a match as residual risk; recorded in the Spec Change Log.
  - `[low]` `[reject]` Blind: PartialConsistency does not reject two observed pieces showing the same color set — carried: such a Partial fits no real candidate and ends UNKNOWN/mismatch; rare misread, fix adds rules beyond the spec's list.
  - `[low]` `[reject]` Blind: corner checks compare unordered color sets, so mirrored corners pass — carried (same row as above): ends UNKNOWN/mismatch, not a false VERIFIED.
  - `[low]` `[reject]` Blind: a six-face Partial with impossible flip/twist/permutation parity passes — real, but it matches no candidate and ends UNKNOWN/mismatch; today's `ScanAssembler` emits a Partial only with a face missing, and parity rules are new logic beyond the spec's list.
  - `[false]` `[reject]` Blind: a repeated request overwrites the pending capture without cancelling — carried: replacement is the policy; the older result then has an unknown id and is discarded.
  - `[low]` `[patch]` Blind: `PhysicalConfirmed(null)` dismisses `PHYSICAL_MISMATCH` but keeps the mismatch `quickCheck` — the dismissal branch in `confirmPhysical` now also sets `quickCheck = null`; tested.
  - `[low]` `[patch]` Blind: a stale-scan re-offer survives Manual Cube Editor entry, which verifies the cube — `enterEditor` now clears `quickCheckRetry`; tested.
  - `[false]` `[reject]` Blind: `ObservationCheck`'s public constructor/`copy` let callers manufacture Certainty — no code outside `Assess.kt` constructs one (repository search), and any construction naming a Certainty value fails `CertaintyProducerTest`.
  - `[low]` `[reject]` Blind: some perturbations degenerate (inverse of a half turn, cancelling extras) — carried: every Certainty still occurs and the PO thresholds hold.
  - `[low]` `[reject]` Blind: the aggregate confirmation rate can hide a weak planner case — the PO set one aggregate threshold (≥ 98%) on a deterministic suite; per-case rates add test branches without changing the guarantee.
  - `[medium]` `[patch]` Edge: a Move entered on the mismatch ladder replaces the last confirm but coverage adds only the new Move's layer, so after a Full VERIFIED match the replaced layer (e.g. R) drops out of `mustCover` — `commitPhysical` now also adds the replaced Move's inverse layer (`replacedLayer`), as Undo does; tested (Full verify after R, report, enter U → {R, U}).
  - `[low]` `[patch]` Verification gap: no reducer test for a consistent Partial that fits no candidate — added: UNKNOWN, no candidates, `PHYSICAL_MISMATCH`, `observed` is that Partial.
  - `[false]` `[reject]` Intent: the planner asks for six faces after *It matches* or > 2 touched layers, against "at most 2 captures" — the AC scopes ≤ 2 captures to ≤ 2 touched faces, and the Tasks specify the six-face fallback.
  - `[false]` `[reject]` Intent: a six-face Partial matching Expected with no Full baseline would VERIFY — the matrix row's own reason is "all six faces required", which such a Partial meets; it is not a false VERIFIED, and no current producer emits it.
  - `[false]` `[reject]` Intent: the residual alias is not exercised through `reduce` — carried: the reducer adopts any VERIFIED match the same way, covered by the session tests.
  - `[false]` `[reject]` Intent: a Full VERIFIED mismatch does not reset the touched set — the intent resets it only on a VERIFIED match; after a mismatch the cube is not Expected, so keeping coverage is correct.
  - `[false]` `[reject]` Intent: planning docs (FR-26, AD-5, `epics.md`) still state the un-narrowed rule — carried: editing planning artifacts is forbidden for this build; recorded in the Spec Change Log.
  - `[false]` `[reject]` Intent: "touched" could mean every face whose stickers move — Design Notes settle the layer reading (the ≤ 2-capture AC is impossible otherwise).

## Design Notes

**What "touched" means.** It is the layer a Move turns, in home faces: `R` touches R. It is not every face whose stickers move (for `R` that is R, U, F, D and B). Two things pick this reading. The AC and FR-26 promise a Quick Check of "the faces the latest Moves affected" in ≤ 2 captures, which the sticker reading makes impossible even after one Move. And "could have touched" covers the direction errors (`R'`, `R2`) the coverage rule exists for (adversarial review H9). Uniqueness does the rest. A single face never verifies, because every turn of the opposite face leaves it unchanged, which is why the planner always asks for 2 faces.

**No earlier Full observation → all six faces.** Entering physical mode with *It matches* gives a predicted cube, not an observed one (AD-5). So until a Full VERIFIED observation (Editor or Full scan), only a 6-face check can verify. This is the literal AD-5 rule and errs toward "0 false verified".

**Candidates compare by cubies.** A scan has no hold orientation, so `Full`/`Partial` match on center-relative colors. Example: expected `S0·R`, Learner did `R'`. With `[R2]` the only candidate whose R and U faces match, a Partial gives LIKELY mismatch with `[R2]` as the leading hypothesis (Sync State `PHYSICAL_MISMATCH`); a Full scan of that cube gives VERIFIED.

## Verification

**Commands:**
- `export JAVA_HOME=/usr/lib/jvm/java-25-openjdk-amd64 ANDROID_HOME=$HOME/Android/Sdk && ./gradlew spotlessApply` -- expected: formatting applied
- `./gradlew :core:domain:test :core:application:test` -- expected: BUILD SUCCESSFUL, scenario suite prints 0 false verified
- `./gradlew spotlessCheck build` -- expected: BUILD SUCCESSFUL

## Auto Run Result

Status: done

**Summary:** Story 10.6 implements Quick Check evidence: `PartialConsistency`, `Coverage`, the `QuickCheck.faces` planner, `Assess.check`/`ObservationCheck` and the revisioned `QuickCheckRequested`/`Observed`/`Cancelled` session events, with PO option (a): a Partial VERIFIES only a match (`[]`); a Partial with a single non-empty candidate is LIKELY, moves Sync State to `PHYSICAL_MISMATCH` and keeps that candidate; only a Full VERIFIES a mismatch.

**Files changed:**
- `core/domain/.../recognition/PartialConsistency.kt`, `PhysicalObservation.kt` -- internal consistency of a Partial; capture bound constant.
- `core/domain/.../tutor/Assess.kt`, `Coverage.kt`, `QuickCheck.kt` -- `check` with option (a), touched layers, face planner.
- `core/domain/src/main/resources/content/strings/en.json` -- `msg.quick-check.stale`.
- `core/application/.../session/{Effect,SessionEvent,SessionState,Reduce}.kt`, `TRANSITIONS.md` -- Quick Check effect, events, state, reducer rules and table rows.
- Tests: `PartialConsistencyTest`, `CoverageTest`, `QuickCheckTest`, `QuickCheckScenarioTest`, `AssessTest`, `QuickCheckSessionTest`, `TransitionsTableTest`.

**Review:** 15 findings. Patched 5 (medium 2, low 3): stale `quickCheck` cleared on Expected change; Quick Check state cleared outside active physical mode; foreign cancellation is a no-op; explicit Coverage expectations; Undo coverage test. Deferred 1 (commuting candidates). Rejected 9, reasons in the Review Triage Log.

**Follow-up review recommended:** true -- two medium patches (session-boundary and Expected-change clearing in `reduce`) were applied by the orchestrator, not re-reviewed; the risk is an unintended clear of Quick Check state on a physical transition that keeps Expected.

**Verification:** `./gradlew spotlessApply`; `./gradlew spotlessCheck build` BUILD SUCCESSFUL (Kover, content tests, `CertaintyProducerTest`, `TransitionsTableTest`). `QuickCheckScenarioTest`: 0 false verified, match confirmation 467/468 (99.8%); deliberate test: 44 face-preserving sequences of 123,500, 1 residual alias of a match (`R U2 R'`, plan {B, D}). `QuickCheckSessionTest` 11/11.

**Residual risks:** a 3+ Move sequence that preserves the observed faces can alias a match as VERIFIED (demonstrated once, accepted by the PO); planning artifacts (`epics.md`, FR-26, AD-5) still state the un-narrowed VERIFIED rule and need a planning update outside this build.

### Follow-up review pass (2026-11-17)

**Summary:** A second review of the Story 10.6 diff (four layers: Blind, Edge Case and Verification Gap on Codex, Intent Alignment on Claude). 18 findings.

**Files changed:**
- `core/application/src/main/kotlin/dev/cubentia/application/session/Reduce.kt` -- a ladder replacement keeps the replaced Move's layer in `touchedSinceVerified`; dismissing a mismatch clears `quickCheck`; Editor entry clears `quickCheckRetry`.
- `core/application/src/test/kotlin/dev/cubentia/application/session/QuickCheckSessionTest.kt` -- three tests: consistent no-candidate Partial, ladder replacement coverage, dismissal and Editor clearing.

**Review:** Patched 4 (medium 1, low 3): replacement coverage, dismissal clears evidence, Editor clears the re-offer, consistent UNKNOWN Partial reducer test. Deferred 0. Rejected 14 (6 carried from the first pass), reasons in the Review Triage Log.

**Follow-up review recommended:** false -- follow-up pass with no `high` patched (patched: high 0, medium 1, low 3); the work has converged.

**Verification:** `./gradlew spotlessApply` ok; `./gradlew :core:application:test` for `QuickCheckSessionTest` (14/14), `TransitionsTableTest`, `PhysicalModeSessionTest` passed; `./gradlew spotlessCheck build` BUILD SUCCESSFUL.

**Residual risks:** unchanged from the first pass: the accepted `R U2 R'` match alias, and planning artifacts still state the un-narrowed VERIFIED rule.
