---
title: RubikXplorer — MVP Product Requirements Document
status: final
created: 2026-10-29
updated: 2026-11-20
---

# PRD: RubikXplorer
*Store name: **Cubentia** (koo-BEN-tyah), chosen 2026-11-08 (OQ-1). RubikXplorer remains the codename in planning documents.*

## 0. Document Purpose

This PRD defines the MVP of RubikXplorer, an Android app that teaches complete beginners to solve a 3×3 cube and then leads them gradually through the full last-layer Algorithm set. It is written for the solo developer-owner (hebelmx) and for the downstream BMAD workflows that build on it: `bmad-ux`, `bmad-architecture` and `bmad-create-epics-and-stories`.

It distills the working draft `docs/mvp-draft-product-and-architecture.md`. Technology direction, the cube-state representation, interface sketches, the tutor decision model, the explanation voice guide, the test strategy and the roadmap all live in `addendum.md` next to this file. They are not repeated here, and the PRD wins on any conflict.

The Glossary (§3) fixes the vocabulary. Features are in §4, with globally numbered FRs nested under them. Inferred items are tagged `[ASSUMPTION: …]` inline and indexed in §11.

## 1. Vision

Most cube apps are solvers with a tutorial bolted on: they hand over a string of notation and leave the learner to it. RubikXplorer is a **tutor with a solver inside it**. It explains what the Learner is trying to achieve and why each Move matters. It demonstrates on an animated 3D cube and lets the Learner perform the Move on a virtual or physical cube. It checks the result against a verified cube model and, through the phone camera, against the real cube itself. When the Learner makes a mistake, it gives a targeted correction instead of a reset.

The path continues after the first solve. A Learner who can solve with the beginner method can move on to a faster last layer: first the two-look approach, a small set of Algorithms, and then, gradually, all 57 OLL and 21 PLL Algorithms. Each is an engine-generated animation the Learner can pause, step through and inspect from any angle. Advanced methods such as CFOP come later on the same foundation.

> **Learn to solve the cube, not just follow a solution.**

The product has a second purpose. It is a **portfolio piece** for the developer's job search. Code quality, the architecture (functional core, hexagonal ports) and a polished Play Store release are deliverables in their own right.

### 1.1 Product Principles

These principles break ties wherever the FRs leave a choice to UX, curriculum authoring or architecture.

1. **Teach before revealing.** State the Objective and give the Learner a chance to think before showing the answer.
2. **Verify every Move.** Keep a known Cube State and compare it with what was observed or entered.
3. **Never claim what you don't know.** Without evidence from the Learner, a Scan or the engine, Certainty is *unknown*. Ask; don't assert.
4. **Explain errors constructively.** Say what went wrong, separate the likely causes, and offer the smallest useful correction.
5. **Build independence.** Reduce help gradually and test learning on fresh Scrambles. Playing back a Solution is not the same as teaching one.

## 2. Target User

### 2.1 Jobs To Be Done

- **Functional:** "Help me solve the cube I own, and actually understand what I'm doing so I can do it again without the app."
- **Functional (reproduce):** "I can follow a demonstration but can't reproduce it on my own. Make me do it, and catch me when I get it wrong."
- **Functional (progression):** "Now that I can solve it, help me get faster by learning the real last-layer Algorithms a few at a time."
- **Emotional:** "Don't make me feel stupid when I mess up. Show me what went wrong and how to get back."
- **Contextual:** "Work with the real cube in my hands, not only a cube on screen, and work offline."
- **Builder (portfolio):** "Show a hiring manager, within minutes, a polished, correct, well-engineered Android app."

### 2.2 Non-Users (v1)

- Speedcubers who already know CFOP and want timers, competition Scrambles or leaderboards.
- Owners of Bluetooth smart cubes who expect automatic Move tracking.
- People who only want a Solution. They can use **Solve my cube** (UJ-3), but the product is not optimized for them. UJ-3 is a secondary journey whose job is to turn them into Learners.

### 2.3 Key User Journeys

- **UJ-1. Ana solves her first cube with the real thing in her hands.**
  - **Persona + context:** Ana, 34, got a cube as a gift and has never solved one. She doesn't know Notation and keeps losing track of which side is the front.
  - **Entry state:** fresh install, offline, no account.
  - **Path:**
    1. Ana opens the app and starts *Cube fundamentals*. She learns centers, edges, corners and what `R` means by turning the virtual cube.
    2. In *First cross* she switches to **Physical-cube Mode**, watches the **Demonstration**, does the turn on her cube, and taps *Done*.
    3. In *First-layer corners* she turns the wrong way without realizing it and reports "my cube doesn't match."
    4. The tutor doesn't guess. It says it can't tell what happened yet and offers a **Quick Check**.
    5. The scan shows that her cross was disturbed. Now with Certainty *verified*, the tutor explains this and offers *Undo* or **Guided recovery**.
  - *v1.0 (no camera):* at step 4 the tutor goes straight to "Which turn did you make?" (FR-9, rung 2). The Quick Check rung (steps 4–5) arrives in v1.1.
  - **Climax:** sessions later, the final Stage ends with her physical cube solved. The app confirms it and offers **Independent practice** on a new Scramble. *v1.0: she confirms with* Yes, it's solved*; v1.1 adds the Quick Check.*
  - **Resolution:** her progress is saved. The home screen shows her next step: practice without hints, then *Faster last layer*.
  - **Edge cases:**
    - If she has no camera permission, the tutor instead asks which turn she made, or which face looks wrong.
    - If she closes the app mid-Lesson, reopening it returns her to the last **Checkpoint** and asks her to confirm, or scan, that her cube still matches the **Expected State**.

- **UJ-2. Diego moves from beginner to two-look last layer.**
  - **Persona + context:** Diego, 17, can solve with the beginner method, but the last layer takes him two minutes.
  - **Path:**
    1. He opens *Faster last layer*.
    2. The app teaches 2-look OLL (orient the last layer in two steps) and 2-look PLL (permute it in two steps), one **Case** at a time.
    3. Each Case has an animated Demonstration, a recognition diagram, and *Guide me* practice on the virtual cube.
    4. He marks the Algorithms he is working on as *Learning*, and later as *Learned*.
  - **Climax:** he solves the last layer of a fresh Scramble using only 2-look Algorithms, and the app shows that he used no hints.
  - **Resolution:** the **Algorithm Library** shows the 2-look Algorithms he has learned out of the 78, and the full OLL/PLL sets are open for him to explore next.

- **UJ-3. Lucía just wants her stuck cube solved** *(secondary journey — conversion)*.
  - **Persona + context:** Lucía scrambled her sibling's cube and wants it restored today, not a course.
  - **Path:**
    1. She opens **Solve my cube** and does a **Quick Scan**: two photos of the cube, each held by a different corner.
    2. The app reads 52 stickers confidently and highlights two it is unsure of (orange or red, under her kitchen light).
    3. She corrects them, the state passes validation, and the **Solver** returns a **Solution**.
  - **Climax:** **Solution Playback** walks her through each Move with a short note. She steps forward and back as she turns her cube.
  - **Resolution:** her cube is solved. The app offers, without insisting, "Want to learn how to do this yourself?"
  - **Edge case:** glare stops Quick Scan from reading the bottom face, so the app asks her to scan only that face with the **Guided 6-face Scan**. Without a camera she types the colors into the **Manual Cube Editor** instead.
  - *v1.0 (no camera):* she enters the colors in the Manual Cube Editor (steps 1–2 become manual entry); Quick Scan arrives in v1.1.

- **UJ-4. Marco picks up where his cube is, not where the course starts.** *(v1.1)*
  - **Persona + context:** Marco, 12, half-learned from a video. He can make the white cross and a few corners, then gets lost.
  - **Path:**
    1. On first launch he chooses *Start from my cube* and does a Guided 6-face Scan.
    2. The tutor evaluates each Stage Predicate on the scanned state and suggests: "Start at *First-layer corners*?"
    3. He accepts. Stages 1–2 are marked *skipped by placement*, and Stage 3 opens.
  - **Climax:** in Physical-cube Mode he turns the wrong way, and a Quick Check shows he did `R` instead of `R'`. That is the only Move consistent with what the scan saw, so the tutor reports it as *verified* and shows the fix.
  - **Resolution:** he completes the layer. Stages 1–2 stay open to revisit but are not forced on him, and his progress is saved.

- **UJ-5. Diego asks "which OLL is this?"** *(v1.1)*
  - **Persona + context:** Diego (UJ-2) is practicing on a friend's Scramble and doesn't recognize the top-layer pattern.
  - **Path:** he solves the first two layers, opens *Recognize case*, and photographs the cube from one top corner, then from the opposite one.
  - **Climax:** the app reports "OLL 45 · T-shape". He taps it, and the app opens that Algorithm in the Algorithm Library and plays its Demonstration.
  - **Resolution:** he performs the Algorithm, and the app offers to set it to *Learning*. He accepts.

- **UJ-6. Laura, a hiring manager, evaluates the developer in five minutes.**
  - **Persona + context:** Laura screens Android candidates. She has the Play Store link and, once it is public, the repository.
  - **Path:** she installs the app, turns the cube, opens an OLL Algorithm in the library, scrubs through its animation, and tries a scan (v1.1).
  - **Climax:** it is smooth, correct and accessible, with no crash, no sign-up and no ads.
  - **Resolution:** she opens the repo. Its README shows screenshots, an architecture overview and green CI, and the code has a pure-Kotlin domain core with meaningful tests (§7). She shortlists the candidate.

## 3. Glossary

*These terms are used exactly as written throughout the PRD and downstream documents.*

- **Learner** — the person using the app to learn. No account; one Learner profile per install.
- **Cube State** — the logical, canonical state of a 3×3 cube: the permutation and orientation of its corners and edges. Sticker colors are derived from it.
- **Move** — a single turn written in **Notation**. The types are: face turns (U, D, L, R, F, B); wide turns (u, d, l, r, f, b); slice turns (M, E, S); and **Cube Rotations** (x, y, z). Each can be a quarter turn or a half turn, in either direction.
- **Notation** — standard Singmaster notation (e.g. `R U R' U'`).
- **Cube Rotation** — reorienting the whole cube (x, y, z). It changes the reference frame, not whether the cube is solved.
- **View Rotation** — rotating the on-screen camera. It never changes the Cube State.
- **Virtual State** — the Cube State of the on-screen interactive cube.
- **Expected State** — the Cube State the tutor believes the physical cube is in, based on confirmed Moves.
- **Physical State** — the real cube's actual state. It is unknown until the Learner enters it or a Scan observes it.
- **Course** — the ordered Stages that take a Learner from zero to a first solve and then to Independent practice.
- **Stage** — one unit of the Course (e.g. *First cross*). It has prerequisites, Lessons, a **Stage Predicate** and a **Mastery Condition**.
- **Stage Predicate** — the Completion Predicate of a Stage's final Objective (e.g. "the first layer is solved"). Placement (FR-27) uses it.
- **Lesson** — a data-defined teaching unit inside a Stage. It has an Objective, a Demonstration, a learner action, a Completion Predicate and a Recovery Path.
- **Objective** — what the Learner is trying to achieve right now, stated in plain language.
- **Completion Predicate** — a rule evaluated on the Cube State that decides whether an Objective is met (e.g. "the white cross is solved and aligned with the centers").
- **Demonstration** — an engine-generated 3D animation of a Move sequence. It has controls to step, pause, replay and change speed, and it shows highlighted pieces and arrows.
- **Teaching Mode** — one of four ways to run a Lesson: *Show me*, *Guide me*, *Test me*, *Let me try*.
- **Physical-cube Mode** — the Learner works on a real cube and confirms Moves, and the app maintains the Expected State.
- **Diagnosis** — the tutor's classification of a discrepancy into a supported **Error Category**, with a **Certainty**.
- **Certainty** — how sure the tutor is of a Diagnosis: *verified*, *likely* or *unknown*. These categories are the only form of certainty; no numeric score is shown or used for decisions.
- **Hint Ladder** — escalating help: a conceptual clue, then a highlighted target or Move, then a full Demonstration.
- **Recovery Path** — a defined way back to a valid position for the current Objective: retry, undo or **Guided recovery**.
- **Checkpoint** — a saved Cube State plus Lesson position, which the Learner can resume from.
- **Mastery Condition** — the per-Stage rule that marks a Stage *mastered*. It is stricter than *completed*.
- **Placement** — starting the Course from a scanned Cube State (FR-27). Stages before the suggested one are marked *skipped by placement*.
- **Scramble** — a legal random Cube State, produced by applying a random Move sequence.
- **Manual Cube Editor** — the screen for entering and validating the 54 sticker colors.
- **Solver** — finds a Solution for a valid Cube State. It does not teach.
- **Solution** — a Move sequence that takes a given Cube State to solved.
- **Solution Playback** — a step-through presentation of a Solution, with a short note per Move.
- **Last Layer (LL)** — the final layer in layer-by-layer methods.
- **OLL** — Orientation of the Last Layer. The full set has **57** Cases.
- **PLL** — Permutation of the Last Layer. The full set has **21** Cases.
- **2-look LL** — solving the LL with a reduced set of Algorithms, in four steps:
  - 2-look OLL: orient the edges, then orient the corners.
  - 2-look PLL: permute the corners, then permute the edges.

  [ASSUMPTION: about 16 Cases (3 + 7 OLL, 2 + 4 PLL). Every 2-look Algorithm is an Algorithm Library entry. A 2-look Case may be solved by a sequence of Library Algorithms, e.g. the edge-orientation "dot" Case.]
- **Case** — one recognizable LL pattern. Each Case has one primary Algorithm.
- **Algorithm** — a named Notation sequence that solves a Case. Every Algorithm is verified by the engine.
- **Algorithm Library** — the browsable catalog of all 78 OLL and PLL Algorithms, grouped by family.
- **Algorithm Status** — the Learner's own label for an Algorithm: *Not started*, *Learning* or *Learned*. Only the Learner changes it; the app may offer a change.
- **Scan** — observing a physical cube with the camera. It comes in three forms:
  - the **Guided 6-face Scan**: live, face by face;
  - **Quick Scan**: 2–3 corner-angle captures with three faces each;
  - the partial **Quick Check**: only the faces needed to verify recent Moves.
- **Scan Result** — the 54 sticker colors read by a Scan, each with a **Sticker Confidence** (*confident* or *uncertain*, categorical only). A Scan Result becomes a Cube State only after validation (FR-14).
- **Color Calibration** — per-device reference colors taken from the cube's six centers and used to classify stickers.

## 4. Features

### 4.1 Interactive 3D Cube

**Description:** the centerpiece of the app. A 3×3 cube rendered in 3D that the Learner can turn and inspect from any angle. Gestures for View Rotation and for face turns are clearly separate, and an ambiguous gesture never changes the Cube State. On-screen buttons offer every Move type as a non-gesture alternative, for accessibility and precision. Realizes UJ-1, UJ-6.

#### FR-1: Render and turn the cube
The Learner can see a 3×3 cube (6 faces × 9 stickers) and apply Moves. Gestures cover face turns, slice turns, wide turns and Cube Rotations; buttons cover every Move type. A drag shows the Move it will make while the finger moves, and commits it only when the finger lifts (SCP 2026-11-18).

**Consequences (testable):**
- After every committed Move, the rendered cube and the Virtual State are identical, checked sticker by sticker in tests.
- Input that arrives during an animation is queued or rejected deterministically. It never leaves the logical state and the rendering out of step.
- A quarter-turn animation completes within 250 ms on the reference device (§5).
- While a turn gesture is in progress, the turning layer and the Move name are shown, and the Cube State is unchanged until release; a cancelled drag changes nothing.
- The previewed Move name is in the cube frame, so it is the Move that will commit whatever the view angle.

#### FR-2: Rotate the view independently
The Learner can rotate the camera freely without changing the Cube State.

**Consequences (testable):**
- No sequence of View Rotations changes the Virtual State.
- [ASSUMPTION: Notation follows the cube's own orientation, not the camera. `R` names the same face whatever the view angle.]
- While the view is off its home position, the stage says so and offers a one-tap Reset view (SCP 2026-11-18).

#### FR-3: Undo, redo and reset
The Learner can undo Moves repeatedly, redo them, and reset the cube.

**Consequences (testable):**
- Undo restores the exact previous Cube State.
- Redo after undo restores the undone state, and any new Move clears the redo history.
- Reset asks for confirmation if it would discard unsaved Lesson progress.

---

### 4.2 Beginner Course

**Description:** a complete layer-by-layer Course from zero knowledge to a first solve, and then to independent solving. Every Lesson is **data-defined**: Objective, Demonstration, learner action, Completion Predicate and Recovery Path. Each is validated against the cube engine. No Lesson is a hard-coded Move list that assumes the Learner never makes a mistake.

Because Lessons are data, the following can all change without touching code: explanation text, its variants, teaching rules and thresholds. This makes localization possible later and lets the pilot compare alternative explanations (OQ-5). Realizes UJ-1, UJ-4.

| Stage | Required learning outcome |
|---|---|
| 1. Cube fundamentals | Identify centers, edges and corners; read face Notation. |
| 2. First cross | Place four edges correctly relative to their centers. |
| 3. First-layer corners | Insert four corners while preserving the cross. |
| 4. Middle layer | Insert the four middle-layer edges. |
| 5. Last-layer orientation | Orient the LL with the beginner method. |
| 6. Last-layer completion | Permute the LL and solve the cube. |
| 7. Independent practice | Solve a new Scramble with fewer and fewer hints, and say what each step achieves. |

#### FR-4: Follow a Stage-gated Course
The Learner moves through the Stages in order. A Stage unlocks when its prerequisites are met, or when the Learner passes a skip-ahead check, or through Placement (FR-27).

**Consequences (testable):**
- Every Lesson definition declares prerequisites, Objective, Demonstration, learner action, Completion Predicate and Recovery Path. A schema-validation test fails the build if any is missing.
- For every Lesson, an automated test drives at least one legal path from its entry Cube State to its Completion Predicate.
- Completion is decided by the Completion Predicate, never by counting Moves.
- An "I already know this" action runs a short check (solving that Stage's Objective from a fresh Scramble). Passing it marks the Stage *skipped*, not *mastered*. [ASSUMPTION: the skip-ahead check is in MVP.]
- A Learner who can already solve can instead **choose an entry level** (for example "I can solve the first two layers", "I solve with the beginner method", "I know 2-look OLL and PLL"), with no check. Every Stage before that level is marked *skipped* (a self-chosen skip, recorded as such), never *mastered*, and stays revisitable. Choosing a level never re-locks or downgrades a Stage the Learner has already reached. The skip-ahead check stays available for Learners who prefer to prove it. *(SCP 2026-11-20 v1.1 promotion, #209)*

#### FR-5: Teaching Modes
The Learner can run any Lesson in any of four modes:
- *Show me*: the full Demonstration.
- *Guide me*: one Move at a time.
- *Test me*: the Learner chooses the next Move and the tutor evaluates it.
- *Let me try*: free solving, with hints on request.

**Consequences (testable):**
- All four modes evaluate the same Completion Predicate.
- In *Guide me*, each step states the Objective before showing the Move (Principle 1).
- In *Test me* and *Let me try*, the next Move is revealed only after the Learner asks for a hint.

#### FR-6: Explained Demonstrations
The Learner can watch a Demonstration that explains the Objective and why each Move matters. It shows the Notation step by step and highlights the affected pieces with direction arrows.

**Consequences (testable):**
- The Learner can play, pause, step forward and back, replay and change speed.
- Every explanation step follows the voice guide (addendum §I). It states:
  - the goal;
  - what must stay intact;
  - where to look;
  - and, after the Move, what to notice.

  The Lesson schema has a field for each of these.
- All explanation text lives outside UI code (NFR-10).

---

### 4.3 Physical-cube Mode

**Description:** the Learner works with a real cube while the app tracks the **Expected State**. Without a Scan, the app cannot see the real cube, and it never pretends it can (Principle 3). Realizes UJ-1, UJ-4.

#### FR-7: Confirm physical Moves
The Learner performs the demonstrated Moves on a real cube, then taps *Done* or enters the Move they actually made.

**Consequences (testable):**
- The Expected State changes only after the Learner confirms, or after a validated Scan (FR-26).
- The Expected State always carries a visible "Expected" label. It is presented as observed only after a Scan has verified it.
- View Rotation changes neither the Notation shown nor the Expected State.

#### FR-8: Switch modes mid-Lesson
The Learner can switch between virtual mode and Physical-cube Mode without restarting the Lesson.

**Consequences (testable):**
- On switching to Physical-cube Mode, the Learner is asked to confirm their cube matches the current state. Alternatively they can scan it (FR-21) or enter it in the Manual Cube Editor (FR-14).

#### FR-9: Report a mismatch honestly
The Learner can report "my cube doesn't match" or "I'm not sure what I did" and enter a Recovery Path.

**Consequences (testable):**
- The app claims to have *detected* a physical error only when a Move the Learner entered or a validated Scan supports the claim.
- Without such evidence, Certainty is *unknown*, and the tutor asks for more information in this order:
  1. a Quick Check (FR-26);
  2. which Move the Learner made;
  3. one specific face to check;
  4. a full re-entry of the cube.

---

### 4.4 Tutor Feedback: Diagnosis, Hints and Recovery

**Description:** after each committed Move or confirmation, the tutor evaluates the Cube State against the current Objective and produces a structured decision (addendum §D). The decision says:
- whether the Objective is met;
- the Diagnosis, if any, with its Certainty;
- the explanation to show;
- the recovery options;
- whether to advance.

The rules are deterministic and transparent; there is no machine learning in MVP. Realizes UJ-1, UJ-4.

The supported Error Categories are:
- wrong Move or direction;
- wrong piece selected;
- wrong piece orientation;
- cube held in an unexpected orientation;
- a completed layer disturbed;
- an invalid or inconsistent entered state;
- an unknown discrepancy.

#### FR-10: Diagnose supported errors
The tutor identifies supported Error Categories from the Cube State, the Move history and any Scan evidence.

**Consequences (testable):**
- Every Error Category maps either to a deterministic rule or to an explicit *unknown*, and scenario tests cover each one.
- Every Error Category has an actionable Recovery Path, and a test exercises it.
- When several causes could produce the same state, the tutor reports *likely* or *unknown*, never a false *verified*.
- The tutor never silently replaces the Learner's state with the Expected State.

#### FR-11: Hint Ladder
The Learner can ask for a hint at any time and gets the next rung of the Hint Ladder. The full Solution is never shown by default.

**Consequences (testable):**
- Each request moves up exactly one rung. The ladder resets when the Objective changes.
- Hint use is recorded per attempt (FR-19).

#### FR-12: Recover from mistakes
After an error, the Learner can retry, undo, or take a Guided recovery back to a valid state for the current Objective.

**Consequences (testable):**
- Guided recovery suggests the shortest correction that restores the current Objective's preserved pieces (Principle 4). It never restarts the Lesson unless the Learner chooses to.
- Scenario: the cross is complete during a corner Lesson, and a Move breaks it. The tutor:
  - says the previous layer was disturbed;
  - offers undo or Guided recovery;
  - keeps the earlier Checkpoint;
  - does not unlock the next Stage.

#### FR-13: Adaptive assistance (rule-based)
The tutor adjusts assistance from the Learner's error history, repeated attempts and hint requests:

| Situation | What the tutor does |
|---|---|
| First mistake | Explains the Objective and highlights the piece. |
| Repeated mistake | Demonstrates the turn. |
| Continued difficulty | Guides the Learner step by step. |
| Repeated success | Gives less assistance. |

**Consequences (testable):**
- Thresholds are configuration data, not code constants, and tests cover them.
- [ASSUMPTION: basic rule-based adaptation is P0; tuning the thresholds from pilot data is P1.]

---

### 4.5 Solve My Cube: Manual Editor, Solver and Playback

**Description:** the Learner can enter any physical cube, get a verified Solution and step through it. The Solver finds a path; it does not teach. All teaching decisions stay with the tutor. Realizes UJ-3.

#### FR-14: Manual Cube Editor and state validation
The Learner can enter all 54 sticker colors and have the configuration validated. The same validation applies to every Scan Result (FR-23). The editor is also the fallback whenever the camera is unavailable.

**Consequences (testable):**
- Validation rejects each of these and explains the failed rule in plain language:
  - wrong color counts;
  - impossible pieces;
  - a twisted corner;
  - a flipped edge;
  - a parity violation.
- Having 54 colors entered never counts as valid by itself.
- Each face may be entered in any rotation. When the entered faces resolve to exactly one valid cube by turning faces, the editor accepts it without asking; only when no turn or more than one fits does it name the face and show how to hold the cube for it. *(SCP 2026-11-20, #253)*
- Validation messages are specific: wrong color counts name each color and how far off it is; impossible pieces name the piece and the faces it touches; suspect stickers and their faces are marked. While some faces of a new entry haven't been entered yet, the editor says which faces are left instead of reporting piece errors. *(SCP 2026-11-20, #253)*

#### FR-15: Solve a valid state
The Learner can solve any valid Cube State offline.

**Consequences (testable):**
- Applying the Solution to its input always yields the solved state. This is verified across a corpus of the solved state plus at least 10,000 random legal states, including deep Scrambles.
- Invalid input is rejected before the Solver runs. A Solver timeout or failure produces an explicit error with a recovery prompt, never a made-up Solution.
- The Solver never blocks the UI thread.

#### FR-16: Solution Playback
The Learner can step forward and back through a Solution, pause and replay it, and read a short note for each Move.

**Consequences (testable):**
- Each note follows a fixed template: face, direction, and the piece group that moves (e.g. "Turn the Right face clockwise: the right column moves up").
- Notes never claim a strategic purpose for Solver output (Principle 3). Strategic explanation belongs to the Course.
- In Physical-cube Mode, playback advances one step each time the Learner taps *Done*. A Quick Check (FR-26) is available at any step.
- The Learner can switch playback between the 3D cube and the 2D net at any step without losing their place. Playback opens in 3D, except in Physical-cube Mode, where it opens on the 2D net; once the Learner has switched, their choice is remembered (FR-19). *(SCP 2026-11-17 playback view and move glyphs)*
- Each Move can be shown with a small picture of the turn, next to its notation and in the Move strip, unless the Learner turns *Move pictures* off (FR-19). The picture is never the only way the Move is given: the notation and the note are always there. *(SCP 2026-11-17)*

**Out of Scope:**
- A "teaching solve" that re-explains arbitrary Solver output in beginner-method terms. Solver output still never gets a strategic explanation. Method-aware solutions are a separate feature, FR-30, built by the Course's own methods rather than by explaining the Solver's path. *(SCP 2026-11-20, #210)*

---

### 4.6 Faster Last Layer: 2-look LL and the Algorithm Library

**Description:** after the beginner Course, the Learner can learn a faster last layer in two steps. First comes **2-look LL**, a mini-Course built with the same Lesson framework. Then the **Algorithm Library** opens up all **57 OLL and 21 PLL** Algorithms. Each Algorithm has:
- an animated Demonstration;
- a recognition diagram of its Case;
- a family grouping;
- an Algorithm Status that the Learner sets.

Demonstrations are **generated by the engine from Notation**, not recorded video. That keeps every Algorithm verified and the app small. Realizes UJ-2, UJ-5, UJ-6.

#### FR-17: 2-look LL mini-Course
The Learner can take a 2-look OLL and 2-look PLL mini-Course. It unlocks after the beginner Course or a skip-ahead check, and uses the Teaching Modes, hints and Completion Predicates.

**Consequences (testable):**
- Each Lesson's Completion Predicate is "this LL step is achieved" (e.g. all LL edges oriented), checked on the Cube State.
- Each Case Lesson references its Algorithm Library entry or entries, so 2-look and Library data never diverge.
- The mini-Course is *completed* when every Lesson's predicate has been met. It is *mastered* once it is *completed* and the Learner has twice solved the LL of a fresh Scramble using only 2-look Algorithms without hints. Qualifying solves made before completion count. [ASSUMPTION: 2-look mastery rule.] *(SCP 2026-11-10)*
- LL practice Scrambles are built from the whole Algorithm Library, so every last-layer Case can appear. *(SCP 2026-11-10)*
- Finishing a Lesson offers to set the related Algorithm Statuses to *Learning*, but never sets them automatically.

#### FR-18: Algorithm Library
The Learner can browse all 78 Algorithms grouped by family, for example:
- OLL: dot, line, L-shape, T, …
- PLL: edges-only, adjacent swap, diagonal swap.

For any Algorithm, the Learner can:
- watch its Demonstration (play, pause, step, speed, View Rotation);
- see its recognition diagram and Notation;
- set its Algorithm Status;
- practice it on a real cube (*I'm doing it on my cube*): the set-up Moves from solved (skippable when the cube already shows the Case), then the Algorithm's Moves, each advanced with *Done*, with a Quick Check (FR-26) available at any step, including mid-Algorithm. *(SCP 2026-11-20, #208)*

**Consequences (testable):**
- An automated test verifies every Algorithm. Applying the Algorithm's inverse to a solved cube must produce a state that matches its Case. Applying the Algorithm to that state must solve the LL, up to a final U-layer adjustment (AUF). A wrong or mistyped Algorithm fails the build.
- The Learner can filter by Algorithm Status and by family.
- Recognition diagrams are derived from the Case's Cube State, never hand-drawn.
- [ASSUMPTION: each Case has one primary Algorithm in MVP; alternatives are P1.]

**Out of Scope:**
- Spaced-repetition drills for recognition and execution (FR-29, P1).

---

### 4.7 Progress and Mastery

**Description:** progress is saved locally and survives restarts. *Completed*, *skipped* and *mastered* are separate states. Realizes UJ-1, UJ-2, UJ-4.

#### FR-19: Persist progress, Checkpoints and preferences
All of the following persist across app restarts and forced process death:
- completed, skipped and mastered Stages;
- the current Checkpoint;
- each attempt's outcome, error count and hints used;
- Algorithm Statuses;
- Color Calibration;
- preferences: animation speed, color-blind display, the last Teaching Mode used, the Solution Playback view (3D cube or 2D net, once chosen), and *Move pictures* (default on). *(SCP 2026-11-17)*

**Consequences (testable):**
- After a forced restart, the latest committed Checkpoint is restored.
- If the Checkpoint was in Physical-cube Mode, the Learner is asked to confirm, scan or re-enter their cube before continuing.
- Repeating a Lesson adds new records and never corrupts earlier ones.
- The Learner can reset all progress and Algorithm Statuses, after a confirmation step. Color Calibration and preferences have their own separate resets.
- *Reset progress* also ends any live Lesson, 2-look or practice session and deletes its snapshot, so nothing locked by the reset can be continued. *(SCP 2026-11-10)*

#### FR-20: Mastery and Independent practice
The Learner can do Independent practice on a fresh Scramble with reduced help. A Stage is *mastered* only once its Mastery Condition is met.

**Consequences (testable):**
- [ASSUMPTION: the initial Mastery Condition is two successful completions of the Stage Objective, at least one with reduced assistance. It is configurable and is a hypothesis for the pilot to test.]
- Mastery transitions follow the configuration, and tests cover them.
- Neither finishing a Lesson nor a *skipped* status marks a Stage *mastered*.
- The Learner can see their own hint and error trend across attempts.

---

### 4.8 Camera Cube Recognition

> **Release: v1.0 for FR-21–FR-27, per SCP 2026-11-20 (PO moved #198, #208, #249, #251, #252 and their camera dependencies into v1.0; FR-28 keeps its own release decision). Earlier text:** **Release: v1.1, flag-gated.** Deferred from P0 on 2026-11-08 (`sprint-change-proposal-2026-11-08-vision-deferral`); restored to the plan on 2026-11-14 (`sprint-change-proposal-2026-11-14-v1-1-camera`). Built on `main` with every camera flag OFF in release; at each release the PO sets each feature OFF, BETA or GA from the test-matrix evidence. Requirements unchanged. v1.0 hides every entry point behind the AD-18 flags and declares no camera permission.

**Description:** the Learner points the phone camera at a physical cube and the app reads its Cube State. This is how the app comes to *know* the Physical State instead of predicting it. Recognition serves four uses:
- **Core:** solving without typing colors (FR-25), and verifying physical Moves (FR-26), which turns the tutor's guesses into evidence.
- **Opportunistic, cut first on slip:** Placement (FR-27) and Case recognition (FR-28).

Recognition runs entirely on-device. A Scan Result is never used before it passes validation (FR-14), and uncertain stickers are always shown to the Learner for correction. Realizes UJ-1, UJ-3, UJ-4, UJ-5.

There are two capture modes:
- **Guided 6-face Scan** (primary). A live viewfinder with a 3×3 grid overlay prompts the Learner face by face ("white center facing you, green on top"). It captures each face automatically once it is steady, and shows progress on a mini cube net.
- **Quick Scan** (fewer captures). The Learner holds the cube by a corner, so each capture shows three faces, and two or three captures cover all six. If the result is not confident and valid, the app falls back to the Guided 6-face Scan for the missing or uncertain faces only.

#### FR-21: Guided 6-face Scan
The Learner can capture all six faces in a live viewfinder, with face prompts and automatic capture.

**Consequences (testable):**
- Each face is captured automatically once the grid is aligned and the image is stable. A manual shutter is also available.
- Each face may be shown in any rotation. The app works out each face's rotation from adjacency checks across the six faces; when it can't, it asks again for only the faces involved, naming the side to put on top. It never guesses. (SCP 2026-11-16) When no rotation fits and more than 2 faces are involved, it asks again for none of them: the colors were probably misread, so it goes straight to review with the best-fitting rotations, where the Learner corrects the stickers (SCP 2026-11-19, #164).
- Every sticker gets a Sticker Confidence. Uncertain stickers are highlighted for one-tap correction before the result can be accepted.

#### FR-22: Quick Scan (corner views)
The Learner can capture the cube in 2–3 corner-angle photos, each showing three faces.

**Consequences (testable):**
- Two captures from opposite corners yield all 54 stickers.
- If perspective, glare or occlusion leaves stickers uncertain or the state invalid, the app asks for only the faces it is missing, using the Guided 6-face Scan. It never produces a guessed state.
- Quick Scan detects the cube's real edges and corners in the camera frame and unwarps each face from them; the Learner never has to line the cube up with a drawn guide, and no perspective-correct guide sketch is used (PO, 2026-11-20, Story 10.18, #195). *(SCP 2026-11-20)*
- The Learner can also switch from Quick Scan to the Guided 6-face Scan at any time. Faces already captured are kept, the Guided Scan asks only for the others, and all six faces are assembled together. *(SCP 2026-11-20, #198)*
- Quick Scan is labeled "beta" unless it meets its own targets (§4.8 NFRs).

#### FR-23: Validate and correct a Scan Result
The Learner can review a Scan Result on a cube net or the 3D cube, correct any sticker, and accept it only once it is valid.

**Consequences (testable):**
- Every Scan Result goes through FR-14 validation. When a state is invalid, the app names the likely bad stickers ("this corner has two whites") and leads to correction or a rescan.
- 54 recognized colors never count as valid by themselves.
- Low light, glare, blur or an incomplete capture produce specific guidance ("move away from the lamp," "hold steady") and a recapture.

#### FR-24: Color Calibration
The Learner can calibrate the colors for their own cube and lighting, using the six centers as references.

**Consequences (testable):**
- Stickers are classified against the cube's own centers, so non-standard shades (stickerless, pastel) work after calibration.
- Calibration persists per device and can be reset (FR-19).
- [ASSUMPTION: only the standard color scheme is supported in MVP: white opposite yellow, green opposite blue, red opposite orange.]

#### FR-25: Scan to solve
In **Solve my cube**, the Learner can scan the cube instead of typing colors, then continue into Solution Playback (FR-16). Realizes UJ-3.

**Consequences (testable):**
- The flow scan → correct → validate → solve → playback runs without leaving the screen.
- The Solver is never called on a Scan Result that has not passed validation.
- If camera permission is denied, the flow opens the Manual Cube Editor.

#### FR-26: Quick Check of physical Moves
In Physical-cube Mode, the Learner can scan to confirm their real cube matches the Expected State. The scan can be a Guided 6-face Scan, or a Quick Check of only the faces the latest Moves affected.

**Consequences (testable):**
- If the scan matches, the confirmation is recorded with Certainty *verified*.
- If it doesn't match, the tutor gets the observed faces as evidence for Diagnosis (FR-10).
- The tutor reports *verified* only when the observed faces fit exactly one candidate Move sequence from the Expected State. A Quick Check of only some faces can verify only a match. A mismatch is *verified* only by a Guided 6-face Scan (e.g. "you turned `R` instead of `R'`"). When a Quick Check shows a mismatch, or several candidates fit, the tutor reports *likely* and offers a Guided 6-face Scan. *(SCP 2026-11-17: never a false* verified *(FR-10) outranks verifying a mismatch from a few faces.)*
- The Learner can adopt the observed state as the new starting point for a Guided recovery.
- The tutor offers a Quick Check at Stage milestones and after reported mismatches, but never requires one. *Done* still works (FR-7).
- In Physical-cube Mode a Quick Check is also available at any step of a Lesson, Solution Playback or Algorithm practice (FR-18), including mid-Algorithm. *(SCP 2026-11-20, #208)*
- [ASSUMPTION: a Quick Check takes at most two captures.]

#### FR-27: Start the Course from my cube (Placement)
The Learner can scan any scrambled or partly solved cube, and the tutor suggests the right Stage for that state.

**Consequences (testable):**
- The tutor evaluates each Stage Predicate on the scanned Cube State and suggests the first Stage whose predicate is not met. The Learner can accept it or pick a different Stage. [ASSUMPTION: white cross first only, in MVP.]
- Accepting a suggestion unlocks that Stage. Earlier Stages are marked *skipped by placement*: they are never *mastered* and can always be revisited (same rule as FR-4's skip-ahead check).
- Before the suggestion, the result says in plain language what the scan saw and why that Stage fits (for example "Your white cross is done, and 2 of 4 first-layer corners are in place."). A cube at most 2 Moves from solved (including a last-layer or first-layer turn, AUF) gets its own outcome before the Stage Predicates are evaluated. A cube solved from a color other than white is told its solve is fine and why the Course starts from white. TalkBack reads the reading and the reason. *(SCP 2026-11-20, #251)*
- An onboarded Learner can run Placement again from outside Welcome without resetting progress. A re-placement only adds progress: it never re-locks or downgrades a Stage and never erases Lesson completions, Checkpoints, attempts or Algorithm Statuses. *(SCP 2026-11-20, #252)*

#### FR-28: Recognize an OLL/PLL Case
The Learner can scan the top layer, and the app names its OLL or PLL Case; tapping the result opens the Algorithm in the Algorithm Library and plays its Demonstration. The scan is two captures of opposite top corners, yellow on top, each showing the U face and two side faces; together they show every side, so the first two layers are checked before a Case is named (SCP 2026-11-18). Realizes UJ-5.

**Consequences (testable):**
- The scanned LL stickers are compared with every Case's derived pattern (FR-18), allowing for any U-layer rotation (AUF).
- The candidate Cases from each capture are intersected. If more than one Case remains, the app lists the candidates and offers another angle, which intersects again. It never picks one silently. If no Case remains, the app says it couldn't match the cube to a Case.
- If the first two layers are not solved, the app says the cube isn't at the last layer yet and offers Placement (FR-27).
- The app offers, but never forces, a change to the Algorithm Status.

**Feature-specific NFRs** — the test matrix covers several cubes (at least one stickered, one stickerless), four lighting conditions (daylight, warm indoor, cool LED, low light) and the reference devices. [ASSUMPTION: all targets below are provisional until the week 1–2 recognition spike confirms them; OQ-7, OQ-8.]

| Mode | Accuracy target | Speed target |
|---|---|---|
| Guided 6-face Scan | ≥ 98% of scans reach a valid state after at most 3 sticker corrections, and ≥ 99.5% of stickers are read correctly under normal indoor light. | < 45 s for a repeat user |
| Quick Scan (GA) | ≥ 95% of scans reach a valid state after at most 3 corrections (Guided 6-face Scan fallback allowed). Below this it ships as "beta". | < 15 s |
| Quick Check | 0 false *verified* results in the scenario suite. A matching cube is confirmed in ≥ 98% of checks. | ≤ 2 captures |
| Case recognition | ≤ 1% wrong Case named, counted only when a single Case is named. Ambiguous scans list candidates. | result < 1 s after capture |
| Confidence calibration (all modes) | At most 0.1% of all stickers are both misread and marked *confident*. | — |

- **Privacy:** camera frames are processed in memory on-device, and are never stored or sent anywhere. The camera permission is requested only when the Learner first opens a scan, with an explanation. Every scan feature has a manual fallback. Camera frames are never saved as photos. The rationale shown before the permission request says this in plain words.

**Out of Scope:**
- Importing photos from the gallery.
- Tracking Moves continuously from live video.

---

### 4.9 Algorithm Training — P1

#### FR-29 (P1): Recognition and execution drills
The Learner can drill OLL and PLL Case recognition (name the Case shown) and execution (perform the Algorithm on the virtual cube against a timer). Spaced repetition is driven by their Algorithm Status and results. FR-29 defines a per-Algorithm mastery rule that is separate from the Learner-set Algorithm Status.

### 4.10 Method-aware Solutions

#### FR-30: Method-aware solutions
*(SCP 2026-11-20, #210)* For any valid Cube State, the Learner can ask for a solution shaped by a method they are learning, instead of the shortest one: the beginner layer-by-layer method (the Course's Stages), the beginner first two layers with 2-look OLL and PLL, or the beginner first two layers with full OLL and PLL. The Solve tab keeps the optimal solution (FR-15) as its default.

**Consequences (testable):**
- Each segment of the solution is tagged with the Stage or Case it practices, and its label comes from content, never from a note about Solver output.
- Last-layer segments use the Library's Algorithms, preferring those the Learner has marked *Learning* or *Learned* when more than one fits.
- The solution is built from the Course's predicates and the Library's Algorithms, never by re-labelling the Solver's path (AD-8, AD-21). Applying it to its input always yields the solved state, verified over the same corpus as FR-15.
- The method is suggested from the Learner's progress (for example 2-look once *Faster last layer* is unlocked), and the Learner can change it.

**Out of Scope:** CFOP F2L, Roux and ZZ segments (§6.2, P2).

## 5. Cross-Cutting Non-Functional Requirements

Targets are validated on a defined set of **reference devices**. [ASSUMPTION: the minimum reference device is a mid/low-range phone from about 2020, running Android 10. The final set is fixed during architecture.] *Resolved: the architecture named the set (ARCH-OQ-1, PO 2026-10-30). Since 2026-11-12 the minimum reference device is the Lenovo Tab M10 Plus 3rd Gen (TB128XU: Snapdragon 680, 4 GB RAM, Android 13), and the mid-range device stays the Samsung Galaxy S24 FE. The Samsung Galaxy Tab S7 FE 5G, the minimum device until then, is kept as an additional compatibility device. API 29–30 coverage is emulator-only. (SCP 2026-11-12)*

| ID | Category | Requirement / acceptance |
|---|---|---|
| NFR-1 | Responsiveness | Face-turn input is acknowledged within 100 ms. |
| NFR-2 | Rendering | ≥ 30 FPS during face turns and View Rotation on the minimum reference device; 60 FPS target on mid-range. |
| NFR-3 | Solver performance | 95% of a representative corpus of random legal states (not only easy Scrambles) solved within 3 s on the minimum reference device. Median, p95, memory use and failure rate are reported. *(SCP 2026-11-12: "minimum" made explicit)* |
| NFR-4 | Correctness | **Zero state mismatches** between engine, rendering and Expected State across the randomized Move, undo and interruption suite. Cube-engine invariants pass as property-based tests: inverse restores the state, M⁴ = identity, a random sequence followed by its inverse = identity, illegal states are rejected. The cube engine has the strongest test coverage of any component. |
| NFR-5 | Offline | A fresh install with no network can complete the Course, use the Algorithm Library, and enter a cube and solve it (and scan it, from v1.1). v1.0 declares no runtime permissions. From v1.1, the camera is the only one, and a release declares it only when a camera feature's flag is above OFF. [ASSUMPTION: no network permission is declared.] |
| NFR-6 | Persistence | The latest Checkpoint survives forced process death. |
| NFR-7 | Accessibility | Content labels on every control, WCAG AA contrast, scalable text, and a non-gesture alternative for every essential action. **Color-blind display** (optional letters or patterns on stickers) means the cube can be used without telling colors apart. [ASSUMPTION: color-blind display is P0.] |
| NFR-8 | Privacy | No account and no personal information collected. No analytics SDK and no ads. All data stays in app-private storage and never leaves the device. The Play Store data-safety form declares "no data collected." |
| NFR-9 | Stability | ≥ 99.5% crash-free sessions; ANR rate below the Play Store's bad-behavior threshold (Android vitals). |
| NFR-10 | Localizability | All Learner-facing text lives outside code, in resources and Lesson data, so adding a language needs no code change. English only at launch. |
| NFR-11 | App size | [ASSUMPTION: install size < 30 MB, including Solver tables (and, from v1.1, any recognition model); checked when OQ-4 and OQ-7 are decided.] *Note: measured at each release that raises a camera flag (Story 10.11).* |
| NFR-12 | Code quality (portfolio) | The cube domain, Solver adapter, tutor rules and recognition classification are pure Kotlin with no Android dependencies, and have ≥ 80% line coverage. Static analysis and formatting are enforced in CI, and every merge to main builds, tests and lints green. |
| NFR-13 | Compatibility | [ASSUMPTION: minSdk Android 10 (API 29).] Phones and tablets, portrait and landscape. targetSdk meets current Play Store policy. |

## 6. Non-Goals and MVP Scope

### 6.1 Non-Goals (explicit)

- Not a speedcubing platform: no competition timer suites, leaderboards or social features.
- Not a Bluetooth smart-cube app.
- No AI or LLM that invents Moves or judges whether a cube is valid; the verified engine owns correctness. If a conversational tutor is ever added, it only explains.
- No accounts, cloud sync or backend in v1.
- No monetization (ads, in-app purchases or subscriptions) for now.

### 6.2 MVP Scope

**In scope (P0, about 16 weeks, solo):**
- Interactive 3D cube with undo, redo and reset (FR-1 – FR-3).
- The full beginner Course, with four Teaching Modes and explained Demonstrations (FR-4 – FR-6).
- Physical-cube Mode with honest mismatch handling (FR-7 – FR-9).
- Diagnosis, the Hint Ladder, recovery, and rule-based adaptive assistance (FR-10 – FR-13).
- Manual Cube Editor, offline Solver and Solution Playback (FR-14 – FR-16).
- 2-look LL mini-Course and the **Algorithm Library of all 78 OLL and PLL Algorithms** with animated Demonstrations (FR-17, FR-18).
- Local progress, Checkpoints, mastery and Independent practice (FR-19, FR-20).
- English, every NFR in §5, and a Play Store release.

**Out of scope for MVP:**
- **v1.1 (in progress, flag-gated): Camera Cube Recognition** (FR-21 – FR-28):
  - capture: Guided 6-face Scan and Quick Scan;
  - validation and correction, plus Color Calibration;
  - uses: scan to solve, Quick Check, Placement and Case recognition.

  Deferred by PO decision on 2026-11-08 (`sprint-change-proposal-2026-11-08-vision-deferral`); restored to the plan on 2026-11-14 as v1.1, flag-gated (Epics 10–11; `sprint-change-proposal-2026-11-14-v1-1-camera`). The AD-18 flags stay OFF in v1.0. **Superseded by SCP 2026-11-20:** FR-21–FR-27 move into v1.0; Story 10.11 raises `CAMERA_SCAN`, `QUICK_SCAN` and `PLACEMENT` (BETA or GA from the test-matrix evidence) for the v1.0 release, so v1.0 declares the `CAMERA` permission. FR-30 (method-aware solutions) is also v1.0.
- **P1:**
  - recognition and execution drills (FR-29);
  - alternative Algorithms per Case;
  - tuning adaptive thresholds from pilot data;
  - a comprehension quiz at the end of each Lesson;
  - GIF or video export of a Demonstration;
  - non-standard cube color schemes;
  - crosses in colors other than white.
- **P2:**
  - advanced methods (CFOP F2L first, perhaps Roux or ZZ later);
  - a "teaching solve" that explains any state in beginner-method steps *(moved into v1.0 as FR-30, method-aware solutions, by SCP 2026-11-20, #210)*;
  - other puzzles (2×2, 4×4);
  - more languages;
  - a conversational or voice tutor;
  - a personal solve timer with history and averages [ASSUMPTION: P2].
- **Not planned:** importing photos from the gallery; tracking Moves from live video.

> [NOTE FOR PM] **The schedule grew from 10 weeks to about 16** (confirmed by the PO on 2026-10-30, OQ-9; reopened if the recognition spike misses its exit criterion). Two changes caused it:
> - Camera Cube Recognition moved into P0, adding about 4 weeks. The draft estimated 2–4 weeks for a basic scan, and two capture modes plus four uses puts this at the top of that range. Color classification under real lighting is the most uncertain item in the MVP.
> - The Solver is now written in-house (OQ-4), adding about 2 weeks.
>
> **Recognition spike, weeks 1–2:** run it alongside the cube engine. Its exit criterion: a Guided 6-face Scan prototype reads ≥ 99% of stickers correctly under normal indoor light on two reference devices.
>
> **If the spike misses** (decided): launch on schedule anyway. The Manual Cube Editor becomes the primary input, every camera feature ships labeled "beta" with each result still validated, and recognition improves in P1.
>
> **If the schedule slips, cut in this order** (expect at least the first cut to happen):
> 1. Quick Scan moves to "beta" (FR-22).
> 2. Case recognition (FR-28).
> 3. Placement (FR-27).
> 4. Library recognition diagrams.
> 5. *Test me* mode.
> 6. Adaptive assistance (FR-13) reduced to a fixed Hint Ladder.
>
> Always keep: the Course, Physical-cube Mode (with camera-free confirmation), the Manual Cube Editor with the Solver, and the verified Library animations.
>
> **2026-11-08:** the spike missed (62.5% per-sticker accuracy). Instead of shipping the camera features as beta, the PO moved all of them to v1.1 (`sprint-change-proposal-2026-11-08-vision-deferral`).

## 7. Release and Portfolio Requirements

- **Store readiness:** the final app name and icon are chosen after a trademark search (OQ-1). The listing needs:
  - screenshots;
  - a short promo video recorded from the app;
  - a privacy-policy page (required even when no data is collected). It is published on the public Cubentia website, which lives in its own repository and sets no cookies and no tracking. Its statements are checked against the app's build (Epic 13, SCP 2026-11-13);
  - a completed content-rating questionnaire.
- **Release track:** internal testing → closed testing with pilot beginners, starting once the Course is usable (addendum §F) → production.
- **Repository:** private until the app works end to end, then public. When it goes public it contains:
  - a README with screenshots and an architecture overview;
  - the architecture document;
  - green CI and visible coverage;
  - a history of Conventional Commits.
- **MVP Definition of Done:** a new user, without an account or internet connection, can:
  - learn the basic cube concepts and face-turn Notation on the virtual cube;
  - follow the full beginner Course, on a virtual or physical cube;
  - receive feedback for the supported errors, then undo or take a Guided recovery;
  - say what each taught Move or sequence is for;
  - resume a Lesson after closing the app;
  - attempt a fresh Scramble with reduced help;
  - enter a cube and solve it with the local Solver (scanning arrives in v1.1);
  - learn 2-look LL, and browse and watch all 78 Algorithms.
- **Release gates** (all are required for production):
  1. Cube-engine invariants and Algorithm Library verification pass in CI.
  2. Every Lesson has validated success and failure paths, and every Error Category has a tested Recovery Path. Hint escalation and mastery transitions follow their configuration.
  3. The end-to-end suite shows no silent desync between virtual and physical state. Undo, pause, restart and resume all preserve a valid state.
  4. A pilot group of beginners completes the Course. It confirms that Move instructions make sense without prior knowledge of Notation and that Learners recover from deliberate mistakes. Task completion, hint use and points of confusion are recorded, and every severity-1 and severity-2 usability issue is fixed.
  5. Offline behavior, state recovery and accessibility are verified.
  6. *(v1.1)* Every scan flow has a working manual fallback, and no unvalidated Scan Result is ever used. Camera features ship as GA if they meet their §4.8 targets on the test matrix, otherwise as "beta" (the spike-failure decision, §6.2). **For any release in which every camera flag is OFF, this gate is N/A** (v1.0 included: the manifest declares no CAMERA permission). For each raised feature, it is checked by Story 10.11 with Story 10.8's evidence.

## 8. Success Metrics

The app has no analytics (NFR-8). Learning metrics therefore come from **observed pilot sessions** and from on-device statistics the Learner can see. Store and stability metrics come from Play Console. With a pilot of about 8 people, pilot metrics are **directional**. The first round sets a baseline, and targets are set after it, not guessed now.

**Primary**
- **SM-1: First-solve completion (pilot).** Share of pilot beginners who finish the Course with a first solve. Validates FR-4 – FR-12.
- **SM-2: Independent solve rate (pilot).** Share who solve a fresh Scramble without step-by-step guidance after finishing the Course. Validates FR-20.

**Secondary**
- **SM-3: Recovery success (pilot).** Share of mistakes, deliberate or accidental, that Learners recover from without restarting the Lesson. Validates FR-9 – FR-12.
- **SM-4: Comprehension (pilot).** Participants explain a Lesson's Objective in their own words, and understand Move instructions without prior knowledge of Notation. Validates FR-6.
- **SM-5: Hint-dependency trend (pilot and on-device).** Hints per attempt should fall over successive attempts at the same Stage. This is the evidence that Learners become independent. Validates FR-13, FR-20.
- **SM-6: Time to first solve (pilot).** Elapsed time and number of sessions from starting the Course to a first solve. Baseline only.
- **SM-7: Move error rate (pilot and on-device).** Incorrect Moves per Lesson and per Stage, by Stage, used to find weak Lessons. Validates FR-4, FR-10.
- **SM-8: Scan success (pilot).** Share of scans that reach a valid Cube State on the first attempt (corrections allowed), plus the median number of corrections. Validates FR-21 – FR-24.
- **SM-9: LL progression (pilot).** Share of Course finishers who complete the 2-look LL mini-Course and solve an LL using 2-look Algorithms without hints. Validates FR-17, FR-18.
- **SM-10: Stability.** ≥ 99.5% crash-free sessions; ANR below threshold. Validates NFR-9.
- **SM-11: Retention.** Play Console's retained-installer rate at 7 and 30 days. Baseline only.
- **SM-12: Store quality.** [ASSUMPTION: average rating ≥ 4.3 once there are at least 20 ratings.]
- **SM-13: Portfolio impact.** By release, the repo is public with a README and an architecture document. Count the job applications that link the app or repo, and the interviews where it comes up.

**Counter-metrics (do not optimize)**
- **SM-C1: Absolute hint count.** Don't push it toward zero. Hints are part of learning, and penalizing them encourages guessing. Optimize the *trend* (SM-5), not the total. Counterbalances SM-2, SM-5.
- **SM-C2: Solve speed / time in app.** This is neither a speed product nor an engagement product. Finishing faster by skipping explanations is a regression. Counterbalances SM-1, SM-6.
- **SM-C3: Algorithms marked "Learned."** The Learner sets this label, so it inflates freely. It is not a mastery signal. Counterbalances SM-9.

## 9. Open Questions

1. **OQ-1 (name resolved 2026-11-08):** the store name is **Cubentia**, and the application ID is `com.exxerpro.cubentia`. Still open before publishing (Story 12.5): a trademark search for "Cubentia", and the icon. "Rubik's" is a Spin Master trademark and must not appear in the listing name, the icon or any Learner-facing text.
2. **OQ-2:** the exact 2-look LL Case set, and the primary Algorithm for each OLL and PLL Case. Decided during curriculum authoring and verified by FR-18's test.
3. **OQ-3:** rendering approach: OpenGL ES, or a 3D library such as Filament or SceneView. Decided by a spike during architecture.
4. **OQ-4 (resolved 2026-10-29):** the Solver is a clean-room Kotlin implementation of Kociemba's two-phase algorithm, written from the published description. The best-known JVM implementation (min2phase) turned out to be GPLv3-only, so no third-party solver code is used. Still open, within the solver epic: whether pruning tables are bundled or built at first run, and how big they are against NFR-11 (architecture AD-8).
5. **OQ-5:** pilot logistics: who the ~8 beginner testers are and when closed testing starts (proposed: after the Course is usable). Also whether the pilot runs the A/B comparison of plain Move-by-Move instruction versus contextual explanation with error feedback (addendum §H).
6. **OQ-6:** source and licensing of Algorithm data and Case names. The standard Algorithms are common knowledge; copied diagrams or text are not.
7. **OQ-7 (closed 2026-11-15, decision K):** recognition approach: classical CV, an on-device ML model, or a hybrid. Decided by the week 1–2 spike. Perspective correction for Quick Scan is the hardest part. Initial: Kotlin-first classical CV (Story 1.9). Story 10.10 met the live gate (216/216) only with careful operator calibration; the PO re-opened OQ-7 on 2026-11-15. Kotlin, OpenCV and an on-device ML model are compared by Story 10.13. Decided (PO, 2026-11-15, Story 10.13): Kotlin nearest-center classification fed by a per-cell histogram-mode reader, self-calibrated from the six face centers; no new runtime. LiteRT is the fallback if field use shows this is not enough; OpenCV rejected on size (NFR-11).
8. **OQ-8:** recognition test matrix: which physical cubes, lighting setups and reference devices, and how ground-truth states are recorded. Matrix: 2 cubes × 4 lights × Lenovo Tab M10 Plus 3rd Gen, Galaxy S24 FE (gating) and Galaxy Tab S7 FE (reported); live fixtures by Story 10.10.
9. **OQ-9 (closed 2026-10-30; reopened by the Story 1.9 spike at 62.5%; closed again 2026-11-08):** the schedule stays within about 16 weeks. All camera features move to v1.1, and v1.0 ships camera-free.

## 10. Decisions Taken During PRD Creation

Recorded in `.memlog.md`:
- Fast-path authoring.
- Launch-grade rigor, because this is a portfolio release.
- The full 57 + 21 Algorithm Library, using engine-generated animations rather than recorded video.
- The LL progression: beginner → 2-look → full set.
- Camera recognition moved into P0, with two capture modes and four uses.
- Placement unlocks the suggested Stage.
- Spike failure → launch with the camera features in beta.
- Free, with no ads; English only; the repo stays private until the app works.
- The Solver is written in-house in Kotlin, because min2phase is GPLv3-only (architecture review).
- Product name Cubentia and application ID `com.exxerpro.cubentia` (PO, 2026-11-08).
- Camera Cube Recognition deferred to v1.1 (PO, 2026-11-08, after the spike scored 62.5%). This supersedes "camera recognition in P0" and "spike failure → camera ships as beta".
- Reference devices: minimum Lenovo Tab M10 Plus 3rd Gen, mid Samsung Galaxy S24 FE (PO, 2026-11-12, SCP 2026-11-12). The Galaxy Tab S7 FE 5G, the minimum from 2026-10-30 (ARCH-OQ-1), is kept as an additional compatibility device.
- Public website and privacy policy in a separate repository, `hebelmx/cubentia-site`, on GitHub Pages; the app repository keeps the source (PO, 2026-11-13, SCP 2026-11-13).
- 2026-11-14: camera recognition restored to the plan as v1.1, flag-gated; per-feature release levels set by the PO at each release (SCP 2026-11-14).
- 2026-11-17: a Quick Check (Partial observation) verifies only a match; only a Guided 6-face Scan (Full observation) verifies a mismatch (SCP 2026-11-17 quick-check-verified-rule).
- 2026-11-17: Solution Playback switches between the 3D cube and the 2D net (opens in 3D, on the net in Physical-cube Mode, then remembers the choice), and each Move gets an optional move picture (default on) with the UX-DR14 Move strip; both are v1 release scope, before Story 12.3 and the store screenshots (PO, SCP 2026-11-17 playback view and move glyphs).
- 2026-11-18: Case recognition takes two opposite top-corner captures, with yellow on top, and intersects their candidate Cases. A Placement suggestion reads "Start at *Stage*?" with no progress sentence (SCP 2026-11-18 recognize-two-corners). *(The "no progress sentence" part is superseded by SCP 2026-11-20: Placement now states what the scan saw and why.)*
- 2026-11-20: GitHub issues #198, #208, #209, #210, #249, #251, #252 and #253 are promoted out of the v1.1 deferral into current work (PO). Placement explains the scanned cube and its reason, and can be run again without a reset; the Manual Cube Editor accepts any face rotation; *I can already solve it* offers entry levels; every Algorithm and Lesson step can be practiced on a real cube; Quick Scan can switch to the Guided Scan keeping its faces; method-aware solutions become FR-30. **Scope move (PO, 2026-11-20, ahead of schedule):** all of these ship in v1.0, together with the camera features they depend on (Guided 6-face Scan and review, Quick Check, Quick Scan, whose rework detects the cube's real edges and corners in the frame instead of drawing a guide sketch, Placement), whose flags Story 10.11 raises for the v1.0 release. The Lenovo Tab M10 Plus 3rd Gen (TB128XU) camera is the minimum camera spec (SCP 2026-11-20 v1.1 promotion).
- 2026-11-18: FR-1 — gestures preview on drag and commit on release; slices by middle-layer drag, wide Moves by two-finger drag on the cube; a View rotated badge with Reset view and face letters while the view is off home (SCP 2026-11-18 drag-preview-and-view-rotated, GitHub #206, #207).
- 2026-11-15: `CAMERA_SCAN` is BETA-only on the Story 10.10 session A result (216/216, operator-dependent calibration); re-plan with a UX pass on scan layout, calibration and color input; Stories 10.12 (recorder rework, session A2) and 10.13 (OQ-7 recognition approach) added; OQ-7 re-opened (SCP 2026-11-15).
- 2026-11-15: OQ-7 closed with decision K: Kotlin nearest-center classification fed by a per-cell histogram-mode reader and self-calibrated from the six face centers, with a logo-center guard; LiteRT is the fallback if field use shows K is not enough; OpenCV rejected on size (PO, Story 10.13, PR #155).

## 11. Assumptions Index

- §3 2-look LL — about 16 Cases (3 + 7 OLL, 2 + 4 PLL), all Algorithms drawn from the Library.
- FR-1 — wide and slice turns can be entered by button only.
- FR-2 — Notation follows the cube's orientation, not the camera.
- FR-4 — the skip-ahead check is in MVP.
- FR-13 — rule-based adaptation is P0; tuning is P1.
- FR-17 — 2-look mastery = two hint-free LL solves using only 2-look Algorithms.
- FR-18 — one primary Algorithm per Case.
- FR-20 — Mastery Condition = 2 successes, at least 1 with reduced help.
- FR-24 — standard color scheme only.
- FR-26 — a Quick Check takes at most two captures.
- FR-27 — Placement assumes a white cross first.
- §4.8 NFRs — all recognition accuracy and speed targets are provisional until the spike.
- §5 — minimum reference device is a mid/low-range phone from about 2020 running Android 10. *(Resolved: ARCH-OQ-1, 2026-10-30; minimum replaced by the Lenovo Tab M10 Plus 3rd Gen, SCP 2026-11-12. See §5.)*
- NFR-5 — no network permission declared.
- NFR-7 — color-blind display is P0.
- NFR-11 — install size < 30 MB.
- NFR-13 — minSdk 29.
- §6.2 — personal solve timer is P2.
- §6.2 NOTE — P0 is about 16 weeks (camera recognition plus the in-house Solver).
- SM-12 — rating ≥ 4.3 once there are at least 20 ratings.
