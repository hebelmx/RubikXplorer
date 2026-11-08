# Epic 8 Context: Use my real cube

<!-- Generated from planning artifacts. Regenerate with compile-epic-context if planning docs change. -->

## Goal

A Learner can follow Lessons and Solution Playback on their physical cube, with the app honestly tracking the Expected State and handling mistakes and mismatches without a camera. The app never pretends to see the real cube: the Expected State advances only when the Learner confirms a turn or enters the turn they actually made, mismatches escalate through an honest no-camera discovery ladder, switching between virtual and physical modes mid-session is seamless and safe, and the first physical solve is confirmed honestly to prove zero silent desync.

## Stories

- Story 8.1: Physical-mode transitions in the reducer
- Story 8.2: Follow a Lesson on my real cube
- Story 8.3: Report a mismatch honestly without a camera
- Story 8.4: Leave and resume physical mode safely
- Story 8.5: Follow a Solution on my real cube
- Story 8.6: Confirm the first physical solve and prove no desync

## Requirements & Constraints

- **Physical-cube tracking & honesty:**
  - The app tracks the Expected State while the Learner works on a real cube, never pretending to see the physical cube without evidence.
  - The Expected State updates *only* after explicit Learner confirmation (*Done* or entering the Move actually made), or after an accepted full observation (e.g. from the Manual Cube Editor).
  - The Expected State always carries a visible `EXPECTED` badge (dashed outline); it is never presented as an observed state without verification.
  - View Rotation changes neither Notation nor the Expected State.
- **Mid-session mode switching:**
  - The Learner can switch between virtual mode and Physical-cube Mode mid-Lesson without restarting or losing progress.
  - Switching to physical presents a *Confirm your cube* card offering *It matches* or *Enter it* via the Manual Cube Editor (*Scan it* stays hidden while camera scanning is unavailable).
  - Switching back to virtual sets `virtual := expected`, clears redo, acts as an undo barrier, and presents: "The on-screen cube now matches what we expected on yours."
- **Honest mismatch handling without a camera:**
  - The app claims to have detected an error only when an entered Move or validated observation provides evidence; otherwise Certainty is strictly ? *Unknown*.
  - When the Learner reports *My cube doesn't match* or *I'm not sure what I did*, the tutor escalates through the no-camera discovery ladder:
    1. (Quick Check skipped while camera scan is unavailable)
    2. Which turn was made? (Move keys)
    3. One specific face check (face/corner picker: "Look at the front face — which corner is white?")
    4. Full re-entry via the Manual Cube Editor (`EditorEntered`)
  - When the Learner enters the Move they actually made, `Assess` uses it as evidence: Certainty is *Verified* only if exactly one candidate Move fits, otherwise *Likely*.
  - An accepted re-entered cube (`EditorEntered`) becomes a `Full` observation and offers Guided recovery.
  - Harmful Move diagnosis on a predicted state is at most *Likely*, never false *Verified*. While awaiting confirmation or in mismatch, no automated feedback is given.
- **Physical undo & Solution Playback:**
  - Undoing a physical Move reverts `expected` and instructs the Learner: "Turn {Move}' on your cube to undo."
  - In Solution Playback, the Learner can tap *I'm following on my cube*: playback enters physical mode, each *Done* advances one step, and templated MoveNotes display under each Move. The final step states "Your cube should be solved now." without asserting *solved* as observed.
- **First physical solve & verification gate:**
  - At the end of Stage 6 in physical mode, meeting the Objective on the Expected State does not trigger milestone celebrations. The app states "Your cube should be solved now. Check it with the camera, or tell me if it is." and plays the milestone only after explicit Learner confirmation (*Yes, it's solved*).
  - Verification gate: the end-to-end test suite across virtual and physical flows (Moves, confirmations, undo, mode switches, pause, kill, and resume) must prove zero silent desync between `virtual`, `expected`, and the rendered cube.
- **Offline, privacy & pure core rules:**
  - Zero network usage, no analytics/crash SDKs, strict memory and thread isolation.
  - State machine, reducer transitions, and assessment logic remain pure Kotlin in `:core:*` with `>= 80%` test coverage.

## Technical Decisions

- **Sync State Machine:**
  - States: `Virtual`, `PhysicalAwaitingConfirm`, `PhysicalPredicted`, `PhysicalVerified`, `PhysicalMismatch`.
  - State transitions in `reduce(SessionState, SessionEvent)`:
    - `Virtual` → `PhysicalAwaitingConfirm` on `ModeSwitched(physical)`.
    - `PhysicalAwaitingConfirm` → `PhysicalPredicted` on `PhysicalConfirmed(match)`, `EditorEntered`, or `ScanAccepted(Full)`.
    - `PhysicalPredicted` → `PhysicalPredicted` on `PhysicalConfirmed(move)` (marks any prior `observed` stale).
    - `PhysicalPredicted` → `PhysicalVerified` on `ScanAccepted(matches expected)` with `Assess = VERIFIED` (Epic 10).
    - `PhysicalVerified` → `PhysicalPredicted` on `PhysicalConfirmed(move)`.
    - `PhysicalPredicted` or `PhysicalVerified` → `PhysicalMismatch` on `MismatchReported` or differing observation.
    - `PhysicalMismatch` → `PhysicalPredicted` on `Undo`, `GuidedRecovery`, or `AdoptObserved(Full)`.
    - `PhysicalPredicted` or `PhysicalVerified` → `Virtual` on `ModeSwitched(virtual)` (`virtual := expected`).
  - `SessionState` fields: holds `virtual: CubeState`, `expected: CubeState`, `observed: PhysicalObservation?`, and `syncState`.
  - In physical mode, `virtual` mirrors `expected`.
  - `PhysicalObservation` is either `Full(state)` (produced strictly via `CubeValidator`) or `Partial(...)`. A `Partial` is never completed from `expected` and never becomes a `CubeState`.
- **Undo / Redo semantics & barriers:**
  - Undoing `PhysicalConfirmed(move)` reverts `expected` and produces presentation instructions for the inverse turn.
  - `ModeSwitched`, `ScanAccepted`, `EditorEntered`, and `CheckpointRestored` act as undo barriers and clear redo.
  - Switching back to virtual sets `virtual := expected`, clears redo, and stops undo from crossing the mode boundary.
- **Session persistence & restore in physical mode:**
  - Restoring a Checkpoint or snapshot in physical mode always lands in `PhysicalAwaitingConfirm`.
  - `observed` is cleared and no asynchronous effects are resumed.
  - The UI presents the *Confirm your cube* card before continuing.
- **Tutor Assessment & Evidence:**
  - Certainty is produced *only* by `tutor/Assess` in `:core:domain`.
  - A single `CandidateEnumerator(expected, maxDepth, moveSet)` evaluates candidate explanations for discrepancies.
  - A diagnosis is `VERIFIED` only on a virtual cube or after an accepted verified observation; on a predicted state it is at most `LIKELY`.
  - When the Learner enters a Move actually made, `Assess` uses it as evidence to form a `Diagnosis`.
- **Solution Playback in physical mode:**
  - Handled by `PlaybackController` in physical mode where auto-play is paused and manual confirmation (*Done*) advances one step.
  - Step notes come solely from templated `MoveNote.of(move)`.

## UX & Interaction Patterns

- **Physical controls:**
  - Primary button is always **Done** (remains primary even under a recovery prompt).
  - Secondary action: *I did something else* (reveals Move key bar to enter the turn actually made).
  - Overflow menu contains *My cube doesn't match* and *I'm not sure what I did*.
  - Move key bar is hidden by default in Physical-cube Mode, appearing only after *I did something else* or during mismatch diagnosis.
- **Status indicators & badges:**
  - Frame label in cube stage top-left: switches from `VIRTUAL` to `EXPECTED` with a dashed `expected-badge` border (1.5dp `ink-secondary`).
  - No Certainty chip is displayed while in predicted state (`EXPECTED` badge only).
  - Certainty chip (`rounded.full`, icon + label + status color) appears when evidence exists: ✓ *Verified*, ≈ *Likely*, ? *Unknown*. It is tappable to explain evidence.
- **Objective completion & Notice copy in physical mode:**
  - When the Objective is met on the Expected State: panel reads "If your cube matches the picture, this step is done.", keeps the `EXPECTED` frame badge, enables *Next*, and shows no celebration animations.
  - First solve completion (end of Stage 6): prompts "Your cube should be solved now. Check it with the camera, or tell me if it is." (camera option hidden while camera scanning is unavailable). Milestone burst plays only after the Learner taps *Yes, it's solved*.
- **Mismatch resolution & ladder flows:**
  - Triggered by *My cube doesn't match* or *I'm not sure what I did*.
  - Frame shows ? *Unknown*. Prompt sequence:
    1. "Which turn did you make?" (presents Move keys)
    2. "Look at the front face — which corner is white?" (single-face inspection)
    3. "Let's re-enter your cube" (routes to Manual Cube Editor)
  - Recovery options: inline prompt with *Undo*, *Guide me back* (Guided recovery), or *Try again*.
- **Mode transitions & Confirm-your-cube card:**
  - Switching to physical: displays *Confirm your cube* card with *It matches* / *Enter it* (*Scan it* hidden until Epic 10).
  - Switching to virtual: displays notification "The on-screen cube now matches what we expected on yours."

## Cross-Story Dependencies

- **Preceding foundations:**
  - Epic 1 & 2: Pure domain cube model, `SessionEvent`, `reduce()`, `MoveIntent`, and Lesson engine.
  - Epic 3: `ManualCubeEditor` and `CubeValidator` for entering and validating physical states, and `SolveCube` / `PlaybackController` for Solution Playback.
  - Epic 4: Tutor `Assess`, `TutorDecision`, Error Categories, Certainty model, and `PlanRecovery` (Guided recovery).
  - Epic 5: 3D renderer and cube stage components (`CubeRenderer`, frame label, `expected-badge`).
  - Epic 6: Session persistence, snapshot / checkpoint writer, and cold-start restore (`PhysicalAwaitingConfirm`).
  - Epic 7: Course progression, Stage 6 completion, and milestone moment.
- **Internal sequence:**
  - Story 8.1 establishes the core Sync State Machine, state fields (`expected`, `syncState`), reducer transitions, and physical undo in `:core:application`.
  - Story 8.2 implements the Lesson UI controls (**Done**, *I did something else*, `EXPECTED` badge, Objective-met handling).
  - Story 8.3 integrates mismatch reporting, the non-camera escalation ladder, and Manual Cube Editor re-entry into the tutor/recovery flow.
  - Story 8.4 wires mid-session virtual/physical switching and safe checkpoint restore into `PhysicalAwaitingConfirm`.
  - Story 8.5 extends physical confirmation to Solution Playback.
  - Story 8.6 delivers the first-solve physical confirmation flow and comprehensive end-to-end sync verification suite.
- **Consumed by later epics:**
  - Epic 10 builds directly on this foundation by adding camera-based verification: `ScanAccepted`, Quick Check as the first rung of the mismatch ladder, and camera verification for physical solve.
