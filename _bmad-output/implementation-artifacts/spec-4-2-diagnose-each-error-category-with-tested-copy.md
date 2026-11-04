---
title: 'Diagnose each Error Category with tested copy'
type: 'feature'
created: '2026-11-03'
status: 'done'
baseline_revision: 352ab74c5ee766886f7794d087a860f3848dd4bb
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

**Problem:** The tutor never names a mistake. *Test me* still uses Story 2.6's whole-Objective `MoveFeedback.classify`, so a taught Move that lifts a solved piece for a moment (R' opening R' D' R) is called Harmful. The Harmful copy is a placeholder ("That moved a piece you need."), and no `Diagnosis` is ever produced for any Error Category (FR-10, AD-5, AD-6, UX-DR24, UX-DR25, UX-DR28).

**Approach:** Add deterministic, ordered Diagnosis rules in `:core:domain` `tutor/`. `Assess` turns them into a `Diagnosis` with Certainty from the one `CandidateEnumerator`, keyed `Text` copy and a Recovery Path prompt. Add `Assess.classify(lesson, entry, before, move, after, config)`, which judges a *Test me* Move against the step derived from `before` (`ON_PATH → HARMFUL → PROGRESS → NEUTRAL`) and replaces `MoveFeedback.classify`. The reducer stores the result, `TutorDecision.diagnosis` carries it, and the lesson panel shows the diagnosis copy in neutral ink.

## Boundaries & Constraints

**Always:**
- Certainty is produced only in `tutor/Assess.kt` (`CertaintyProducerTest` must stay green): one consistent candidate → VERIFIED, several → LIKELY, none → UNKNOWN. The rules file never names a Certainty constant.
- `classify` runs only on `MoveCommitted` while the Teaching Mode is `TEST_ME`. Every other event clears both `lastMoveFeedback` and `lastDiagnosis`, and so does a commit in any other mode.
- `ON_PATH` wins: when `before` is an authored path state and the Move is the next authored Move (as a canonical `Move` value), the result is `ON_PATH` with no Diagnosis. A quarter turn of an authored half turn counts in either direction, and the same quarter again from that half-done state also counts.
- The step is `Tutor.currentStep(lesson, entry, before)`. Its preserved pieces are `lesson.preserve.preservedPieces(before)` plus `steps[j].done.preservedPieces(before)` for every other step `j` with `Tutor.stepDone(lesson, j, before)`. Disturbed pieces are the preserved pieces that no longer appear in the same predicates' `preservedPieces(after)`.
- HARMFUL exactly when the Diagnosis is a disturbance category (`COMPLETED_LAYER_DISTURBED`, `WRONG_PIECE`, `WRONG_MOVE_OR_DIRECTION`). Otherwise the result is PROGRESS when `allOf(lesson.preserve, step.done)` progress rises, else NEUTRAL. A non-disturbance Diagnosis (orientation, twisted piece, unknown) may ride along with PROGRESS or NEUTRAL.
- The Learner's Cube State is never replaced or rewound by a Diagnosis. The UI shows diagnosis copy in `inkPrimary` with a polite live region: no red, no ✗, no shake, no haptic.
- All copy is `Text(key, args)` in `content/strings/en.json`. Piece, color and face names are ICU `select` arguments with lowercase ids, never English literals passed as args.
- `TutorDecision` keeps exactly its AD-5 fields.

**Never:** Recovery prompt buttons (*Undo* / *Guide me back* / *Try again* / *Show me how*) or the violet disturbed-piece outline (Story 4.4); `RecoveryPlanner`/`PlanRecovery`, or `Recovery.Pending/Ready` being produced (4.5); Hint Ladder (4.3); `AdaptivePolicy` (4.6); physical-mode evidence, `expected`/`observed` fields, Certainty chip (Epic 8); changing the `TEST_ME` flag level; editing planning artifacts, `_bmad/` or `.claude/skills/`; any numeric confidence.

## I/O & Edge-Case Matrix

| Scenario | Input / State | Expected Output / Behavior | Error Handling |
|----------|--------------|---------------------------|----------------|
| On path, lifts a solved piece | `lesson-03-01` entry, Test me, `R'` | `ON_PATH`, no Diagnosis; line "That's the move — keep going." | — |
| Every taught Move | every shipped Lesson, each authored Move from its path state | `ON_PATH` (never HARMFUL) | — |
| Half turn by quarters | `lesson-02-05` entry, `F`, then `F` | both `ON_PATH` | — |
| Completed layer disturbed | `lesson-03-01` entry, `R` | HARMFUL; `COMPLETED_LAYER_DISTURBED`, VERIFIED, candidates `[[R]]`, disturbed = {white-red edge}; `msg.diagnosis.completed-layer-disturbed` `{layer: cross}` → "That turn moved the cross."; path GUIDED_RECOVERY, prompt "Undo, or let me guide you back." | — |
| Wrong piece | `lesson-02-05` after `F2`, `F` | HARMFUL; `WRONG_PIECE` VERIFIED; `{moved: white-green, target: white-red}` → "That moved the white-green edge; we want the white-red edge." | — |
| Wrong move or direction (Stage 2 insert) | `lesson-02-04` after `U' R' U`, `F` | HARMFUL; `WRONG_MOVE_OR_DIRECTION` VERIFIED → "That turn moved a piece you'd placed." + UNDO prompt "Undo, or try another turn." | — |
| Wrong piece orientation | test-built states for a Lesson whose step watches white-green-red: `expected` has that corner out of its slot, `observed` = `expected`·one face turn seats it in its slot twisted (build `expected` as that twisted-in-slot state·the inverse turn) | not HARMFUL; `WRONG_PIECE_ORIENTATION` VERIFIED; `{kind: corner, face: up}` → "Right corner, but twisted — white should face up." | — |
| Unexpected orientation | any Lesson, Test me, `y` | NEUTRAL; `UNEXPECTED_ORIENTATION` VERIFIED; `{bottom: yellow, front: green}` (the entry's held D and F centers); path REORIENT, offers `[SHOW_ME_HOW]`, no UNDO | — |
| Rotation back | then `y'` (orientation equals the entry's again) | NEUTRAL, no Diagnosis | — |
| Ambiguous | `Assess.diagnose(lesson, entry, expected, observed = expected·U D, config)` with a disturbed cross | category `COMPLETED_LAYER_DISTURBED`, LIKELY (2 candidates), never VERIFIED | — |
| Unknown | observed 3 face turns from expected (maxDepth 2) | `UNKNOWN_DISCREPANCY`, UNKNOWN, no candidates; "I can't tell what changed." + RETRY prompt "Undo, or start this step again." | — |
| Invalid entered state | `Assess.diagnoseEntry(facelets)` with a twisted corner | `INVALID_ENTERED_STATE`, UNKNOWN; explanation = the validator's reason (`msg.validation.twisted-corner`); path RE_ENTER | valid facelets → null |
| Step back, nothing preserved | a Move that undoes the last off-path or on-path Move, touches no preserved piece and lowers the step's effective-done progress (the test asserts that drop) | NEUTRAL, no Diagnosis | — |
| Off path, closer | Test me Move that raises step progress, disturbs nothing | PROGRESS, "That works too — you're closer." | — |
| Not Test me | the same Moves in Let me try / Guide me / Show me | `lastMoveFeedback` and `lastDiagnosis` stay null | — |

</intent-contract>

## Code Map

- `core/domain/.../tutor/MoveFeedback.kt` -- enum `PROGRESS, NEUTRAL, HARMFUL` plus the `classify(completion, before, after)` companion. Becomes `ON_PATH, PROGRESS, NEUTRAL, HARMFUL`, and the companion goes away. `text: Text?`: ON_PATH `msg.test-me.on-path`; HARMFUL `null`, because its line is the Diagnosis.
- `core/domain/.../tutor/Assess.kt` -- `confirm`; `diagnose(category, enumerator, observed)` at :24; `decide` at :62; the `Diagnosis` private-constructor class and its `verified`/`likely`/`unknown` factories at :111-140. These are the only Certainty producers: keep new factories here.
- `core/domain/.../tutor/ErrorCategory.kt` -- the 7 categories and the exhaustive `recoveryPath`, plus `RecoveryPath`. Add `prompt: Text` and `offers: List<RecoveryOffer>` to `RecoveryPath`.
- `core/domain/.../tutor/Tutor.kt` -- `currentStep` (path walk at :24-33), `stepDone`, `shownStep`, `pathState`, and the private `stepMoves`. Reuse the path walk for the on-path test.
- `core/domain/.../tutor/CandidateEnumerator.kt` -- `CandidateEnumerator(expected, maxDepth, moveSet)`, `consistentWith(observed)`, and `of(expected, config)`. Equality includes orientation, so build `expected` re-held in `observed`'s orientation (`CubeState(expected.cubies, observed.orientation)`; the constructor is internal to `:core:domain`).
- `core/domain/.../predicate/{WhiteLayer,WhitePieceSolved,WhiteCrossSolved,WhiteCornersSolved,AllOf,AnyOf,StateReached,AlwaysTrue}.kt` -- `WhiteLayer.correctPieces` is the color-anchored white-layer reading. `WhitePieceSolved.PIECES` (private) holds the 8 canonical names; expose an `internal fun nameOf(piece: Piece): String?`. `StateReached.preservedPieces` returns every matching slot, including non-white pieces.
- `core/domain/.../cube/{CubeState,Orientation,FaceletTables,CubeValidator}.kt` -- `CubeState(cubies, orientation)`; `FaceletTables.center(face)`; `CubeValidator.validate(facelets)` → `ValidationResult.Invalid.reason: Text`.
- `core/domain/src/main/resources/content/strings/en.json` -- `msg.test-me.*` at :10-12 (`msg.test-me.harmful` is removed). `ShippedContentTest` scans `Text("…", mapOf(...))` calls in main code against en.json argument sets, so pass args as literal `mapOf` entries.
- `core/domain/src/test/.../tutor/{AssessTest,MoveFeedbackTest,TutorFixtures,CertaintyProducerTest}.kt` -- `MoveFeedbackTest` tests the removed classifier: rewrite it for `Assess.classify`. Update `AssessTest` callers of `diagnose`.
- `core/application/.../session/{Reduce,SessionState,TRANSITIONS.md}` -- `committed` at Reduce :93-100 calls `MoveFeedback.classify` in every mode. `lastMoveFeedback` is set and cleared at :54/:76/:121/:184/:191; add `lastDiagnosis` beside it, with the same clears. The `decision` lazy is at SessionState :65. The TRANSITIONS.md tutor field list is at :12, with prose at :69-70, and `TransitionsTableTest` parses it (tutor tuple at :157).
- `core/application/src/test/.../session/{TutorEventsTest,ShippedLessonPathsTest,SolvePlaybackSessionTest,TransitionsTableTest,Lessons.kt}` -- the old-classifier assertions (TutorEventsTest :175-237 "other modes record the feedback", ShippedLessonPathsTest :103-120 harmful row) need the new semantics.
- `app/src/main/kotlin/dev/rubikxplorer/app/ui/lesson/{LessonScreen,LessonPanel}.kt` -- `stepCard` builds `feedback` from `decision.feedback?.text` (LessonScreen :320). `LessonPanel.Feedback` (:205) is the neutral live-region line. `StepCardContent.feedback: String?` is at LessonPanel :63.
- `app/src/test/kotlin/dev/rubikxplorer/app/ui/lesson/LessonScreenTest.kt` -- :402-410 assert the placeholder "That moved a piece you need."; `app/src/test/.../content/ContentStringsTest.kt` is the Robolectric ICU pattern for exact-copy tests.

## Tasks & Acceptance

**Execution:**
- [ ] `core/domain/.../tutor/MoveFeedback.kt` -- add `ON_PATH`, make `text` nullable (HARMFUL null), and delete the `classify` companion -- AD-5 closed enum; Story 2.6's classifier is replaced.
- [ ] `core/domain/.../tutor/ErrorCategory.kt` -- `RecoveryPath.prompt: Text` (`msg.recovery.undo|guided-recovery|retry|reorient|re-enter`) and `offers: List<RecoveryOffer>`; new `enum RecoveryOffer { UNDO, GUIDE_ME_BACK, TRY_AGAIN, SHOW_ME_HOW, RE_ENTER }`. UNDO, GUIDED_RECOVERY and RETRY offer `[UNDO, GUIDE_ME_BACK, TRY_AGAIN]`; REORIENT offers `[SHOW_ME_HOW]`; RE_ENTER offers `[RE_ENTER]`. Use exhaustive `when` -- AC 2 (*Show me how*, no undo) and the 4.4 prompt order.
- [ ] `core/domain/.../tutor/DiagnosisRules.kt` (new, internal) -- pure rule table per Design Notes: `fun findings(lesson, entry, expected, observed): Finding?` with `Finding(category, explanation: Text, disturbed: Set<Piece>)`, plus the on-path test `isOnPath(lesson, entry, before, move)` (or put it on `Tutor`). It never mentions Certainty -- keeps the producer rule.
- [ ] `core/domain/.../predicate/WhiteLayer.kt`, `WhitePieceSolved.kt` -- add `misorientedInSlot(state, piece)` (the piece's colors sit in the slot whose centers are its colors, but it is not correct) and `internal nameOf(piece)` -- color-anchored helpers for the rules.
- [ ] `core/domain/.../tutor/Assess.kt` -- `Diagnosis` gains `explanation: Text` and `disturbed: Set<Piece>`, and the factories take them. `diagnose(category, enumerator, observed, explanation, disturbed = emptySet())`. New `diagnose(lesson, entry, expected, observed, config): Diagnosis?` (rules + candidates from `CandidateEnumerator.of(re-held expected, config)`), `diagnoseEntry(facelets): Diagnosis?`, `classify(lesson, entry, before, move, after, config): MoveAssessment` (`data class MoveAssessment(feedback, diagnosis: Diagnosis?)`). `decide` takes `lastDiagnosis`: `diagnosis` is that diagnosis in TEST_ME while `!advance`, else null, and `explanation` is the diagnosis's explanation when shown -- AC 1, 3, 4, 5.
- [ ] `core/domain/src/main/resources/content/strings/en.json` -- remove `msg.test-me.harmful`; add `msg.test-me.on-path`, `msg.diagnosis.*` (6 keys, not invalid-entry) and `msg.recovery.*` (5 keys) with the copy in Design Notes -- UX-DR24/25.
- [ ] `core/domain/src/test/.../tutor/` -- `DiagnosisScenarioTest.kt`: one test per ErrorCategory, asserting category, Certainty, `Text` key+args and Recovery Path, plus the ambiguous (LIKELY) and unknown rows; `ClassifyTest.kt` (replaces `MoveFeedbackTest.kt`): every row of the classification matrix, including every shipped Lesson's authored Moves → ON_PATH and quarter-of-half; update `AssessTest`, `TutorVocabularyTest` (RecoveryOffer, MoveFeedback entries) -- matrix coverage.
- [ ] `core/application/.../session/{SessionState,Reduce,TRANSITIONS.md}` -- `lastDiagnosis: Diagnosis?` beside `lastMoveFeedback`. `committed` calls `Assess.classify` only when `teachingMode == TEST_ME` (else both null). Every place that clears `lastMoveFeedback` also clears `lastDiagnosis`. `decision` passes it. Update the tutor field list and prose -- AD-3, AD-5 "only on MoveCommitted in Test me".
- [ ] `core/application/src/test/.../session/` -- update `TutorEventsTest`, `ShippedLessonPathsTest` (the harmful row becomes the classify/Diagnosis expectation; add: in TEST_ME through `reduce`, every authored Move gives `lastMoveFeedback == ON_PATH`, and `decision.feedback == ON_PATH` whenever `!advance`), `TransitionsTableTest`, `SolvePlaybackSessionTest`; new `DiagnosisSessionScenarioTest.kt`: Harmful taught-insert scenarios for Stage 2 (`lesson-02-04`, `lesson-02-05`) and Stage 3 (`lesson-03-01`). Each asserts the decision's diagnosis and explanation, the state not replaced (`virtual == entry·moves`, Undo restores it), `recovery == Unavailable`, and the clears.
- [ ] `app/src/main/kotlin/dev/rubikxplorer/app/ui/lesson/DiagnosisLine.kt` (new), `LessonPanel.kt`, `LessonScreen.kt` -- `StepCardContent` gains `diagnosis: String?` and `recoveryPrompt: String?`. When `decision.diagnosis` is set, the panel shows `DiagnosisLine` (tag `lesson:diagnosis`; explanation, then prompt, `inkPrimary`, polite live region, no icon or animation) instead of `Feedback`. Otherwise `Feedback` shows `decision.feedback?.text` -- UI renders the decision only.
- [ ] `app/src/test/kotlin/dev/rubikxplorer/app/ui/lesson/LessonScreenTest.kt`, new `app/src/test/.../content/DiagnosisCopyTest.kt` -- Robolectric: shipped en.json resolves each diagnosis, recovery and Test me Text to the exact Design Notes copy, including the EXPERIENCE literals "Your cube is turned — hold it with white on the bottom, green in front." (`bottom=white, front=green`) and "Right corner, but twisted — white should face down." (`kind=corner, face=down`). Update the LessonScreen Test me test: the harmful line now shows the diagnosis copy, the ON_PATH line shows, and `lesson:diagnosis` uses no red (assert text only; color is fixed in code review).

**Acceptance Criteria:**
- Given one scenario test per Error Category, when each runs, then the expected category, Certainty and `Text` copy are produced, and the ambiguous and out-of-reach evidence yields LIKELY and UNKNOWN, never VERIFIED.
- Given an orientation-only difference, when it is diagnosed, then it is `UNEXPECTED_ORIENTATION` with the turned-cube copy and Recovery Path REORIENT offering *Show me how* and not *Undo*.
- Given *Test me* on any shipped Lesson, when the Learner commits the next authored Move from its path state, then it is classified ON_PATH, never HARMFUL, and the line reads "That's the move — keep going." while no Notice shows.
- Given *Test me* and an off-path Move, when it is committed, then it is PROGRESS, NEUTRAL or HARMFUL against the current step's preserved pieces and effective-done progress, not whole-Objective progress.
- Given a Harmful Move in *Test me* (Stage 2 and Stage 3 taught inserts), when it is committed, then `decision.diagnosis` holds the category copy and its Recovery Path prompt, `MoveFeedback.classify` and `msg.test-me.harmful` no longer exist, and the lesson panel shows the copy in neutral ink.
- Given any Diagnosis, when it is shown, then the session's Cube State is the Learner's own (not replaced), and no red fill, ✗ or shake appears.

## Spec Change Log

## Review Triage Log

### 2026-11-03 — Review pass
- verdicts: 19 findings — high 0, medium 0, low 11, false 8, maybe-false 0 (the edge-case layer reported no findings)
- findings:
  - `[low]` `[reject]` BH-1: a face turn after a Cube Rotation stops the orientation diagnosis — this is the specified rule (AC 2 names an orientation-only difference), and predicates are orientation-invariant, so step guidance keeps working; tracking the hold separately adds a branch and state.
  - `[low]` `[reject]` BH-2: the orientation copy names the entry hold, which is wrong after an authored rotation — no shipped Lesson authors a Cube Rotation; deriving the hold from the path adds machinery for content that does not exist.
  - `[low]` `[reject]` BH-3: `isOnPath` matches any repeated path state while `currentStep` takes the first — no shipped path revisits a state (carried from the 4.1 triage); a guard adds a validator rule.
  - `[low]` `[reject]` BH-4: `currentStep` ignores half-done half-turn states — off the path it falls back to the first unmet step, which is the same step for every shipped half turn (02-05, 03-03), and the next quarter classifies ON_PATH; folding these states into the path walk changes 4.1's derivation.
  - `[low]` `[reject]` BH-5: flattening `anyOf` may pick a target from one branch — no shipped `anyOf` holds a `white-piece-solved` leaf; preserving branch semantics adds complexity.
  - `[low]` `[patch]` BH-6: the flipped-edge branch of WRONG_PIECE_ORIENTATION is untested — added an edge scenario to `DiagnosisScenarioTest` (VERIFIED, `{kind: edge, face: up}`, GUIDED_RECOVERY).
  - `[low]` `[reject]` BH-7: orientation tests cover only `y` from the home hold — the bottom/front args come from the entry, not the rotation axis, so other axes exercise no other code.
  - `[low]` `[reject]` BH-8: `quartersOf` wide/slice/rotation branches are untested — no shipped Lesson authors those half turns; the branches are one-line mirrors of the tested face case.
  - `[low]` `[patch]` BH-9: off-path wide and slice Moves are never classified — added `M` (LIKELY, two candidates) and `r` (VERIFIED, `[L]`) tests to `ClassifyTest`.
  - `[low]` `[patch]` BH-10: the diagnosis live region is unverified on screen (grouped with VG-2) — `LessonScreenTest` asserts `LiveRegion == Polite` on `lesson:diagnosis`.
  - `[low]` `[patch]` VG-1: a NEUTRAL diagnosis (turned cube) is never rendered in a UI test — added a Test me `y` scenario asserting the diagnosis and reorient prompt show and `lesson:feedback` is absent.
  - `[low]` `[patch]` VG-2: diagnosis live-region semantics are unasserted (grouped with BH-10) — same fix.
  - `[false]` `[reject]` IA-1: UNKNOWN_DISCREPANCY and INVALID_ENTERED_STATE are not reachable in a virtual session — AC 1 asks for one scenario test per category, which `DiagnosisScenarioTest` provides; a single virtual Move is always within reach, and physical evidence (where unknown arises) is Epic 8.
  - `[false]` `[reject]` IA-2: wrong-piece and unknown copy differ from EXPERIENCE — the wrong-piece example names a corner by two colors, so the canonical-name pattern is recorded in Design Notes; the "scan the front and right faces" line is physical-mode Do/Don't copy, not a State Patterns row.
  - `[false]` `[reject]` IA-3: "white on the bottom" appears only with hand-fed args — shipped Lessons hold white on top, so the honest copy for them is "yellow on the bottom"; the EXPERIENCE literal is the same message for a white-bottom hold, pinned in `DiagnosisCopyTest`.
  - `[false]` `[reject]` IA-4: *Show me how* exists only as domain data, with no control — the Diagnosis offers `[SHOW_ME_HOW]` and no UNDO, as AC 2 states; Story 4.4's first AC owns rendering the inline recovery prompt's offers.
  - `[false]` `[reject]` IA-5: the recovery prompt is text, not buttons — same: epics Story 4.4 AC 1 defines the button prompt; 4.2 delivers the prompt sentence and offers.
  - `[false]` `[reject]` IA-6: no-red holds only by construction, and the violet outline is missing — the violet outline is Story 4.4 AC 2; `DiagnosisLine` uses `inkPrimary` with no icon or animation, which is the stated check.
  - `[false]` `[reject]` IA-7: feedback is now computed only in *Test me* — this is AD-5 ("computed only on MoveCommitted in Test me") and AC 4.

## Design Notes

**Rule order** in `findings(lesson, entry, expected, observed)`. `s` is the current step at `expected`, and `held = CubeState(expected.cubies, observed.orientation)`:
1. `observed.cubies == expected.cubies`, `observed.orientation != expected.orientation` and `!= entry.orientation` → UNEXPECTED_ORIENTATION. Args: `bottom`/`front` are the entry's colors on the Learner's D and F centers.
2. No candidate from `held` explains `observed` → UNKNOWN_DISCREPANCY. Assess handles this rule: it is the empty-candidate case.
3. disturbed ∩ `lesson.preserve.preservedPieces(expected)` ≠ ∅ → COMPLETED_LAYER_DISTURBED. `layer` is `cross` when every disturbed layer piece is a white edge, `first-layer` when all are white-layer pieces, else `other`.
4. disturbed ≠ ∅, step `s`'s `done` has a `WhitePieceSolved` leaf (walk allOf/anyOf) not correct at `expected` (the first is the target), and a disturbed piece has a canonical name (the first in `PIECES` order is moved) → WRONG_PIECE `{moved, target}`.
5. disturbed ≠ ∅ → WRONG_MOVE_OR_DIRECTION.
6. A watched piece of step `s` is `misorientedInSlot` at `observed` and was not at `expected` → WRONG_PIECE_ORIENTATION `{kind: edge|corner, face}`. Watched pieces: `WhiteCrossSolved` → 4 white edges; `WhiteCornersSolved` → 4 white corners; `WhitePieceSolved` → its piece; combinators → union. `face` is the Learner face holding the WHITE center (`up|down|front|back|left|right`).
7. else no Diagnosis.

Certainty comes from the candidates in Assess: 1 → `verified`, >1 → `likely`, 0 → rule 2's `unknown`. A single face turn is unique at maxDepth 2. A slice Move gives two commuting candidates (LIKELY), and that is honest.

**Copy** (en.json; `S(x)` = an ICU select over the lowercase ids, with an `other` branch):
- `msg.test-me.on-path`: "That's the move — keep going."
- `msg.diagnosis.unexpected-orientation`: "Your cube is turned — hold it with {bottom S} on the bottom, {front S} in front." (colors white/yellow/green/blue/red/orange)
- `msg.diagnosis.wrong-piece`: "That moved the {moved S}; we want the {target S}." Each select maps the 8 canonical names to "white-green edge" … "white-green-red corner".
- `msg.diagnosis.wrong-piece-orientation`: "Right {kind S: corner/edge}, but {kind S: twisted/flipped} — white should face {face S}."
- `msg.diagnosis.completed-layer-disturbed`: "That turn moved the {layer S: cross/first layer/pieces you'd solved}."
- `msg.diagnosis.wrong-move-or-direction`: "That turn moved a piece you'd placed."
- `msg.diagnosis.unknown-discrepancy`: "I can't tell what changed."
- `msg.recovery.undo`: "Undo, or try another turn." · `guided-recovery`: "Undo, or let me guide you back." · `retry`: "Undo, or start this step again." · `reorient`: "Turn it back with the rotation keys." · `re-enter`: "Fix the outlined stickers and enter your cube again."

EXPERIENCE's wrong-piece example ("white-orange corner; we want white-red-green") names a corner by two colors, and its color order differs from the shipped canonical names. The copy keeps the pattern and uses the canonical piece names. Shipped Lessons hold white on top, so the orientation and twist copy read "yellow on the bottom" and "face up". The EXPERIENCE literals are the `white`/`down` argument values, which the app copy test pins.

**Recovery prompt split:** Story 4.2 delivers the prompt as copy, meaning each Recovery Path's sentence plus `offers`. Story 4.4 renders `offers` as inline buttons in that order (epic context: "4.4 adds the recovery prompt"). The violet outline uses `Diagnosis.disturbed` in 4.4.

INVALID_ENTERED_STATE has no Cube State, so no candidate can exist, and its Certainty is UNKNOWN. The validator's reason is the explanation. No UI is wired: the Editor already shows those reasons.

## Verification

**Commands:**
- `export JAVA_HOME=/usr/lib/jvm/java-25-openjdk-amd64 ANDROID_HOME=$HOME/Android/Sdk && ./gradlew spotlessApply && ./gradlew spotlessCheck build` -- expected: BUILD SUCCESSFUL, covering every JVM and Robolectric test (`DiagnosisScenarioTest`, `ClassifyTest`, `DiagnosisSessionScenarioTest`, `ShippedLessonPathsTest`, `CertaintyProducerTest`, `DiagnosisCopyTest`), Kover ≥ 80% on `:core:*`, and the boundary and learner-text gates.

## Auto Run Result

- **Summary:** Added ordered, deterministic Diagnosis rules (`tutor/DiagnosisRules.kt`) and `Assess.diagnose(lesson, entry, expected, observed, config)`, `Assess.diagnoseEntry(facelets)` and `Assess.classify(...)`. Certainty still comes only from `Assess` via one `CandidateEnumerator` over the expected state re-held in the observed orientation. `MoveFeedback` is now `ON_PATH | PROGRESS | NEUTRAL | HARMFUL`, judged against the step derived from `before`; Story 2.6's whole-Objective classifier and `msg.test-me.harmful` are gone. `Diagnosis` carries keyed copy and disturbed pieces, and `RecoveryPath` carries a prompt and ordered `offers` (REORIENT offers only *Show me how*). The reducer classifies only in *Test me* and stores `lastDiagnosis`; the decision exposes it, and a new `DiagnosisLine` shows it in neutral ink.
- **Files:**
  - `core/domain/.../tutor/{Assess,DiagnosisRules,ErrorCategory,MoveFeedback,Tutor}.kt`: rules, classifier, Diagnosis fields, recovery prompts and offers, on-path test.
  - `core/domain/.../predicate/{WhiteLayer,WhitePieceSolved}.kt`: misoriented-in-slot and piece-name helpers.
  - `core/domain/src/main/resources/content/strings/en.json`: on-path, diagnosis and recovery copy.
  - `core/application/.../session/{Reduce,SessionState,TRANSITIONS.md}`: Test-me-only classification, `lastDiagnosis`.
  - `app/.../ui/lesson/{DiagnosisLine,LessonPanel,LessonScreen}.kt`: diagnosis line.
  - Tests: `ClassifyTest`, `DiagnosisScenarioTest`, `DiagnosisSessionScenarioTest`, `DiagnosisCopyTest` (new), and updates to `ShippedLessonPathsTest`, `TutorEventsTest`, `LessonScreenTest` and others; `MoveFeedbackTest` removed.
- **Review:** 19 findings. 5 low patched in 4 test additions (flipped edge, slice/wide classification, on-screen turned-cube diagnosis, live region). 0 deferred. 14 rejected (6 low, 8 false), with reasons in the triage log.
- **Follow-up review recommended:** false. Patched counts: high 0, medium 0, low 5; all patches are tests only.
- **Verification:** `./gradlew spotlessApply && ./gradlew spotlessCheck build` reached BUILD SUCCESSFUL before review and again after the patches.
- **Residual risks:**
  - Select ids use `_` (`white_green`, `first_layer`) because ICU select keywords cannot hold `-`. The implementer added a "piece actually moved" filter to disturbed pieces, because an `anyOf` branch switch over-reported them.
  - The diagnosis clears with `lastMoveFeedback` (the existing list), so `RevealRequested`, a Reset that only asks, `ResetDeclined` and a same-mode change keep it; the Always line says every other event clears it.
  - At boundaries after a `state-reached` step `done` (01-02, 01-03, 02-02, 02-04), every non-taught Move is Harmful, because that predicate preserves all 20 pieces.

