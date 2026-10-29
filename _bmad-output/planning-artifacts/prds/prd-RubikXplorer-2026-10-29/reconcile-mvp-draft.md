# Input reconciliation: mvp-draft-product-and-architecture.md → PRD + addendum

- **Input:** `docs/mvp-draft-product-and-architecture.md` (line numbers below refer to this file)
- **Targets:** `prd.md`, `addendum.md` (this folder)
- **Date:** 2026-10-29
- **Method:** compared every section of the draft (excluding the inline mermaid/CSS blob at line 646 and image links) against the PRD and addendum. Deliberate changes recorded in `.memlog.md` are not gaps: camera recognition P1 → P0, the 57 OLL + 21 PLL library, the ~14-week timeline, no monetization or account, English-only launch, and the deferred store name.

**Summary:** 17 gaps. 2 high, 5 medium, 10 low. There is also one internal PRD inconsistency, listed at the end.

---

## High

### G1. Product principles section dropped
- **Draft (L300–316):** four named principles. "**Teach before revealing**: explain the objective and give the learner an opportunity to think before showing the answer." "**Verify every move**." "**Explain errors constructively**: identify what went wrong, distinguish likely causes, and offer the *smallest useful correction*." "**Build independence**: gradually reduce hints and test learning on new cube scrambles."
- **PRD today:** some of the behaviour is spread across FRs. FR-5 does not reveal the next move in *Test me*/*Let me try*. FR-11 has the hint ladder. FR-10 has Certainty. FR-20 covers independent practice. The principles themselves are not stated anywhere, and "smallest useful correction" is missing entirely. No requirement says the tutor must prompt the learner to think before it demonstrates in *Guide me*/*Show me*. Downstream UX and lesson authoring have no tie-break rule for cases the FRs do not cover.
- **Should land:** PRD, a new short "Product Principles" subsection after §1 Vision. Add "smallest useful correction" as a consequence on FR-12 or FR-13.
- **Severity:** high

### G2. Tutor voice and explanation style (the "why a move matters" example) not captured
- **Draft (L29, L97–101):** "explains why a move matters". The example tutor line replacing a bare `R U R' U'`: *"We are moving this corner into its correct position while preserving the lower layer. Watch the right face first. Now perform the turn on your cube. Notice which pieces move and which remain in place."* followed by "This shifts the experience from instruction-following to learning."
- **PRD today:** FR-6 says "plain-language explanation of the Objective and the purpose of the Moves". The explanation pattern is lost. That pattern is: state the goal plus what is being preserved, direct the learner's attention, invite the action, then prompt them to notice what changed. The draft treats this as the core differentiator, and nothing in the PRD or addendum gives lesson authors or `bmad-ux` a voice or style reference.
- **Should land:** PRD FR-6. Add a consequence that every Lesson step's explanation states (a) the Objective, (b) what must stay preserved, and (c) what to watch or notice. The example quote and a short content style guide go in a new addendum section ("Explanation voice", input to `bmad-ux` and curriculum authoring).
- **Severity:** high

---

## Medium

### G3. Feedback-loop step 7 "Confirm understanding before continuing" dropped
- **Draft (L87–95):** a 7-step instructional loop ending with "7. Confirm understanding before continuing."
- **PRD today:** the tutor advances when the Completion Predicate is met (FR-4). Addendum D's flow goes straight from "satisfied" to "confirm progress". Nothing checks understanding, as opposed to state, before the tutor moves on. The draft's Gate 4 bullet (L747), "participants can explain the immediate objective", only appears as pilot metric SM-4.
- **Should land:** PRD §4.2 (FR-4/FR-5), for example an optional comprehension check or "predict what happens" prompt at Lesson end. Alternatively, record an explicit decision that understanding is validated only in the pilot. Also add the full 7-step loop to addendum D.
- **Severity:** medium

### G4. Three draft product metrics silently dropped
- **Draft (L226–233):** *Time to first solve*, *Move error rate* (incorrect moves per lesson/stage), *Retention* (learners who return to keep practicing). L176 adds that the teaching modes "provide measurable progress data".
- **PRD today:** §8 keeps first-solve completion and independent solve rate. Time-to-first-solve, move error rate and retention are gone without a recorded reason. "No analytics" (NFR-8) explains why in-app telemetry is unavailable, but each of these can still be measured another way. Time to first solve and error rate can come from the pilot or from on-device stats the learner sees. Retention can come from Play Console's retained-installer stats.
- **Should land:** PRD §8. Add these as pilot or Play Console metrics, or list them under a "considered and deferred" note.
- **Severity:** medium

### G5. "Hint dependency" metric inverted into a counter-metric; independence trend lost
- **Draft (L232, L843):** "Hint dependency: how frequently learners require help *over successive attempts*". "The product should demonstrate that learners become more independent over repeated practice."
- **PRD today:** SM-C1 says "do not push hints toward zero". That is reasonable as a guard, but the draft's actual signal, a *declining trend* in hint use across attempts, is no longer measured anywhere. SM-2 is a single post-Course check, so it does not show improvement over repeated practice.
- **Should land:** PRD §8. Add a secondary metric "hint dependency trend across successive attempts (pilot plus on-device stats)", and reword SM-C1 so it rules out optimizing *absolute* hint count, not the trend.
- **Severity:** medium (partial contradiction)

### G6. MVP Definition of Done not carried over
- **Draft (L827–843):** 11 "a new user can…" bullets. Among them: "Understand the purpose of each taught move or sequence", "Attempt a fresh scramble with reduced assistance", "Use the core experience without an internet connection". It also stresses that "successful solution playback is not the same as successful teaching."
- **PRD today:** most bullets map to individual FRs, but no user-level DoD checklist exists. The "playback ≠ teaching" warning only shows up indirectly in SM-C2. "Understand the purpose of each taught move" has no testable home.
- **Should land:** PRD §6.2 or §7, as a "MVP Definition of Done" block (it can reference FR ids). Updated for the P0 scope, it should also include scan-to-solve.
- **Severity:** medium

### G7. Test-strategy gates 2–4 detail weakened
- **Draft (L707–748):** a four-gate test plan.
  - Gate 1, property tests: covered in NFR-4 and FR-15.
  - Gate 2, tutor: "Every supported error category has an *actionable recovery path*"; "Hint escalation and mastery transitions behave according to configuration."
  - Gate 3, e2e: "Undo, pause, restart, and resume preserve valid state."
  - Gate 4, usability: "Beginners can understand move instructions *without prior notation knowledge*"; "Users can recover from deliberate mistakes"; "The test records task completion, hint use, and *points of confusion*."
- **PRD today:** §7 keeps the five release gates and NFR-4 keeps Gate 1. But FR-10 only requires scenario tests per category, not a recovery path per category. Mastery-transition tests are not required (FR-13 only tests hint thresholds). The no-prior-notation usability check and the "points of confusion" record are absent. The test strategy as a whole (gate structure, "cube engine gets the strongest coverage", L709) is not handed to architecture or QA.
- **Should land:**
  - FR-10: add a consequence that every Error Category has an actionable Recovery Path.
  - FR-20: add a consequence that mastery transitions follow configuration and are tested.
  - Gate 4 / SM-4: add the "no prior notation" check and recording of points of confusion.
  - Addendum: a new "Test strategy" section that copies the four-gate breakdown.
- **Severity:** medium

---

## Low

### G8. Data-driven lessons: the "test alternative explanations" rationale dropped
- **Draft (L825):** "A lesson should be data-driven rather than implemented as a separate screen with custom logic. That makes it easier to add languages, modify teaching rules, and *test alternative explanations*."
- **PRD today:** data-defined Lessons (§4.2) and localizability (NFR-10) are captured. Being able to swap explanation variants and teaching rules without code changes is not stated, and that is what the A/B idea (G9) depends on.
- **Should land:** PRD §4.2 description or NFR-10. One clause: explanation text and teaching rules are swappable as data.
- **Severity:** low

### G9. A/B learning experiment demoted
- **Draft (L235):** "A particularly valuable experiment would compare two groups: conventional move-by-move instructions vs contextual explanations with error feedback. Measure independent solving ability after the same amount of practice."
- **PRD today:** addendum H keeps the idea but labels it "not MVP requirements", and the PRD does not mention it. It is the only proposed direct test of the product's core thesis.
- **Should land:** PRD OQ-5 (pilot logistics). Add "decide whether the pilot runs the A/B comparison". This depends on G8.
- **Severity:** low

### G10. "No numeric confidence scores" rule lives only in the addendum
- **Draft (L661):** "Do not introduce numerical confidence scores unless the system can calibrate and validate them."
- **PRD today:** addendum D states it. The PRD defines Certainty as verified/likely/unknown and Sticker Confidence as confident/uncertain, but never forbids numeric scores. Camera work (OQ-7, ML option) makes numeric confidence tempting, and the PRD wins on conflicts.
- **Should land:** PRD §3 Glossary (Certainty / Sticker Confidence). Add "categorical only; no numeric score is shown or used for decisions until calibrated."
- **Severity:** low

### G11. Privacy items partly implicit
- **Draft (L697–705):** store progress in *app-private storage*; *avoid collecting personally identifying information*; provide a way to reset local progress.
- **PRD today:** reset is in FR-19 and NFR-8 says "no data leaves the device". App-private storage and "no PII collected even locally" are not explicit. Neither is whether FR-19 reset also clears Color Calibration (FR-24) and Algorithm Statuses.
- **Should land:** PRD NFR-8. Add app-private storage and no PII, and state what FR-19 reset covers.
- **Severity:** low

### G12. Learner traits and the "explain the moves" outcome trimmed
- **Draft (L167, L322–328):** Stage 06 "Solve new scrambles, receive hints only when needed, *and explain the moves*". Learner trait "Can follow a demonstration but struggle to reproduce it."
- **PRD today:** Stage 7 says "progressively fewer hints" only. The "follow but can't reproduce" trait is not in §2. That trait is what justifies *Test me* and *Let me try*.
- **Should land:** PRD §2 (add the trait) and the §4.2 Stage 7 outcome.
- **Severity:** low

### G13. Solver test-corpus details
- **Draft (AC-05.6 L487, L695):** solver tests cover the *solved state*, randomized legal scrambles, and invalid inputs. The benchmark corpus must be "representative… not only easy scrambles".
- **PRD today:** FR-15 and NFR-3 cover random legal states and invalid input. The already-solved input and the "not only easy scrambles" caveat are missing.
- **Should land:** PRD FR-15 / NFR-3.
- **Severity:** low

### G14. Speedcubing P1 items vanished from the roadmap
- **Draft (L120):** "Speedcubing: Timer, scrambles, solve history, averages — P1." (The later MVP section, L369, only excludes a *competitive* speedcubing platform.)
- **PRD today:** §6.1 non-goal "no competition timer suites". Personal solve timer, solve history and averages are listed in neither P1 nor P2. They may have been dropped on purpose, but no decision is recorded.
- **Should land:** PRD §6.2 out-of-scope list (P1/P2 or "not planned"), or a memlog decision.
- **Severity:** low

### G15. "Prove three riskiest components first" not reflected in the P0.1 exit criterion
- **Draft (L845–853):** a proof of concept of (1) a correct engine, (2) 3D animation in sync with it, and (3) one lesson objective that detects whether a move helps or harms progress.
- **PRD today:** addendum F quotes the advice, but the P0.1 exit criterion only covers engine, render and solve. Item 3 is deferred to P0.2.
- **Should land:** addendum F. Either add item 3 to the P0.1 exit criterion or note why it is deferred.
- **Severity:** low

### G16. Next-deliverable and separation advice not handed to architecture
- **Draft (L855, L1063–1065):** the next deliverable is a design spec with Kotlin domain models, the *lesson-definition schema*, *tutor decision rules*, *state diagrams* and *unit-test cases*. Also: "Rendering, camera gestures, animation timing, and cube-state correctness are separate problems. Solving all of them simultaneously makes debugging much harder." The learning-roadmap phase 2 calls for a button-driven Compose screen before 3D.
- **PRD today:** addendum F keeps "simple 2D first". The expected contents of the architecture document and the "separate problems" rationale are not passed on.
- **Should land:** addendum A, as a checklist of what `bmad-architecture` must produce.
- **Severity:** low

### G17. Preferences persistence
- **Draft (L204, L548):** persistence covers "Lesson progress, *preferences*, and practice history".
- **PRD today:** FR-19 omits preferences. These include animation speed, color-blind mode (NFR-7), Teaching Mode choice and calibration.
- **Should land:** PRD FR-19.
- **Severity:** low

---

## Incidental: PRD internal inconsistency (not a draft gap)
- PRD OQ-5 says Gate 4 runs "within the 10 weeks", but §6.2 and memlog moved P0 to about 14 weeks. Update OQ-5.

---

## Faithfully captured
The following came through intact or improved:
- Vision and positioning ("tutor with a solver inside", "Learn to solve the cube, not just follow a solution").
- Competitive framing and its caveat (addendum G).
- The four Teaching Modes (FR-5) and the 7-stage curriculum.
- The data-defined Lesson schema and "no hard-coded move lists" (FR-4).
- Separate gestures and non-gesture alternatives.
- Physical-mode honesty, AC-03.6 → FR-9.
- The full error-category list. "Multiple causes → likely/unknown, never false verified" (FR-10).
- The Hint Ladder. Rule-based adaptive policy with configurable thresholds.
- Solver/tutor separation and the `solveCube` vs `teachStep` rule (§4.5, addendum B).
- No LLM inventing moves or judging validity (§6.1).
- Mastery ≠ completion, with the mastery hypothesis.
- The three-state sync model and its six rules (addendum C).
- The TutorDecision fields and the verified/likely/unknown Certainty (addendum D).
- All NFR targets, with the property-test invariants in NFR-4.
- Offline operation; no account; reset progress; on-device camera privacy.
- Release gates 1–5. "Set the completion % after the pilot baseline" (SM-1).
- Camera ACs: the "54 colors ≠ valid" rule, recapture flows, manual correction.
- Technology table, cubie representation, interface sketch, hexagonal/functional-core guidance, the module-splitting advice, and the epic sketch.
