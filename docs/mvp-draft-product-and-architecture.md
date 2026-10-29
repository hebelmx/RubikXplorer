# Product specification: Rubik’s Cube Tutor & Solver for Android

Your opportunity is to build something different from a conventional Rubik’s Cube app. Most solvers focus on finding a solution; most tutorials present algorithms and sequences of moves. Your product should teach users to understand the cube, execute moves correctly, recognize mistakes, and progressively solve it independently.

I would approach this as an educational product with a solver integrated into it, rather than a solver with tutorials added afterward.

## 1. Define the product vision

[Cube Solver: Scan, Learn, Play - Education App | MWM](https://images.openai.com/static-rsc-4/3PCyze7clkkdg5HayNj-ZNI3d9xeA8Dq5UY68T6847HayG0i8aPujxFFwdK-HhFL_oSXc0dTMxLOuldM3C4l1WwXYngyK_1KcHG0wF3Pocbdr_Cr7GuiHxM__Vc51nwEU3rlSpo7h0pwPGJoRpez1sh272GfEB5zvcd7U7WniKg?purpose=inline)

The interactive cube

A rotatable 3D cube that responds to finger gestures, shows individual moves, and lets learners inspect the result from any angle.

[Solving Rubik's Cube - Made Easy: Step1](https://images.openai.com/static-rsc-4/JZbOa0eWzumWjpzZSlS5DWh6r5PlTG_FjjPxeD8dAm_R1ui-upTpbmFR1VmQ-QIGiHqSBXmDfeTsU97ofCuEHf3S753nSNUo7-WiWXyFXwpeob04tLKdusCTo02-3vILEIRwcTK5jzWgt9jFVjzXiY9RFdaUAWE7XJ25SefhxiI?purpose=inline)

The personal teacher

Explains the purpose of each move, demonstrates it visually, checks whether the learner performed it correctly, and adapts when they make a mistake.

[How to Solve a Rubik's Cube the Easiest Way (Step-by-Step)](https://images.openai.com/static-rsc-4/mreYkFFkkzfNaBLKnKewlI1HF2jbB6flbRoDvk-HWkHsg9VYlTIgzXLfb3--LkaobCCHNcKZzDOPhvOZN20TUKfb9QTGH9qJiqfpFu1omzRUDFRXuKjVEeIYjU6cx-WgZgTssA49So0-IGEFjTxInWX4ewyjFj5OFo1vxy1I4wA?purpose=inline)

The solver and coach

Scans or accepts a manually entered cube state, computes a solution, and explains each step instead of simply displaying a sequence of notation.

Proposed positioning: Learn to solve the cube, not just follow a solution.

The main differentiator should be instructional quality: the app knows what the learner is trying to achieve, explains why a move matters, and verifies progress.

## 2. Competitive analysis: what should you do differently?

A preliminary review of current products shows that the market already includes interactive tutorials, cube scanning, automatic solving, and algorithm training. These are necessary features, but not sufficient differentiation.

[Cube Solver for Android -  Download](https://images.openai.com/static-rsc-4/R5LAuAs-_VZgRE0PKF9-z9DsgD3TOVaSpjKCP9rAsQ3rxmhwdmzdHgqaQ2r4rooHTbh0Fubbt8pT5UDWtESM4n9SDATjudJI-9PcgJrAtr7dHqjfVrlRKIYtFge2F_uv_75RilFc0fu3FUq2Mc-JZVGmt1X09KwnnU7d6Q71iEY?purpose=inline)

Rubik's Cube Solver & Tutorial

Established competitor

Beginner lessons, 3D animations, camera scanning, automatic solving, and speedcubing tools.&#x20;

[image](https://www.google.com/s2/favicons?domain=https://play.google.com\&sz=32)

Apps on Google Play



Opportunity: Make error diagnosis and conceptual explanations central to every lesson.

[큐브 해결사: 스캔, 학습, 플레이 | 18단계로 스캔 및 해결](https://images.openai.com/static-rsc-4/JuZfeIY1KD-IC6vimmN48HmN8fnbyv5oM_lHOYxMilJlMpwFK8A9WkVLftVR4xijVRbtZk7y7wb2w8zvA1ab_1DUP6i10gDmjIocOSrdEMaAHj1TsAjKRiimJwr2HgusOM4B8r2ybI3uyQt18BO7XeqoFqCAQGWZt7QZ2lri-Og?purpose=inline)

Cubiq

Learning and practice

Structured lessons, interactive 3D practice, algorithm training, and solve statistics.&#x20;

[image](https://www.google.com/s2/favicons?domain=https://cubiq.koynapps.com\&sz=32)

cubiq.koynapps.com



Opportunity: Use adaptive instruction based on observed mistakes, not just lesson completion.

[Pinnaxis](https://images.openai.com/static-rsc-4/kodh5_pczfl7TQF4I5bjesPX0BBr9t-AA69tzLLmk-O0iFbMusP3iVhJUBRJgiZ44Jrp5ysnsPcxdBn6UxbKGeS9NH3ZhpnQT8wLSHtNzjMJM_EMh-YDrRu_Td-sO_gkEaLmMzn_7jXzQZsyxOcUN7Q0kjIykt5YDq7CYiYfmbo?purpose=inline)

Wurfel

Simple solve guidance

Camera scanning, manual input, and move-by-move explanations.&#x20;

[image](https://www.google.com/s2/favicons?domain=https://wurfel.app\&sz=32)

Scan it, solve it



Opportunity: Teach transferable solving skills and help users solve without depending on the solver.

These are feature comparisons based on published product descriptions, not hands-on usability tests. They do not establish that competitors lack the proposed features.

### The feature that could differentiate your product

Build an instructional feedback loop:

1. Explain the current objective.
2. Demonstrate the move on a 3D cube.
3. Ask the learner to perform the move.
4. Observe or receive the resulting cube state.
5. Detect whether the move was correct.
6. Explain the error and offer a targeted hint.
7. Confirm understanding before continuing.

For example, instead of saying, “Perform `R U R' U'`,” the tutor could explain:

> “We are moving this corner into its correct position while preserving the lower layer. Watch the right face first. Now perform the turn on your cube. Notice which pieces move and which remain in place.”

This shifts the experience from instruction-following to learning.

## 3. Functional specification

I recommend the following requirements for the first release.

Priority: P0 = essential for launch; P1 = next release; P2 = later expansion.

| Module                         | Required behavior                                                      | Priority |
| ------------------------------ | ---------------------------------------------------------------------- | -------- |
| Interactive cube               | 3D rendering, rotation, face turns, undo, reset                        | P0       |
| Guided tutor                   | Lessons broken into objectives, demonstrations, and practice           | P0       |
| Move explanations              | Plain language, standard notation, animated arrows, highlighted pieces | P0       |
| Learning verification          | Validate the learner's move and identify common errors                 | P0       |
| Automatic solver               | Solve a valid 3×3 state and replay the solution                        | P0       |
| Manual cube editor             | Enter sticker colors and validate the configuration                    | P0       |
| Progress tracking              | Completed stages, mistakes, hints, and mastery                         | P0       |
| Camera scanner                 | Capture all six faces, estimate colors, and validate the cube          | P1       |
| Adaptive teaching              | Change hints and practice based on error history                       | P1       |
| Speedcubing                    | Timer, scrambles, solve history, averages                              | P1       |
| Advanced methods               | CFOP, F2L, OLL, PLL, algorithm drills                                  | P2       |
| Additional cubes               | 2×2, 4×4, and other twisty puzzles                                     | P2       |
| Voice and conversational tutor | Spoken guidance and contextual questions                               | P2       |

Important design decision: The solver and tutor should be separate services. The solver calculates a valid path to the solved state; the tutor selects and explains moves according to the learning method. An optimal or near-optimal solution is not necessarily the best teaching sequence.

## 4. Design the tutor around how people learn

The teaching system is the most important part of the product. I would organize it as a sequence of learning stages, each with a clear goal, prerequisites, exercises, and a mastery check.

## Learning path: first solve to independence

### 01

Understand the cube

Centers, edges, corners, face orientation, and notation.

### 02

Build the first cross

Recognize edge pieces, match center colors, and position them correctly.

### 03

Solve the first-layer corners

Identify corner orientation and insert each piece without undoing the cross.

### 04

Complete the middle layer

Recognize edge cases and choose the correct insertion sequence.

### 05

Solve the last layer

Orient and permute the remaining pieces using understandable algorithms.

### 06

Practice independently

Solve new scrambles, receive hints only when needed, and explain the moves.

For every stage, define four teaching modes:

- Show me: demonstrate the full process.
- Guide me: give one move at a time.
- Test me: let the learner decide what to do next.
- Let me try: allow independent solving, with optional hints.

This structure is more useful than presenting a long video or a list of algorithms. It also provides measurable progress data.

## 5. Technical architecture for Android

Given the product requirements, I would use a native Android application with a deterministic cube model and a teaching engine.

Android user interface

Jetpack Compose · Lesson screens · Controls · Accessibility

3D cube and interaction engine

Cube rendering · Move animations · Input handling · State visualization

### Cube engine

State representation, legal moves, validation, and transitions.

### Solver engine

Search algorithms, solution generation, and replay.

### Tutor engine

Lesson rules, hint selection, error diagnosis, and mastery.

### Persistence

Lesson progress, preferences, and practice history.

### Recommended implementation choices

| Component           | Recommendation                                              |
| ------------------- | ----------------------------------------------------------- |
| Language            | Kotlin                                                      |
| UI                  | Jetpack Compose                                             |
| 3D visualization    | OpenGL ES or a suitable 3D rendering library                |
| Cube representation | Integer or compact array-based state model                  |
| Solver              | Kociemba two-phase algorithm for 3×3 solving                |
| Tutorial logic      | Rule-based state machine with structured lesson definitions |
| Persistence         | Room for local progress and practice history                |
| Camera scanning     | CameraX with on-device color classification                 |
| AI integration      | Optional, for natural-language explanations and questions   |

I would keep the core solving and validation logic deterministic and local. An LLM can help explain concepts conversationally, but it should not independently invent moves or determine whether a cube state is valid. Those tasks belong to the verified cube engine.

## 6. Define what success means

Do not measure the first version only by downloads or solve speed. The main objective is to determine whether people actually learn.

Track these product metrics:

- First-solve completion: percentage of beginners who complete a solve.
- Time to first solve: elapsed time from starting the course to solving the cube.
- Independent solve rate: percentage of learners who solve a new scramble without step-by-step instructions.
- Move error rate: incorrect moves per lesson or per solving stage.
- Hint dependency: how frequently learners require help over successive attempts.
- Retention: percentage of learners who return to continue practicing.

A particularly valuable experiment would compare two groups: learners using conventional move-by-move instructions and learners using contextual explanations with error feedback. Measure independent solving ability after the same amount of practice.

## 7. Choose the product direction before writing the full specification

I would settle one strategic question first. It affects the user interface, cube-state tracking, camera requirements, and complexity of the teaching engine.

## Product definition

Select your initial direction. You can leave the defaults or change any selection.

1\. Who is the primary learner?

Complete beginners who have never solved a cube

People who know the basics but get stuck

Existing solvers who want to improve speed

All levels, with personalized learning paths

2\. How should learners interact with the cube?

Virtual 3D cube only

Physical cube, with manual move confirmation

Both virtual and physical cubes

Bluetooth-connected smart cube as the main experience

3\. What kind of teacher should it be?

Structured lessons with fixed instructions

Adaptive tutor that detects errors and adjusts hints

Conversational AI tutor that answers questions

Hybrid: structured lessons, adaptive feedback, and AI explanations

4\. What do you want to specify first?

MVP requirements and acceptance criteria

Screens, navigation, and user experience

Detailed software architecture and algorithms

Complete product specification and development roadmap




# Rubik's Cube Tutor for Android — MVP Product Requirements Specification

Version 1.0 · October 2026 · Product, UX, and engineering baseline

## 1. Product definition

Product concept: An Android application that teaches complete beginners to solve a standard 3×3 Rubik’s Cube using an adaptive, interactive tutor that works with both a virtual cube and a physical cube.

Core product promise: The learner understands what to do, performs the move, receives feedback, corrects mistakes, and gradually becomes capable of solving the cube independently.

The MVP is not simply a camera scanner connected to a solver. It is a learning system that combines a verified cube model, a solving engine, a structured teaching curriculum, and an error-detection mechanism.

## Product principles

Teach before revealing

Explain the objective and give the learner an opportunity to think before showing the answer.

Verify every move

Maintain a known cube state and compare the expected state with the observed or entered state.

Explain errors constructively

Identify what went wrong, distinguish likely causes, and offer the smallest useful correction.

Build independence

Gradually reduce hints and test learning on new cube scrambles.

### Target user

A beginner who owns a physical cube, has little or no knowledge of cube notation, and may not understand how turning one face affects other pieces.

The product should accommodate learners who:

- Have never solved a cube.
- Can follow a demonstration but struggle to reproduce it.
- Frequently lose track of cube orientation.
- Make mistakes and do not understand how to recover.
- Want to learn rather than merely receive a solution.

### MVP scope assumptions

- Android phones and tablets; initial target Android 10 or later, subject to final device-support validation.
- Standard 3×3×3 cube only.
- One beginner solving method: layer by layer.
- English-language content initially.
- Offline operation for the core tutorial, virtual cube, state validation, and solver.
- Physical cube support initially uses manual move confirmation and optional camera-based state verification. Bluetooth smart cubes are excluded.

## 2. MVP feature boundaries

P0

Must ship

- Interactive 3D cube.
- Beginner learning path.
- Move demonstrations and notation explanations.
- Physical-cube mode with manual move confirmation.
- State tracking and move-error detection.
- Hints, undo, and recovery.
- Local solver and step-by-step solution playback.
- Local progress tracking.

P1

Release after core validation

- Camera-based scanning of all six faces.
- Automatic color classification and state validation.
- More advanced diagnosis of physical cube discrepancies.
- Additional learning analytics and adaptive lesson tuning.

Out of scope

Not required for the MVP

- Bluetooth-connected cubes.
- 2×2, 4×4, and other puzzle types.
- Competitive speedcubing platform.
- Social features and leaderboards.
- Open-ended AI-generated solving algorithms.

Scope recommendation: Treat camera scanning as a staged P1 capability. Physical-cube teaching can launch without it if the learner can confirm moves and the tutor can maintain a reliable expected state. This reduces initial computer-vision risk without compromising the core teaching proposition.

## 3. Functional requirements and acceptance criteria

Each requirement below has an identifier that can be used in development tickets, QA test cases, and release reviews.

### FR-01 — Interactive 3D cube

Requirement: The application shall display a standard 3×3 cube, support face turns, and allow the learner to rotate the viewing camera independently of the cube state.

| ID      | Acceptance criterion                                                                                                                                    |
| ------- | ------------------------------------------------------------------------------------------------------------------------------------------------------- |
| AC-01.1 | The initial cube renders all six faces with nine stickers per face.                                                                                     |
| AC-01.2 | A user can rotate the view without changing the logical cube state.                                                                                     |
| AC-01.3 | Each supported face turn updates the model and rendered cube to the same state.                                                                         |
| AC-01.4 | A 90° face-turn animation completes without leaving the logical state inconsistent, including when input is received during animation.                  |
| AC-01.5 | Undo restores the exact preceding cube state. Reset returns the cube to a defined initial state after confirmation when unsaved progress could be lost. |
| AC-01.6 | On the agreed reference device, a typical face-turn animation completes within 250 ms, with a target of at least 30 rendered frames per second.         |

Design decision: Separate camera gestures from face-turn gestures. Ambiguous gestures should not silently modify the cube.

### FR-02 — Beginner curriculum

Requirement: The application shall guide a learner through a complete layer-by-layer solving method.

| Stage                     | Required learning outcome                                        |
| ------------------------- | ---------------------------------------------------------------- |
| 1. Cube fundamentals      | Identify centers, edges, corners, and face notation.             |
| 2. First cross            | Place the four target edges correctly relative to their centers. |
| 3. First-layer corners    | Insert all four corners while preserving the completed cross.    |
| 4. Middle layer           | Insert the four middle-layer edges.                              |
| 5. Last-layer orientation | Orient the last-layer pieces using the chosen beginner method.   |
| 6. Last-layer completion  | Permute the remaining pieces and solve the cube.                 |
| 7. Independent practice   | Complete a new scramble with progressively fewer hints.          |

| ID      | Acceptance criterion                                                                                                  |
| ------- | --------------------------------------------------------------------------------------------------------------------- |
| AC-02.1 | Every lesson specifies prerequisites, objective, demonstration, learner action, verification rule, and recovery path. |
| AC-02.2 | The learner can replay a demonstration and pause or step through it.                                                  |
| AC-02.3 | The application checks the lesson's actual completion condition, not merely the number of moves performed.            |
| AC-02.4 | A learner can exit and resume a lesson without losing the saved checkpoint.                                           |
| AC-02.5 | The full curriculum includes at least one supported path from a legal scrambled cube state to the solved state.       |

Important: The lesson content must be authored and validated against the cube engine. Do not implement the curriculum as a hard-coded list of moves that assumes the learner never makes an error.

### FR-03 — Physical-cube mode

Requirement: The learner can manipulate a real cube while the application maintains a corresponding virtual state.

The baseline workflow is:

1. The tutor demonstrates a move or short sequence.
2. The learner performs the move on the physical cube.
3. The learner taps Done or enters the move they performed.
4. The application updates its expected state.
5. The tutor checks the resulting learning objective and continues or provides corrective guidance.

| ID      | Acceptance criterion                                                                                                                         |
| ------- | -------------------------------------------------------------------------------------------------------------------------------------------- |
| AC-03.1 | The learner can switch between virtual and physical modes without restarting the lesson.                                                     |
| AC-03.2 | The expected state is updated only after the learner confirms a physical move or a supported verification method establishes the result.     |
| AC-03.3 | The UI distinguishes the application's expected state from a physically observed state.                                                      |
| AC-03.4 | The learner can report an incorrect or uncertain move and enter a recovery flow.                                                             |
| AC-03.5 | Changing the virtual viewing angle does not alter the physical-cube move notation or expected state.                                         |
| AC-03.6 | The app does not claim to have detected a physical error when no sensor, camera observation, or user-provided move data supports that claim. |

This last criterion is central to trust. Without camera input or a connected cube, the application cannot independently know whether a learner actually performed a physical move.

### FR-04 — Adaptive tutor and error diagnosis

Requirement: The tutor shall evaluate the learner's state and actions, identify supported error categories, and select a useful next action.

Initial error categories:

- Incorrect move or direction.
- Incorrect piece selection.
- Incorrect piece orientation.
- Cube held in an unexpected orientation.
- Completed layer accidentally disrupted.
- Invalid or inconsistent entered cube state.
- Unknown discrepancy requiring additional input.

| ID      | Acceptance criterion                                                                                                                              |
| ------- | ------------------------------------------------------------------------------------------------------------------------------------------------- |
| AC-04.1 | Every supported error condition maps to a deterministic diagnostic rule or an explicitly defined uncertainty state.                               |
| AC-04.2 | The tutor explains the immediate objective and offers a correction relevant to the diagnosed error.                                               |
| AC-04.3 | The learner can request a hint without immediately revealing the entire solution.                                                                 |
| AC-04.4 | Hints progress from a conceptual clue to a highlighted target or move, and then to a complete demonstration.                                      |
| AC-04.5 | After an error, the learner can retry, undo, or request a guided recovery.                                                                        |
| AC-04.6 | The system does not silently replace the learner's state with the expected state.                                                                 |
| AC-04.7 | If the observed state is insufficient to diagnose an error, the tutor asks for additional information rather than asserting an unsupported cause. |

Adaptive policy for the MVP: Start with transparent rules, not machine learning. Use error history, repeated attempts, hint requests, and completion outcomes to adjust assistance.

For example:

- First mistake: explain the objective and show the affected piece.
- Repeated mistake: highlight the relevant face and demonstrate the turn.
- Continued difficulty: guide the learner through the move one step at a time.
- Successful repetition: return control to the learner and reduce assistance.

The thresholds should be configurable and validated through usability tests.

### FR-05 — Solver and solution playback

Requirement: The solver shall calculate a valid solution for a validated 3×3 state and present it as an explorable sequence.

| ID      | Acceptance criterion                                                                       |
| ------- | ------------------------------------------------------------------------------------------ |
| AC-05.1 | The solver accepts valid, reachable cube states and rejects invalid or unreachable states. |
| AC-05.2 | Applying the generated solution to the input state produces the solved state.              |
| AC-05.3 | The learner can advance, go back, replay, and pause the solution.                          |
| AC-05.4 | The application explains each move or short sequence in beginner-friendly language.        |
| AC-05.5 | Solver failure produces an explicit error or recovery prompt, not a fabricated solution.   |
| AC-05.6 | Solver tests cover the solved state, randomized legal scrambles, and invalid state inputs. |

A conventional two-phase solver such as Kociemba's is a reasonable baseline for finding efficient solutions. However, its output is not necessarily an ideal pedagogical sequence.

Engineering rule: Maintain separate interfaces for `solveCube(state)` and `teachStep(learnerState, lessonObjective)`. The first finds a solution; the second decides how to teach.

### FR-06 — Progress and mastery

Requirement: The application shall save learning progress locally and support meaningful mastery checks.

| ID      | Acceptance criterion                                                                                      |
| ------- | --------------------------------------------------------------------------------------------------------- |
| AC-06.1 | Completed lesson stages and the current checkpoint persist after app restart.                             |
| AC-06.2 | Progress records include attempts, hints used, and lesson completion outcomes.                            |
| AC-06.3 | A learner can repeat a lesson without corrupting prior progress records.                                  |
| AC-06.4 | Independent practice uses a fresh legal scramble and measures success without full step-by-step guidance. |
| AC-06.5 | A completed lesson is not treated as proof of mastery unless its defined mastery condition has been met.  |

A practical first mastery rule might require successful completion of the stage objective twice, with at least one attempt using reduced assistance. This is a hypothesis to test, not an established educational threshold.

### FR-07 — Camera scanning (P1)

Requirement: The application should eventually infer a physical cube state from camera observations.

Acceptance criteria for the camera release:

- All six faces can be captured with guided orientation prompts.
- The system estimates sticker colors and indicates uncertain classifications.
- The resulting state must pass cube legality validation before the solver is invoked.
- The user can manually correct uncertain sticker colors.
- Low confidence, glare, poor lighting, or incomplete captures lead to a recapture or correction flow.
- The scanner must not claim successful validation merely because 54 sticker colors have been entered.

Camera scanning needs its own device and lighting test plan. It should not be a launch blocker for the initial tutor if manual physical-cube mode is usable.

## 4. Technical architecture

The architecture should prioritize correctness, testability, offline operation, and separation between rendering, cube logic, and teaching decisions.

Android presentation layer

Jetpack Compose · Lesson UI · Controls · Accessibility

Application orchestration

Lesson state machine · User actions · Checkpoints · Error events

Cube domain engine

State, move transformations, legality, equality, solved-state detection.

Solver adapter

Solution search, move sequence, playback, timeout and error handling.

Teaching engine

Lesson objectives, diagnosis rules, hints, recovery and mastery.

Local data layer

Room · Progress · Checkpoints · Preferences · Learning events.

Conceptual architecture. Arrows represent dependencies, not a required runtime execution order.

### Recommended technology choices

| Area              | Recommendation                              | Reason                                                           |
| ----------------- | ------------------------------------------- | ---------------------------------------------------------------- |
| Language          | Kotlin                                      | Native Android development and strong type safety.               |
| UI                | Jetpack Compose                             | State-driven UI and reusable lesson components.                  |
| 3D rendering      | OpenGL ES or a vetted 3D library            | Interactive geometry and animated face turns.                    |
| Cube logic        | Pure Kotlin module                          | Deterministic, fast, independently testable logic.               |
| Solver            | Kociemba two-phase implementation           | Practical 3×3 solution generation.                               |
| Local persistence | Room                                        | Structured progress and checkpoint storage.                      |
| Asynchronous work | Kotlin coroutines                           | Keep rendering and UI responsive during solving and persistence. |
| Camera (P1)       | CameraX                                     | Camera lifecycle and image capture integration.                  |
| Automated testing | JUnit plus Android instrumentation/UI tests | Domain correctness and end-to-end validation.                    |

The choice between OpenGL ES and a higher-level rendering library should be finalized with a short technical spike. The cube's geometry is modest; interaction correctness, gesture behavior, and animation consistency are greater risks than raw polygon performance.

### Cube-state representation

Represent the cube's logical state independently of its visual mesh.

For a 3×3 cube, use a standard cubie representation:

- Corner permutation: which corner occupies each corner position.
- Corner orientation: the orientation of each corner.
- Edge permutation: which edge occupies each edge position.
- Edge orientation: the orientation of each edge.

The conventional model contains eight corners and twelve edges. Centers define the face reference frame.

This representation is compact and suitable for legal-state validation and solving algorithms. The renderer can derive sticker colors from the cubie state.

An alternative is a 54-sticker array. That is intuitive for rendering and scanning but requires additional work to validate piece identities and orientations. It is reasonable as an input/output format, but I would not use it as the sole canonical solver state.

### Domain interfaces

Define interfaces around responsibilities rather than UI screens. A Kotlin-style sketch:

```
interface CubeEngine {
    fun applyMove(state: CubeState, move: Move): CubeState
    fun validate(state: CubeState): ValidationResult
    fun isSolved(state: CubeState): Boolean
}

interface CubeSolver {
    suspend fun solve(state: CubeState): SolveResult
}

interface TutorEngine {
    fun evaluate(
        state: CubeState,
        lesson: LessonDefinition,
        history: LearnerHistory
    ): TutorDecision
}
```

These are proposed interfaces, not a complete implementation. In production, define explicit types for illegal states, cancellation, solver limits, and unexpected discrepancies.

## 5. State synchronization: the most important engineering risk

The application maintains three potentially different states:

Expected state

The state produced by the tutor's planned sequence of moves.

Virtual state

The state represented by the application's interactive cube.

Physical state

The real cube, which may differ from the expected state and may not yet be observed.

In virtual mode, the virtual state is authoritative for user-performed moves. In physical mode, the expected state is a prediction until the user confirms moves or a camera observation provides evidence about the real cube.

The system should not pretend that a physical cube's state is known when it is not.

### Required synchronization behavior

1. Each confirmed move creates an immutable state transition.
2. Rendering reflects the resulting logical state.
3. The lesson engine evaluates the new state against its current objective.
4. The application records the transition and relevant learner action.
5. If a discrepancy is detected, the tutor enters a recovery state rather than silently correcting the cube.
6. Undo restores the prior state and updates the lesson evaluation.

Acceptance test: Given a known starting state and a sequence of legal moves, the engine, virtual rendering model, and expected-state tracker must agree after every committed transition. Interruptions, repeated input, and undo must not introduce state drift.

## 6. Adaptive teaching logic

Use a deterministic state machine for the MVP. This provides explainable decisions and allows each teaching path to be tested.

\#chatgpt-mermaid-\_r_hc\_{font-family:-apple-system-body,ui-sans-serif,-apple-system,system-ui,"Segoe UI",Helvetica,"Apple Color Emoji",Arial,sans-serif,"Segoe UI Emoji","Segoe UI Symbol";font-size:16px;fill:rgb(237, 237, 237);}@keyframes edge-animation-frame{from{stroke-dashoffset:0;}}@keyframes dash{to{stroke-dashoffset:0;}}#chatgpt-mermaid-\_r_hc\_ .edge-animation-slow{stroke-dasharray:9,5!important;stroke-dashoffset:900;animation:dash 50s linear infinite;stroke-linecap:round;}#chatgpt-mermaid-\_r_hc\_ .edge-animation-fast{stroke-dasharray:9,5!important;stroke-dashoffset:900;animation:dash 20s linear infinite;stroke-linecap:round;}#chatgpt-mermaid-\_r_hc\_ .error-icon{fill:rgb(48, 48, 48);}#chatgpt-mermaid-\_r_hc\_ .error-text{fill:rgb(237, 237, 237);stroke:rgb(237, 237, 237);}#chatgpt-mermaid-\_r_hc\_ .edge-thickness-normal{stroke-width:1px;}#chatgpt-mermaid-\_r_hc\_ .edge-thickness-thick{stroke-width:3.5px;}#chatgpt-mermaid-\_r_hc\_ .edge-pattern-solid{stroke-dasharray:0;}#chatgpt-mermaid-\_r_hc\_ .edge-thickness-invisible{stroke-width:0;fill:none;}#chatgpt-mermaid-\_r_hc\_ .edge-pattern-dashed{stroke-dasharray:3;}#chatgpt-mermaid-\_r_hc\_ .edge-pattern-dotted{stroke-dasharray:2;}#chatgpt-mermaid-\_r_hc\_ .marker{fill:rgb(175, 175, 175);stroke:rgb(175, 175, 175);}#chatgpt-mermaid-\_r_hc\_ .marker.cross{stroke:rgb(175, 175, 175);}#chatgpt-mermaid-\_r_hc\_ svg{font-family:-apple-system-body,ui-sans-serif,-apple-system,system-ui,"Segoe UI",Helvetica,"Apple Color Emoji",Arial,sans-serif,"Segoe UI Emoji","Segoe UI Symbol";font-size:16px;}#chatgpt-mermaid-\_r_hc\_ p{margin:0;}#chatgpt-mermaid-\_r_hc\_ .label{font-family:-apple-system-body,ui-sans-serif,-apple-system,system-ui,"Segoe UI",Helvetica,"Apple Color Emoji",Arial,sans-serif,"Segoe UI Emoji","Segoe UI Symbol";color:rgb(237, 237, 237);}#chatgpt-mermaid-\_r_hc\_ .cluster-label text{fill:rgb(237, 237, 237);}#chatgpt-mermaid-\_r_hc\_ .cluster-label span{color:rgb(237, 237, 237);}#chatgpt-mermaid-\_r_hc\_ .cluster-label span p{background-color:transparent;}#chatgpt-mermaid-\_r_hc\_ .label text,#chatgpt-mermaid-\_r_hc\_ span{fill:rgb(237, 237, 237);color:rgb(237, 237, 237);}#chatgpt-mermaid-\_r_hc\_ .node rect,#chatgpt-mermaid-\_r_hc\_ .node circle,#chatgpt-mermaid-\_r_hc\_ .node ellipse,#chatgpt-mermaid-\_r_hc\_ .node polygon,#chatgpt-mermaid-\_r_hc\_ .node path{fill:rgb(9, 23, 44);stroke:rgb(31, 78, 148);stroke-width:1px;}#chatgpt-mermaid-\_r_hc\_ .rough-node .label text,#chatgpt-mermaid-\_r_hc\_ .node .label text,#chatgpt-mermaid-\_r_hc\_ .image-shape .label,#chatgpt-mermaid-\_r_hc\_ .icon-shape .label{text-anchor:middle;}#chatgpt-mermaid-\_r_hc\_ .node .katex path{fill:#000;stroke:#000;stroke-width:1px;}#chatgpt-mermaid-\_r_hc\_ .rough-node .label,#chatgpt-mermaid-\_r_hc\_ .node .label,#chatgpt-mermaid-\_r_hc\_ .image-shape .label,#chatgpt-mermaid-\_r_hc\_ .icon-shape .label{text-align:center;}#chatgpt-mermaid-\_r_hc\_ .node.clickable{cursor:pointer;}#chatgpt-mermaid-\_r_hc\_ .root .anchor path{fill:rgb(175, 175, 175)!important;stroke-width:0;stroke:rgb(175, 175, 175);}#chatgpt-mermaid-\_r_hc\_ .arrowheadPath{fill:rgb(175, 175, 175);}#chatgpt-mermaid-\_r_hc\_ .edgePath .path{stroke:rgb(175, 175, 175);stroke-width:1px;}#chatgpt-mermaid-\_r_hc\_ .flowchart-link{stroke:rgb(175, 175, 175);fill:none;}#chatgpt-mermaid-\_r_hc\_ .edgeLabel{background-color:rgb(0, 0, 0);text-align:center;}#chatgpt-mermaid-\_r_hc\_ .edgeLabel p{background-color:rgb(0, 0, 0);}#chatgpt-mermaid-\_r_hc\_ .edgeLabel rect{opacity:0.5;background-color:rgb(0, 0, 0);fill:rgb(0, 0, 0);}#chatgpt-mermaid-\_r_hc\_ .labelBkg{background-color:rgba(0, 0, 0, 0.5);}#chatgpt-mermaid-\_r_hc\_ .cluster rect{fill:rgb(48, 48, 48);stroke:rgba(255, 255, 255, 0.15);stroke-width:1px;}#chatgpt-mermaid-\_r_hc\_ .cluster text{fill:rgb(237, 237, 237);}#chatgpt-mermaid-\_r_hc\_ .cluster span{color:rgb(237, 237, 237);}#chatgpt-mermaid-\_r_hc\_ div.mermaidTooltip{position:absolute;text-align:center;max-width:200px;padding:2px;font-family:-apple-system-body,ui-sans-serif,-apple-system,system-ui,"Segoe UI",Helvetica,"Apple Color Emoji",Arial,sans-serif,"Segoe UI Emoji","Segoe UI Symbol";font-size:12px;background:rgb(48, 48, 48);border:1px solid rgba(255, 255, 255, 0.15);border-radius:2px;pointer-events:none;z-index:100;}#chatgpt-mermaid-\_r_hc\_ .flowchartTitleText{text-anchor:middle;font-size:18px;fill:rgb(237, 237, 237);}#chatgpt-mermaid-\_r_hc\_ rect.text{fill:none;stroke-width:0;}#chatgpt-mermaid-\_r_hc\_ .icon-shape,#chatgpt-mermaid-\_r_hc\_ .image-shape{background-color:rgb(0, 0, 0);text-align:center;}#chatgpt-mermaid-\_r_hc\_ .icon-shape p,#chatgpt-mermaid-\_r_hc\_ .image-shape p{background-color:rgb(0, 0, 0);padding:2px;}#chatgpt-mermaid-\_r_hc\_ .icon-shape .label rect,#chatgpt-mermaid-\_r_hc\_ .image-shape .label rect{opacity:0.5;background-color:rgb(0, 0, 0);fill:rgb(0, 0, 0);}#chatgpt-mermaid-\_r_hc\_ .label-icon{display:inline-block;height:1em;overflow:visible;vertical-align:-0.125em;}#chatgpt-mermaid-\_r_hc\_ .node .label-icon path{fill:currentColor;stroke:revert;stroke-width:revert;}#chatgpt-mermaid-\_r_hc\_ .node .neo-node{stroke:rgb(31, 78, 148);}#chatgpt-mermaid-\_r_hc\_ [data-look="neo"].node rect,#chatgpt-mermaid-\_r_hc\_ [data-look="neo"].cluster rect,#chatgpt-mermaid-\_r_hc\_ [data-look="neo"].node polygon{stroke:url(#chatgpt-mermaid-\_r_hc\_-gradient);filter:drop-shadow( 1px 2px 2px rgba(185,185,185,1));}#chatgpt-mermaid-\_r_hc\_ [data-look="neo"].swimlane.cluster rect{filter:none;}#chatgpt-mermaid-\_r_hc\_ [data-look="neo"].node path{stroke:url(#chatgpt-mermaid-\_r_hc\_-gradient);stroke-width:1px;}#chatgpt-mermaid-\_r_hc\_ [data-look="neo"].node .outer-path{filter:drop-shadow( 1px 2px 2px rgba(185,185,185,1));}#chatgpt-mermaid-\_r_hc\_ [data-look="neo"].node .neo-line path{stroke:rgb(31, 78, 148);filter:none;}#chatgpt-mermaid-\_r_hc\_ [data-look="neo"].node circle{stroke:url(#chatgpt-mermaid-\_r_hc\_-gradient);filter:drop-shadow( 1px 2px 2px rgba(185,185,185,1));}#chatgpt-mermaid-\_r_hc\_ [data-look="neo"].node circle .state-start{fill:#000000;}#chatgpt-mermaid-\_r_hc\_ [data-look="neo"].icon-shape .icon{fill:url(#chatgpt-mermaid-\_r_hc\_-gradient);filter:drop-shadow( 1px 2px 2px rgba(185,185,185,1));}#chatgpt-mermaid-\_r_hc\_ [data-look="neo"].icon-shape .icon-neo path{stroke:url(#chatgpt-mermaid-\_r_hc\_-gradient);filter:drop-shadow( 1px 2px 2px rgba(185,185,185,1));}#chatgpt-mermaid-\_r_hc\_ .node text{font-size:14px;font-weight:600;letter-spacing:normal;fill:rgb(232, 232, 232);}#chatgpt-mermaid-\_r_hc\_ .edgeLabels text{font-size:13px;font-weight:600;letter-spacing:-0.08px;fill:rgb(232, 232, 232);}#chatgpt-mermaid-\_r_hc\_ .node tspan[font-weight="normal"],#chatgpt-mermaid-\_r_hc\_ .edgeLabels tspan[font-weight="normal"]{font-weight:600;}#chatgpt-mermaid-\_r_hc\_ .edgeLabel .label rect{opacity:1;rx:13px;ry:13px;fill:rgb(19, 19, 19);stroke:rgb(47, 47, 47);stroke-width:1px;}#chatgpt-mermaid-\_r_hc\_ .node rect,#chatgpt-mermaid-\_r_hc\_ .node circle,#chatgpt-mermaid-\_r_hc\_ .node ellipse,#chatgpt-mermaid-\_r_hc\_ .node polygon,#chatgpt-mermaid-\_r_hc\_ .node path{fill:rgb(24, 24, 24);stroke:rgba(255, 255, 255, 0.1);stroke-width:1px;}#chatgpt-mermaid-\_r_hc\_ .node rect{rx:16px;ry:16px;}#chatgpt-mermaid-\_r_hc\_ .mermaid-decision .label-container{fill:rgb(19, 19, 19);stroke:rgb(47, 47, 47);stroke-dasharray:2,2;}#chatgpt-mermaid-\_r_hc\_ .edgePaths .flowchart-link{stroke:rgb(175, 175, 175);stroke-width:1px;stroke-linecap:round;stroke-linejoin:round;}#chatgpt-mermaid-\_r_hc\_ .marker{fill:rgb(175, 175, 175);stroke:rgb(175, 175, 175);}#chatgpt-mermaid-\_r_hc\_ :root{--mermaid-font-family:-apple-system-body,ui-sans-serif,-apple-system,system-ui,"Segoe UI",Helvetica,"Apple Color Emoji",Arial,sans-serif,"Segoe UI Emoji","Segoe UI Symbol";}Load lesson objectiveDemonstrate and explainLearner performs orconfirms moveUpdate cube stateState valid and objectivesatisfied?Confirm progressLesson complete?Assess masteryCause identifiable?Give targeted hintRequest clarification orrecaptureLearner wants help?Guided recovery or undoYesNoYesNoYesNoRetryShow demonstrationRecover

### Tutor decision model

Each lesson evaluation should produce a structured decision containing:

- Current learning objective.
- Whether the objective is satisfied.
- Diagnostic category, if supported.
- Confidence or certainty classification.
- Recommended next action.
- Explanation to display.
- Recovery options.
- Whether to advance the lesson.

Keep confidence handling simple initially: distinguish verified, likely, and unknown. Do not introduce numerical confidence scores unless the system can calibrate and validate them.

### Example acceptance scenario

Given: A beginner has completed the first cross and is practicing corner insertion.

When: The learner makes a move that disrupts the completed cross.

Then:

1. The engine identifies that the cross condition is no longer satisfied.
2. The tutor explains that the previous layer was disturbed.
3. The learner can undo or request guided recovery.
4. The system retains the prior checkpoint.
5. The next lesson stage does not unlock until its required condition is met.

The tutor must not assume that every incorrect state has a unique explanation. Different move sequences can produce the same state, so diagnostics should be based on available action history and state comparisons.

## 7. Non-functional requirements

These requirements are proposed engineering targets and should be validated on a defined reference-device set.

| Category           | Requirement                                   | Acceptance criterion                                                                                                       |
| ------------------ | --------------------------------------------- | -------------------------------------------------------------------------------------------------------------------------- |
| Responsiveness     | Keep interactions responsive                  | Face-turn input is acknowledged within 100 ms under normal load.                                                           |
| Rendering          | Smooth cube interaction                       | At least 30 FPS during typical interaction on the minimum supported reference device.                                      |
| Solver performance | Solve ordinary valid scrambles locally        | Target 95% of the agreed test corpus solved within 3 seconds on the reference device.                                      |
| Reliability        | Preserve state consistency                    | Zero state mismatches across the defined randomized move and undo test suite.                                              |
| Offline support    | Core teaching does not require network access | A fresh installation can complete core lessons and solve a valid cube without connectivity.                                |
| Persistence        | Recover interrupted sessions                  | The most recent committed checkpoint is restored after a forced app restart.                                               |
| Accessibility      | Support usable controls and explanations      | Core flows have accessible labels, adequate contrast, scalable text, and non-gesture alternatives for essential actions.   |
| Privacy            | Minimize data collection                      | No account is required for core use; learning history remains local unless a future feature explicitly requires otherwise. |
| Compatibility      | Support agreed Android devices                | All release-critical tests pass on the defined OS versions, screen sizes, and hardware profiles.                           |

The solver benchmark should be measured using a representative corpus of legal states, not only easy scrambles. Report median and 95th-percentile latency, memory use, and failure rate. A solver timeout must return a controlled failure result and never block the UI thread.

### Privacy and data handling

For the MVP:

- Store progress locally using app-private storage.
- Avoid collecting camera images unless needed for the scanning feature and explicitly disclosed.
- Do not transmit cube states or learning history to a server by default.
- Avoid collecting personally identifying information for basic use.
- Provide a way to reset local progress.

## 8. Test strategy and release gates

The cube engine should receive the strongest automated test coverage because every other subsystem depends on its correctness.

## Release test plan

Gate 1 — Cube engine correctness

Unit and property-based tests

- Every face turn followed by its inverse restores the original state.
- Four quarter-turns of the same face restore the original state.
- Random legal move sequences followed by their inverse restore the original state.
- Legal-state validation rejects impossible orientation and permutation combinations.
- A solver's output solves every state in the agreed test corpus.

Gate 2 — Tutor correctness

Rule and scenario tests

- Every lesson has a defined completion predicate.
- Every supported error category has an actionable recovery path.
- Unknown discrepancies never produce unsupported diagnostic claims.
- Hint escalation and mastery transitions behave according to configuration.

Gate 3 — End-to-end behavior

Android integration and UI tests

- A beginner can launch a lesson and complete the virtual-cube workflow.
- Physical mode supports manual move confirmation and recovery.
- Undo, pause, restart, and resume preserve valid state.
- Lesson completion and progress persistence work across app restarts.

Gate 4 — Usability validation

Observed testing with target users

- Beginners can understand move instructions without prior notation knowledge.
- Users can recover from deliberate mistakes.
- Participants can explain the immediate objective of a lesson in their own words.
- The test records task completion, hint use, and points of confusion.

### Proposed release gates

I would not release the MVP until:

1. All critical cube-engine invariants pass automated testing.
2. Every lesson has validated success and failure conditions.
3. Virtual and physical modes do not silently lose synchronization.
4. A representative beginner test group can complete the intended learning path with documented usability issues addressed.
5. Offline behavior, state recovery, and accessibility are verified.

A specific learner completion percentage should be set after a pilot establishes a baseline. Setting an arbitrary high percentage before usability testing would create a misleading release criterion.

## 9. Development roadmap

I recommend four implementation phases. The estimates below are planning assumptions for a small, experienced team, not commitments.

## P0.1

Weeks 1–2

Cube engine and technical prototype

Implement cube state, face-turn transformations, validation, 3D rendering, and solver integration.

Exit criterion: a randomized scramble can be applied, rendered, solved, and verified without state mismatch.

## P0.2

Weeks 3–5

Tutor and lesson framework

Implement lesson definitions, objective verification, move explanations, hints, undo, and recovery.

Exit criterion: the first cross and corner lessons pass their automated scenario tests.

## P0.3

Weeks 6–8

Full beginner path and physical-cube workflow

Complete the layer-by-layer curriculum, manual physical-cube interaction, local persistence, and solution playback.

Exit criterion: the full learning path is usable offline and passes end-to-end tests.

## P0.4

Weeks 9–10

Pilot and release hardening

Test with beginners, refine instructions, address usability problems, and validate performance and compatibility.

Exit criterion: all release gates are met and critical defects are resolved.

Camera scanning should follow as a separate phase, estimated at another 2–4 weeks for an initial implementation and integration, with additional time if color classification or device compatibility proves difficult.

## 10. Initial product backlog

These are the first engineering epics I would create in the project tracker.

| Epic | Deliverable                                   | Dependency               |
| ---- | --------------------------------------------- | ------------------------ |
| E01  | Cube domain model and move transformations    | None                     |
| E02  | Cube validation and solver adapter            | E01                      |
| E03  | 3D cube rendering and gestures                | E01                      |
| E04  | Lesson schema and curriculum engine           | E01                      |
| E05  | Tutor diagnostics and recovery                | E01, E04                 |
| E06  | Physical-cube manual interaction              | E01, E04                 |
| E07  | Progress persistence and checkpoints          | E04                      |
| E08  | Solution playback and explanation             | E02, E03, E04            |
| E09  | Accessibility, performance, and compatibility | E03–E08                  |
| E10  | Camera scanning                               | E01, E03; planned for P1 |

A lesson should be data-driven rather than implemented as a separate screen with custom logic. That makes it easier to add languages, modify teaching rules, and test alternative explanations.

## 11. MVP definition of done

The MVP is complete when a new user can:

- Start without an account and learn the basic cube concepts.
- Manipulate the virtual cube and understand face-turn notation.
- Follow the complete beginner curriculum.
- Practice with a physical cube using explicit move confirmation.
- Receive appropriate feedback for supported errors.
- Undo a mistake or enter a guided recovery path.
- Solve a valid cube using the local solver.
- Understand the purpose of each taught move or sequence.
- Resume a lesson after closing the application.
- Attempt a fresh scramble with reduced assistance.
- Use the core experience without an internet connection.

The final two capabilities are particularly important: successful solution playback is not the same as successful teaching. The product should demonstrate that learners become more independent over repeated practice.

## 12. Recommended next engineering decision

Before implementing the full Android application, build a small proof of concept that validates the three highest-risk components:

1. A mathematically correct cube-state engine.
2. A 3D cube whose animation remains synchronized with that engine.
3. A lesson objective that detects when a learner's move helps or harms progress.

If these work together, the rest of the MVP can be developed around stable interfaces.

The most valuable next deliverable is a detailed software design specification containing the Kotlin domain models, cube-state transformations, lesson-definition schema, tutor decision rules, state diagrams, and unit-test cases. That will convert the product requirements above into an implementable engineering blueprint.



Your background in C#, Clean Architecture, functional programming, and hexagonal architecture is a good foundation for this project. I would keep those architectural principles, but adapt their implementation to Android rather than reproduce a .NET application structure in Kotlin.

The project is particularly suitable as a learning exercise because it combines familiar engineering problems—domain modeling, state transitions, algorithms, and testing—with new challenges in mobile UX, rendering, gestures, and lifecycle management.

## 1. Recommended technology strategy

You have three realistic options.

| Approach                                 | Advantages                                                                                           | Trade-offs                                                                                                              |
| ---------------------------------------- | ---------------------------------------------------------------------------------------------------- | ----------------------------------------------------------------------------------------------------------------------- |
| Native Android: Kotlin + Jetpack Compose | Best opportunity to learn Android conventions, lifecycle, native UI, and mobile development directly | You must learn Kotlin, Android tooling, and rendering integration                                                       |
| .NET MAUI + C#                           | Reuses your strongest language and established .NET skills                                           | Less exposure to native Android development; 3D cube rendering still requires a separate solution                       |
| Unity + C#                               | Strong 3D rendering and interaction tooling                                                          | More engine overhead for a lesson-oriented application; less aligned with conventional Android application architecture |

My recommendation is Kotlin + Jetpack Compose, with a small, pure Kotlin domain core and a separate 3D presentation layer.

Because learning mobile development is one of your goals, native Android gives you the most direct experience. If your priority were instead delivering a commercial prototype as quickly as possible while maximizing C# reuse, I would reconsider .NET MAUI.

## 2. How your existing architecture maps to Android

Android's official guidance recommends a UI layer, a data layer, unidirectional data flow, and an optional domain layer for complex or reusable business logic. This fits well with your architectural background.&#x20;

[image](https://www.google.com/s2/favicons?domain=https://developer.android.com\&sz=32)

Android Developers+1



I would map your preferred approach as follows:

Presentation adapters

Jetpack Compose screens · ViewModels · UI state

Android-specific

Application core — use cases

Start lesson · Execute move · Evaluate progress · Request hint · Solve cube

Framework-independent Kotlin

Domain model and ports

Cube state · Lesson rules · Solver interface · Progress repository interface

Pure business logic

Solver adapter

Kociemba implementation

Persistence adapter

Room database

Rendering adapter

3D cube renderer

Observation adapter

Manual input, later camera

The diagram shows conceptual dependencies. In a clean hexagonal implementation, the domain and application core define the interfaces they need, while infrastructure adapters implement those interfaces. Dependencies point inward.

### What to retain from your current approach

- Clean Architecture: keep business rules independent of Android APIs.
- Hexagonal architecture: use ports for persistence, solving, and cube-state observation where substitution is valuable.
- Functional programming: model moves as deterministic state transformations.
- Test-driven development: test the cube engine and teaching rules without launching Android.
- Dependency inversion: inject implementations rather than constructing infrastructure inside use cases.

### What to adapt

Do not automatically create a separate project or module for every layer. For a learning project, start with a small number of modules and split them only when boundaries are stable.

Android also has lifecycle-aware state management and UI conventions that are worth learning rather than abstracting away. Compose screens should render state and dispatch user actions; ViewModels should coordinate presentation state and application use cases. The cube model itself should not depend on either.&#x20;

[image](https://www.google.com/s2/favicons?domain=https://developer.android.com\&sz=32)

Android Developers+1



## 3. Use functional core, imperative shell

This is probably the most natural fit for your experience.

The cube engine is an excellent functional core: the same state and move must always produce the same result.

For example, in Kotlin:

```
data class CubeState(
    val corners: List<Int>,
    val cornerOrientations: List<Int>,
    val edges: List<Int>,
    val edgeOrientations: List<Int>
)

enum class Face {
    U, D, L, R, F, B
}

data class Move(
    val face: Face,
    val quarterTurns: Int
)

fun applyMove(
    state: CubeState,
    move: Move
): CubeState {
    // Apply a deterministic cube transformation.
    TODO("Implement and test the transformation")
}
```

This is a starting model, not a complete cube implementation. A production version must enforce representation invariants, validate move values, and ensure the permutation and orientation transformations are mathematically correct.

Notice the similarities with C#:

- `data class` provides value-oriented data modeling.
- `enum class` defines a closed set of face values.
- Functions can be pure and independently tested.
- Immutable values make undo, replay, and debugging easier.

The imperative shell handles things that genuinely have side effects: touch events, animation frames, camera input, database writes, and Android lifecycle events.

One important nuance: the `List` interface alone does not guarantee immutability. Avoid leaking mutable collections into the domain, and consider compact arrays or dedicated immutable value types for the performance-sensitive solver.

## 4. Suggested Kotlin project structure

Start with this structure rather than a large enterprise-style template.

```
rubik-tutor/
├── app/
│   └── presentation/
│       ├── home/
│       ├── lesson/
│       ├── cube/
│       └── progress/
├── core/
│   ├── domain/
│   │   ├── cube/
│   │   ├── lesson/
│   │   └── learning/
│   └── application/
│       └── usecase/
├── adapters/
│   ├── solver/
│   ├── persistence/
│   └── rendering/
└── core-tests/
    ├── cube/
    ├── solver/
    └── tutor/
```

Initially, these can be packages in a single Gradle module. Once the domain code is stable, move `core:domain` and `core:application` into a separate Kotlin/JVM module so they can be tested without Android dependencies.

That separation is a useful milestone in your learning journey: you will be able to demonstrate that your business logic is independent of the mobile framework.

## 5. Your learning roadmap

I would not begin by building the entire app. Use the cube as a sequence of small, progressively harder engineering exercises.

Phase 1 — Learn Kotlin through the cube domain

Implement moves, immutable state transitions, validation, and undo. Write unit tests before building the UI.

Deliverable: a working cube engine with no Android dependencies.

Phase 2 — Learn Android fundamentals

Build a simple screen with buttons that execute moves. Learn Compose state, ViewModels, navigation, lifecycle, and local persistence.

Deliverable: an Android app that manipulates and displays a cube state.

Phase 3 — Learn interactive graphics

Implement the 3D cube, touch-based camera rotation, face selection, and synchronized animations.

Deliverable: a cube that remains consistent with the domain model under interaction.

Phase 4 — Build the tutor

Implement lesson objectives, feedback rules, error recovery, checkpoints, and adaptive hints.

Deliverable: one complete lesson that can detect a wrong move and guide recovery.

Phase 5 — Integrate the solver and physical-cube workflow

Add solution playback, manual move confirmation, persistence, and the remaining curriculum. Add camera scanning only after the baseline is reliable.

Deliverable: a complete, testable MVP.

## 6. The three decisions I would make now

1. Use Kotlin, not C#, for the primary implementation if learning Android is a real project goal.
2. Keep the cube engine framework-independent and use it as the first development milestone.
3. Postpone complex 3D rendering until the domain model is correct. Start with a simple 2D representation or a basic cube visualization, then replace it with interactive 3D without changing the business logic.

The third decision is especially important. Rendering, camera gestures, animation timing, and cube-state correctness are separate problems. Solving all of them simultaneously makes debugging much harder.

The guiding principle is to preserve the architectural discipline you already know while learning the conventions and lifecycle of a new platform. You do not need to abandon Clean Architecture to develop for Android; you need to implement it in a way that remains idiomatic to Android.
