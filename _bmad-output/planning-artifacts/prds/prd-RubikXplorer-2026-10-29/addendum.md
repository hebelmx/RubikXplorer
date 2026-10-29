---
title: RubikXplorer PRD — Addendum (downstream depth)
created: 2026-10-29
updated: 2026-10-29
---

# Addendum: material for architecture, UX and epics

This file preserves depth from `docs/mvp-draft-product-and-architecture.md` and from the PRD conversation that belongs in downstream documents rather than in the PRD itself. `bmad-architecture` and `bmad-ux` should read it as input. It is not a requirements source; the PRD wins on any conflict.

## A. Technology direction (input to `bmad-architecture`)

| Area | Draft recommendation | Note |
|---|---|---|
| Language | Kotlin | Chosen over .NET MAUI / Unity because learning native Android is a project goal. |
| UI | Jetpack Compose | ViewModels + unidirectional data flow (Android official guidance). |
| 3D rendering | OpenGL ES or a vetted 3D library | Decide by spike (PRD OQ-3). The geometry is modest; the risks are gesture and animation correctness and sync. |
| Cube logic | Pure Kotlin/JVM module | No Android deps; tested on JVM. |
| Solver | Kociemba two-phase | Adapter behind a port; licence check (PRD OQ-4). |
| Persistence | Room | Progress, Checkpoints, learning events, Algorithm Status. |
| Async | Kotlin coroutines | Solver off the main thread; timeout → controlled failure. |
| Camera (**now P0**) | CameraX (ImageAnalysis for the live grid; ImageCapture for Quick Scan) + on-device color classification | Candidate approaches for the spike (PRD OQ-7): (a) classical CV: detect the 3×3 grid, sample sticker regions, classify in a perceptual color space (e.g. CIELAB/HSV) by nearest calibrated center; (b) a small on-device model (TFLite/LiteRT) for grid/sticker detection, with color still classified against calibrated centers; (c) a hybrid. Quick Scan needs corner-view detection and a perspective unwarp of each of the three visible faces. Validity is still checked by the cube engine, so a perfect classifier is not required, only a well-calibrated "uncertain." Keep recognition behind an **observation port** so manual input, scan and future sources all feed the same validation. |
| Testing | JUnit + property-based tests (e.g. Kotest) + instrumentation/UI tests | |

**Architectural style (developer preference, keep idiomatic to Android):** functional core / imperative shell, hexagonal ports and adapters, dependency inversion. Start with few Gradle modules and split when boundaries stabilize. The first milestone is moving `core:domain` + `core:application` into a pure Kotlin/JVM module, which demonstrates the business logic is framework-independent (a portfolio talking point).

Port/adapter map:
- Presentation adapters: Compose screens · ViewModels · UI state.
- Application core (use cases): start Lesson · execute Move · evaluate progress · request hint · solve cube.
- Domain + ports: Cube State · Lesson rules · Solver port · progress repository port · observation port.
- Adapters: Kociemba Solver · Room persistence · 3D renderer · observation (manual input + camera recognition, both P0).

Suggested starting layout (packages first, modules later):
```
app/presentation/{home,lesson,cube,library,progress}
core/domain/{cube,lesson,learning,algorithms}
core/application/usecase
adapters/{solver,persistence,rendering}
```

**What `bmad-architecture` must produce** (from the draft's "next deliverable"):
- the Kotlin domain models;
- cube-state transformations;
- the **Lesson-definition schema**, including the explanation-voice fields (§I);
- tutor decision rules and the Error Category → Recovery Path map;
- state diagrams: tutor flow, and sync between the Expected, Virtual and Physical states;
- the observation port, shared by manual input and the camera;
- unit-test cases for each component.

Treat rendering, gestures, animation timing, camera recognition and cube-state correctness as **separate problems behind separate seams**. Solving them together makes debugging much harder. Start with a button-driven Compose screen and a simple 2D net before full 3D.

## B. Cube-state representation

- Canonical: the cubie model. Corner permutation (8), corner orientation, edge permutation (12), edge orientation. Centers fix the reference frame.
- A 54-sticker array is the I/O format (rendering, Manual Cube Editor, scanning), not the canonical solver state.
- Do not leak mutable collections into the domain; consider compact arrays or dedicated immutable value types for solver performance.

Interface sketch from the draft (proposal only, to be refined with explicit types for illegal states, cancellation, solver limits and discrepancies):
```kotlin
interface CubeEngine {
    fun applyMove(state: CubeState, move: Move): CubeState
    fun validate(state: CubeState): ValidationResult
    fun isSolved(state: CubeState): Boolean
}
interface CubeSolver { suspend fun solve(state: CubeState): SolveResult }
interface TutorEngine {
    fun evaluate(state: CubeState, lesson: LessonDefinition, history: LearnerHistory): TutorDecision
}
```
Engineering rule: keep `solveCube(state)` and `teachStep(learnerState, lessonObjective)` separate. The first finds a path; the second decides how to teach.

## C. State synchronization (highest engineering risk)

There are three states: Expected, Virtual and Physical. In virtual mode the Virtual State is authoritative. In physical mode the Expected State is only a prediction until the Learner confirms it or a scan provides evidence.
1. Each confirmed Move creates an immutable transition.
2. Rendering reflects the resulting logical state.
3. The tutor evaluates the new state against the Objective.
4. The transition and the Learner action are recorded.
5. A discrepancy puts the tutor into recovery, never a silent correction.
6. Undo restores the previous state and re-runs the evaluation.

## D. Tutor decision model

Instructional loop from the draft:
1. Explain the current Objective.
2. Demonstrate it on the 3D cube.
3. Ask the Learner to perform it.
4. Observe the resulting state, or receive it from the Learner.
5. Detect whether the Move was correct.
6. Explain any error and offer a targeted hint.
7. Confirm understanding before continuing.

In MVP, step 7 is handled by the "notice" prompt in each explanation (§I) and validated in the pilot (SM-4). A comprehension quiz is P1.


A `TutorDecision` carries: the current Objective; whether it is satisfied; the Diagnosis category; Certainty (verified / likely / unknown — no numeric scores until they can be calibrated); the recommended next action; the explanation text; recovery options; and whether to advance. The MVP tutor is a deterministic state machine. Its flow is: load Objective → demonstrate → Learner acts or confirms → update state → if valid and satisfied, confirm progress (and assess mastery when the Lesson completes). Otherwise: if the cause is identifiable, give a targeted hint; if not, ask for clarification or a re-entry. Then retry, demonstrate or recover.

## E. Algorithm Library data and verification (from conversation)

- Store Algorithms as data: id, set (OLL/PLL/2-look), family, Case name, Notation, optional alternatives (P1).
- Verification test: for each Algorithm, start from solved, apply the inverse to get the Case state, then apply the Algorithm and check the LL is solved up to AUF. Also check that the derived Case state matches the expected recognition pattern.
- Recognition diagrams are rendered from the derived Case state, so there are no hand-drawn assets and no drift.
- "Animated video" = in-app engine-driven animation. A GIF/MP4 export of a Demonstration is a P1 nice-to-have (also useful for Play Store promo material).

## F. Roadmap (draft estimates, solo, planning assumptions only)

*Revised after camera recognition moved to P0 (about 14 weeks, an assumption; see PRD OQ-9).*

| Phase | Weeks | Scope | Exit criterion |
|---|---|---|---|
| P0.1 | 1–2 | Cube engine, validation, basic rendering, Solver integration, one Objective check (proof of concept) · **recognition spike** (Guided Scan prototype on 2 devices) | A random Scramble applies, renders, solves and verifies with no mismatch, and one Completion Predicate tells a helping Move from a harmful one · spike reaches ≥ 99% per-sticker accuracy in indoor light, otherwise the camera ships as beta (PRD §6.2) · OQ-9 decided |
| P0.2 | 3–5 | Lesson framework, Objective checks, explanations, hints, undo, recovery | Cross and corner Lessons pass scenario tests |
| P0.3 | 6–8 | Full Course, Physical-cube Mode, persistence, playback, 2-look LL, Algorithm Library | Full path usable offline; e2e tests pass |
| P0.4 | 9–11 | Guided 6-face Scan, validation/correction, calibration, scan to solve, Quick Check | Guided Scan meets accuracy targets on the test matrix |
| P0.5 | 12 | Quick Scan, start from my cube, Case recognition | Each passes scenario tests; Quick Scan beta-or-GA decision |
| P0.6 | 13–14 | Pilot wrap-up, polish, performance, accessibility, store listing | All release gates met |

The closed-test pilot should **start after P0.3 (around week 8)**, not in P0.6. Learners need several sessions to finish the Course, and the results have to come back in time for fixes. P0.5 packs three camera features into one week, which is the most compressed phase, and the PRD's cut order is expected to fire there first.
| P1 | — | Drill training, alternative Algorithms, threshold tuning | — |

Draft advice that still holds: prove the three riskiest pieces first. These are a correct engine, 3D animation in sync with it, and one Objective that detects whether a Move helps or harms progress. Use a simple 2D or basic visualization before investing in full interactive 3D.

Initial epic sketch from the draft (to be regenerated by `bmad-create-epics-and-stories`): E01 domain model · E02 validation + Solver adapter · E03 3D rendering + gestures · E04 Lesson schema + curriculum engine · E05 diagnostics + recovery · E06 Physical-cube interaction · E07 persistence + Checkpoints · E08 Solution Playback · E09 accessibility/performance/compatibility · E10 camera (P1). **New:** E11 2-look LL + Algorithm Library. E10 is now P0 and is likely to split into E10a scan capture + calibration, E10b validation/correction UI, and E10c scan uses (solve, Quick Check, placement, Case recognition).

## G. Competitive notes (from draft; feature comparisons from public descriptions, not hands-on tests)

- *Rubik's Cube Solver & Tutorial* (Play): beginner lessons, 3D, scanning, solving. → Our opportunity: put error diagnosis and conceptual explanation at the center.
- *Cubiq*: structured lessons, 3D practice, algorithm training, stats. → Opportunity: adapt to observed mistakes, not just lesson completion.
- *Wurfel*: scan/manual input, move-by-move. → Opportunity: teach skills that transfer, and independence from the solver.

## H. Learning-research ideas (not MVP requirements)

- An A/B pilot comparing plain move-by-move instruction with contextual explanation plus error feedback, measuring independent solving after equal practice time. Possible only with consenting pilot participants, since there is no in-app analytics.

## I. Explanation voice (input to `bmad-ux` and curriculum authoring)

The draft's reference example. Instead of "Perform `R U R' U'`":

> "We are moving this corner into its correct position while preserving the lower layer. Watch the right face first. Now perform the turn on your cube. Notice which pieces move and which remain in place."

Each explanation step has four parts, and each has a field in the Lesson schema:
1. **Goal:** what we are achieving.
2. **Preserve:** what must not be disturbed.
3. **Watch:** where to look before acting.
4. **Notice:** what changed after the Move, and what stayed in place.

Tone:
- Plain language, with Notation shown alongside the words, never instead of them.
- Second person, encouraging and never judging. Errors are framed as "here's what happened," not "wrong."
- State the Objective before the answer (teach before revealing).
- Don't claim a cause the tutor can't support.

## J. Test strategy (from the draft; input to architecture and QA)

- **Gate 1 — cube engine (unit and property-based tests):**
  - inverse restores the state;
  - M⁴ = identity;
  - a random sequence followed by its inverse = identity;
  - impossible orientation and permutation combinations are rejected;
  - Solver output solves the whole corpus (the solved state plus ≥ 10,000 random states, including deep Scrambles);
  - every Library Algorithm is verified.
- **Gate 2 — tutor (rule and scenario tests):**
  - every Lesson has a Completion Predicate;
  - every Error Category has a tested Recovery Path;
  - unknown discrepancies never produce an unsupported claim;
  - hint escalation and mastery transitions follow their configuration;
  - Quick Check never yields a false *verified*.
- **Gate 3 — end to end (Android instrumentation and UI tests):**
  - the virtual-cube Lesson flow works;
  - physical confirmation and recovery work;
  - undo, pause, restart and resume work;
  - progress survives a restart;
  - scan flows and their manual fallbacks work.
- **Gate 4 — usability (observed pilot):**
  - Learners understand instructions with no prior knowledge of Notation;
  - they recover from deliberate mistakes;
  - they can explain the Objective;
  - task completion, hint use and points of confusion are recorded.
- **Recognition test matrix:** cubes × lighting × devices with recorded ground truth (PRD OQ-8). Report accuracy per sticker and per scan, plus the rate of confidently-wrong stickers.
