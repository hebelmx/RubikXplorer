---
title: Input reconciliation — UX spines vs PRD, addendum and architecture spine
created: 2026-10-29
spines:
  - DESIGN.md
  - EXPERIENCE.md
inputs:
  - ../../prds/prd-RubikXplorer-2026-10-29/prd.md
  - ../../prds/prd-RubikXplorer-2026-10-29/addendum.md
  - ../../architecture/architecture-RubikXplorer-2026-10-29/ARCHITECTURE-SPINE.md
counts: { high: 6, medium: 17, low: 16 }
---

# Reconciliation: UX spines vs PRD and architecture

Each finding gives the input and where it says so, the gap (dropped, weakened or contradicted), where the fix should go, and a severity.

## High

### H1. Physical-mode success is shown as a fact when it rests only on the Expected State
- **Input:** PRD Principle 3 (§1.1); FR-7 ("presented as observed only after a Scan has verified it"); FR-26 ("offers a Quick Check at Stage milestones"); UJ-1 climax ("The app confirms it"); AD-5.
- **Gap:** EXPERIENCE State Patterns "Objective met" and Flow 1 steps 4 and 8 show a check, a violet pulse, Notice and the milestone line "Solved. That's your first solve — on your own cube." after *Done* alone. In Physical-cube Mode that is a prediction. The spines never separate *Objective met (expected)* from *Objective met (verified)*, and they never offer the milestone Quick Check FR-26 calls for.
- **Land in:** EXPERIENCE State Patterns. Add a row "Objective met, physical (expected)": the wording says what the cube *should* show now, keeps the EXPECTED frame and offers an optional Quick Check. Gate the first-solve milestone copy on VERIFIED, or word it conditionally ("Your cube should be solved — check it?"). Update Flow 1 steps 4 and 8, and the DESIGN.md Milestone moment component.
- **Severity:** high

### H2. FR-3 undo, redo and reset have no UX
- **Input:** FR-3 (undo, redo, reset; reset confirms when it would discard unsaved Lesson progress; a new Move clears redo); AD-3 (undo barriers: `ScanAccepted`, `ModeSwitched`, `CheckpointRestored`); addendum §C.6.
- **Gap:** Undo appears only inside the recovery prompt. There is no general Undo/Redo control on the cube stage or the Move key bar, no Reset control, no reset confirmation, and no treatment when redo is cleared or an undo barrier is reached. Undoing a `PhysicalConfirmed` ("perform the inverse on your cube") also has no UX.
- **Land in:** DESIGN Components (cube stage or Move key bar: Undo, Redo, Reset). EXPERIENCE Component Patterns (rules, including the physical-undo prompt "Turn R' on your cube to undo") and State Patterns (reset confirmation; undo unavailable after a barrier).
- **Severity:** high

### H3. FR-13 adaptive assistance is invisible and undefined
- **Input:** FR-13 table (first mistake → explain and highlight; repeated → demonstrate; continued → step by step; repeated success → less help); Principle 5; AD-18 `ADAPTIVE_ASSISTANCE` flag; cut order item 6 (falls back to a fixed Hint Ladder).
- **Gap:** Neither spine mentions adaptive assistance. Open questions: does escalation switch the Teaching Mode (the switcher "remembers last mode"), auto-play a Demonstration, or only change the copy? Is the Learner told why help changed, and can they decline it? How does "less assistance" show? What happens when the flag is OFF?
- **Land in:** EXPERIENCE Component Patterns (an "Assistance level" row tied to the Hint Ladder and switcher), State Patterns (escalated, reduced, flag OFF), and one Key Flow beat (e.g. Flow 1 after a second mistake).
- **Severity:** high

### H4. The FR-4 skip-ahead check has no flow, and FR-17's unlock path is dropped
- **Input:** FR-4 ("I already know this" runs a check that solves that Stage's Objective from a fresh Scramble; passing marks *skipped*, not *mastered*); FR-17 (2-look unlocks "after the beginner Course **or a skip-ahead check**"); UJ-2 (Diego can already solve).
- **Gap:** Welcome offers "I can already solve it (skip-ahead check)" but no flow, surface or states follow it. Stage detail has no per-Stage "I already know this" action. Pass and fail outcomes and copy are missing. Flow 2 step 1 says *Faster last layer* unlocks "after the Course", which drops the skip-ahead path that Diego's journey needs.
- **Land in:** EXPERIENCE IA (add the action to Stage detail; say where the Welcome option leads), State Patterns (check passed → ◇ Skipped; check failed → "Start the Stage" offered, no penalty), Key Flows (a skip-ahead flow, or a new Flow 2 step 0).
- **Severity:** high

### H5. FR-20 trend, Mastery Condition progress and Independent practice have no designed surface
- **Input:** FR-20 ("The Learner can see their own hint and error trend across attempts"; Mastery Condition = 2 completions, 1 with reduced help); SM-5 and SM-7 (on-device); counter-metrics SM-C1 (don't push hints toward zero; optimize the *trend*) and SM-C3 ("Learned" is not a mastery signal); MVP Definition of Done ("attempt a fresh Scramble with reduced help").
- **Gap:** The You tab lists "hint and error trends, Algorithm counts" but:
  - DESIGN.md has no trend component (sparkline or per-Stage list);
  - there is no rule that it shows the trend, not totals, and never frames hint use as bad;
  - Stage detail does not show Mastery Condition progress ("1 of 2 solves, 1 with reduced help");
  - Independent practice has no behavior spec (what "reduced assistance" looks like, the end-of-attempt summary);
  - Algorithm counts could read as mastery, against SM-C3.
- **Land in:** DESIGN Components (a Trend row and a Mastery progress line). EXPERIENCE IA (You and Stage detail), Component Patterns (trend rules: per-Stage, attempts in order, neutral ink, no totals as a score), and a Key Flow for practice to mastery.
- **Severity:** high

### H6. The FR-24 Color Calibration flow is missing
- **Input:** FR-24 (calibrate against the six centers; persists; separate reset); AD-9 (only `UpdateCalibration` and `ResetCalibration` write it).
- **Gap:** Calibration is only a Settings list item. There are no steps (scan six centers? reuse a Guided Scan?), no entry points (first scan, repeated uncertain stickers, stickerless or pastel cubes), no success or failure states, and no reset confirmation copy.
- **Land in:** EXPERIENCE IA (a Calibration sub-flow), State Patterns, and the Scan viewfinder rules ("Colors look off — calibrate?"). Add a short flow.
- **Severity:** high

## Medium

### M1. The step-card field "Keep" doesn't match the PRD and architecture name "preserve"
- **Input:** addendum §I (Goal / **Preserve** / Watch / Notice); AD-6 Lesson shape `steps[]: { goal, preserve, watch, notice }` and lesson-level `preserve: Predicate`; EXPERIENCE Foundation ("PRD Glossary terms are used verbatim"); architecture Conventions (Glossary terms verbatim in type names).
- **Gap:** DESIGN Step card and EXPERIENCE Voice and Tone use **Keep**, and EXPERIENCE says it follows addendum §I. That is a silent rename. Content keys (`…steps.n.preserve`) and the displayed label will drift.
- **Land in:** Either label the field **Preserve** in DESIGN Step card and EXPERIENCE Voice and Tone, or record an explicit decision: "UI label 'Keep' renders schema field `preserve`". Fix the claim "follows addendum §I verbatim" either way.
- **Severity:** medium

### M2. "Teach before revealing" is weakened in the Teaching Mode reveal rules
- **Input:** Principle 1; FR-5 (*Guide me* states the Objective before showing the Move; in *Test me* and *Let me try* the next Move is revealed only after a hint); AD-3 (reveal is decided by `TutorDecision.reveal`, never by a screen).
- **Gap:**
  - *Show me* auto-plays on entry, with no beat to state the Objective first.
  - In *Guide me*, nothing says the Move chip appears only after Goal/Preserve/Watch (e.g. a tap or a short delay).
  - *Let me try* hides the whole step card, which goes beyond FR-5 (only the next Move is gated) and drops Preserve/Watch.
  - EXPERIENCE hard-codes reveal behavior per mode in the UI instead of rendering `TutorDecision.reveal`.
- **Land in:** EXPERIENCE Component Patterns (switcher and step card): "The UI renders `reveal`; the Objective is always shown first; *Show me* starts after the Objective is shown or on tap."
- **Severity:** medium

### M3. *Test me* evaluation feedback is undefined
- **Input:** FR-5 (the tutor evaluates the Learner's Move); AD-6 (`ObjectiveProgress` is shared by *Test me*, Diagnosis and recovery).
- **Gap:** "waits for the Learner's Move and then evaluates it" gives no outcomes. What does a progressing Move that differs from the authored one show? A neutral Move? A harmful one?
- **Land in:** EXPERIENCE State Patterns (three *Test me* outcomes with copy).
- **Severity:** medium

### M4. FR-9 report options and the information-request ladder are incomplete
- **Input:** FR-9 ("my cube doesn't match" **or "I'm not sure what I did"**; request order Quick Check → which Move → one face → full re-entry); FR-7 (enter the Move actually made); AD-5.
- **Gap:**
  - "I'm not sure what I did" is missing.
  - *I did something else* has no input spec (Move key bar in an "enter what you did" state?).
  - Rungs 3 (one specific face to check) and 4 (full re-entry) have no UX.
  - Flow 1's no-camera path stops at rung 2.
- **Land in:** EXPERIENCE Component Patterns (Physical controls) and State Patterns (one row per rung of the mismatch ladder).
- **Severity:** medium

### M5. FR-26 Quick Check offer rules and AdoptObserved are dropped
- **Input:** FR-26 (offered at Stage milestones and after mismatches, never required, *Done* still works; *likely* → offers a **Guided 6-face Scan**; the Learner can adopt the observed state for Guided recovery); AD-5 (`Partial` must be completed to `Full` before `AdoptObserved`); AD-3 state machine.
- **Gap:** "Quick Check appears when offered" gives no trigger rules and no "never required" rule. "Mismatch, likely" offers a generic "Scan to confirm" instead of a Guided 6-face Scan. There is no "Use my cube as it is now" (AdoptObserved) action, and no step that completes a Partial first.
- **Land in:** EXPERIENCE Component Patterns (Physical controls) and State Patterns (Mismatch likely, and an AdoptObserved row).
- **Severity:** medium

### M6. Expected vs observed rendering during a mismatch is undefined
- **Input:** FR-7; AD-5 (`observed` is Full or Partial; a Partial is never completed from expected and never becomes a `CubeState`); AD-3 (`observed` goes stale on `PhysicalConfirmed`).
- **Gap:** The frame labels cover VIRTUAL, EXPECTED and VERIFIED, but nothing says what the stage shows when observed differs from expected. Is the observed face overlaid? Shown side by side? Partial faces must never be drawn as a full observed cube. A stale observation also needs a visual state.
- **Land in:** DESIGN Components (cube stage: an observed-faces overlay or a mismatch view; a rule that a Partial is never rendered as a whole cube). EXPERIENCE State Patterns.
- **Severity:** medium

### M7. FR-10 Error Categories have no per-category UX
- **Input:** FR-10 and §4.4 (seven categories); AD-5 `ErrorCategory`, especially `UNEXPECTED_ORIENTATION` (AD-2).
- **Gap:** Only "completed layer disturbed" and "wrong direction" have treatments. "Cube held in an unexpected orientation" is absent, though it is distinctive (re-orient guidance, not undo). So are wrong piece, wrong piece orientation, and invalid entered state inside a Lesson.
- **Land in:** EXPERIENCE State Patterns (a category → copy pattern → recovery-option table).
- **Severity:** medium

### M8. Guided recovery's pending state and "smallest correction" presentation are missing
- **Input:** Principle 4; FR-12 (shortest correction that restores the preserved pieces; never restarts the Lesson); AD-5 `recovery: Pending(effectId) | Ready(moves) | Unavailable`; AD-8 (`PlanRecovery` runs as an Effect with a 3 s timeout).
- **Gap:** The recovery prompt lists the options but not:
  - a Pending state ("Finding the shortest way back…");
  - an Unavailable state;
  - how the correction is shown (its length, the preserved pieces highlighted).
- **Land in:** EXPERIENCE Component Patterns (Recovery prompt) and State Patterns.
- **Severity:** medium

### M9. "Recent-solve resume" contradicts AD-10
- **Input:** AD-10 ("Only `LESSON`, `PRACTICE` and `TWO_LOOK` resume on cold start"; `PlaybackController` is never checkpointed per AD-20); AD-3 (the Editor and Scan review are not sessions).
- **Gap:** The IA Solve tab offers "a recent-solve resume". The Solver-timeout copy says "Your cube is saved", which no architecture rule guarantees.
- **Land in:** EXPERIENCE IA (drop the resume, or limit it to the in-process session) and State Patterns (reword the timeout copy), or raise it as an architecture change.
- **Severity:** medium

### M10. Flag and cut-order UX is incomplete
- **Input:** AD-18 enum `{QUICK_SCAN, PLACEMENT, CASE_RECOGNITION, TEST_ME, CAMERA_SCAN, ADAPTIVE_ASSISTANCE}`; PRD §6.2 spike-failure decision (all camera features BETA; the Manual Cube Editor becomes the primary input).
- **Gap:**
  - "Feature flag BETA" lists only Quick Scan, Recognize and Placement. It leaves out `CAMERA_SCAN` (Guided Scan, Quick Check, scan to solve).
  - Nothing says *Enter colors* becomes the primary Solve action under spike failure.
  - `ADAPTIVE_ASSISTANCE` OFF is not covered.
  - Cross-links to features that may be OFF are not handled: "Recognize: not at LL → Start from my cube" when `PLACEMENT` is OFF; Welcome *Start from my cube*.
  - The beta tooltip copy ("may misread colors") doesn't fit `TEST_ME` if it ever goes BETA.
- **Land in:** EXPERIENCE State Patterns (a flag × surface table, including spike-failure ordering).
- **Severity:** medium

### M11. FR-1's Cube Rotation gesture is missing, and View vs Cube Rotation is not distinguished
- **Input:** FR-1 ("Gestures cover face turns **and Cube Rotations**"); Glossary (a Cube Rotation changes the frame; a View Rotation never changes state); AD-4; FR-2 assumption (Notation follows the cube, not the camera).
- **Gap:** The gesture map gives off-cube and two-finger drags to View Rotation, and Cube Rotations are button-only. The difference between View Rotation (no state) and Cube Rotation (changes the frame and the move labels' meaning) has no feedback. It's also not stated that Move key labels never change with the view.
- **Land in:** EXPERIENCE Interaction Primitives.
- **Severity:** medium

### M12. FR-6 direction arrows and piece highlighting have no visual spec
- **Input:** FR-6 ("highlights the affected pieces with direction arrows"); Glossary *Demonstration* ("highlighted pieces and arrows").
- **Gap:** DESIGN covers highlight and dim but has no arrow component (style, token, reduce-motion behavior).
- **Land in:** DESIGN Components (a Move arrow overlay).
- **Severity:** medium

### M13. NFR-7 WCAG AA contrast is deferred to DESIGN.md, which never states or checks it
- **Input:** NFR-7 (WCAG AA contrast, content labels on every control).
- **Gap:**
  - EXPERIENCE says "visual contrast is in DESIGN.md", but DESIGN.md has no contrast rule or checked pairs.
  - `sticker-highlight` #5B3FD1 on `cubie-body` #121417 is about 2.5:1, below the 3:1 non-text minimum, and it is theme-invariant.
  - The scan grid uses "`on-accent` white", but `on-accent-dark` is #160F33.
  - No rule says every control has a content label (only Move keys and the cube are covered).
- **Land in:** DESIGN Colors (a contrast table; a lighter highlight or an outline for the dark cube; fix the scan-grid token). EXPERIENCE Accessibility Floor (a content-label rule).
- **Severity:** medium

### M14. Surface Coverage contradicts itself and leaves surfaces without a flow
- **Input:** the requirement that every IA surface is reached by a flow; UJ-1 edge cases; UJ-3 edge case (no camera → Editor).
- **Gap:**
  - The section says four surfaces have no Key Flow, then claims Flow 1 step 8 and Flow 2 step 4 reach them.
  - There is no flow for Independent practice through to mastery, You (trend), Settings (separate resets, calibration), or the Manual Cube Editor as a primary path (no camera; spike failure).
  - The Welcome "I can already solve it" branch has no flow.
  - The UJ-1 mid-Lesson resume edge case appears only in the state table.
- **Land in:** EXPERIENCE Key Flows (add short flows 7–9: practice → mastery and trend; skip-ahead; Settings resets and calibration). Rewrite Surface Coverage.
- **Severity:** medium

### M15. FR-17 2-look *completed* vs *mastered* is not shown
- **Input:** FR-17 (completed = every predicate met; mastered = 2 hint-free LL solves on fresh Scrambles using only 2-look Algorithms).
- **Gap:** Flow 2 shows one hint-free solve as the climax, with no mastery state or progress toward it. The *Faster last layer* row has no states.
- **Land in:** EXPERIENCE IA (Learn) and Flow 2. DESIGN Stage row (reuse it for the mini-Course).
- **Severity:** medium

### M16. The Quick Scan capture UI is not designed
- **Input:** FR-22 and §4.8 (hold by a corner; 2–3 captures of three faces each; fall back to Guided Scan for missing faces only).
- **Gap:** The DESIGN Scan viewfinder specifies only the face-on 3×3 grid. There is no corner-view overlay, capture-count progress, or hand-off to the per-face Guided Scan.
- **Land in:** DESIGN Components (a Quick Scan viewfinder variant). EXPERIENCE Component Patterns.
- **Severity:** medium

### M17. FR-27 Placement choices and edge outcomes are missing
- **Input:** FR-27 ("The Learner can accept it **or pick a different Stage**"; white cross first only); AD-10 `StageSkippedByPlacement`.
- **Gap:**
  - Flow 4 shows only acceptance.
  - There is no "choose another Stage" control.
  - No copy for:
    - a solved cube;
    - a cube at the last layer (offer 2-look?);
    - a non-white cross (MVP assumption);
    - nothing solved (suggest Stage 1).
- **Land in:** EXPERIENCE State Patterns (Placement outcomes) and Flow 4.
- **Severity:** medium

## Low

- **L1 — Glossary drift.** "Guide me back" is used without the Glossary term *Guided recovery*. "Skipped" collapses *skipped* (check) and *skipped by placement* (AD-10 logs both). "Physical mode" is used as shorthand for *Physical-cube Mode*. The PRD's *Solve my cube* appears as "Solve tab". **Land in:** EXPERIENCE Voice and Tone (a label → Glossary-term map). DESIGN Stage row (optionally a subtitle "by placement").
- **L2 — Hint rung 2.** FR-11 and the Glossary say "a highlighted target **or Move**". The UX says target only. **Land in:** EXPERIENCE Hint button.
- **L3 — Scan review.** FR-21 says "one-tap correction"; the UX needs a tap on the sticker, then a tap on the color. FR-23 also allows review on the 3D cube. **Land in:** EXPERIENCE Sticker review net.
- **L4 — Permission denied.** FR-25 says permission denied "opens the Manual Cube Editor". The UX shows a card plus a button. **Land in:** EXPERIENCE State Patterns (or note this as a deliberate deviation).
- **L5 — Back dialog.** "Leave lesson?" shows only mid-animation. Under AD-4 commit-before-animate, the Move is already committed, so the dialog has no purpose. **Land in:** EXPERIENCE Responsive & Platform → Back.
- **L6 — Animation queue.** AD-4 fast-forwards only when more than 3 items are queued. The UX says the queue "speeds up to catch up" with no threshold. **Land in:** EXPERIENCE Interaction Primitives.
- **L7 — Expected-only certainty.** "Expected only → no certainty chip" sits uneasily with Principle 3 (no evidence → *unknown*) and AD-5 (every `TutorDecision` carries a Certainty). Decide whether to show an explicit ? *Unknown* or record the omission as a decision. **Land in:** EXPERIENCE State Patterns.
- **L8 — Non-local validation errors.** FR-14 includes errors that can't be pinned to stickers (parity, twisted corner, flipped edge). They need copy patterns; the UX gives only "this corner has two whites". **Land in:** EXPERIENCE State Patterns → Invalid entered state.
- **L9 — One active session.** AD-3 allows one session at a time; starting another kind suspends the current one. There is no UX for "Starting a solve pauses your lesson" or for what resuming a suspended physical session shows. **Land in:** EXPERIENCE State Patterns.
- **L10 — Playback component.** The DESIGN Playback transport omits the MoveNote line. FR-16 makes a Quick Check available at any step of physical playback, and Flow 3 doesn't show it. **Land in:** DESIGN Components and EXPERIENCE Flow 3.
- **L11 — Flow 5 wording.** Flow 5's failure path says the Learner is "sent to Placement". The state table and FR-28 say Placement is *offered*. **Land in:** EXPERIENCE Flow 5.
- **L12 — Color-blind patterns.** AD-17 `StickerAppearance` has `{fill, glyph, pattern}` and NFR-7 says "letters or patterns". The UX specifies glyphs only. **Land in:** DESIGN Components (note that patterns are deferred, or specify them).
- **L13 — Mode switch back to virtual.** Switching to virtual sets `virtual := expected`, and `ModeSwitched` is an undo barrier that clears redo. Neither is communicated. **Land in:** EXPERIENCE State Patterns.
- **L14 — Cut-order item 4.** Cutting Library recognition diagrams has no fallback tile design, and AD-18 has no flag for it. **Land in:** DESIGN Algorithm tile (raise the missing flag with architecture).
- **L15 — Milestone trigger.** The "Stage mastered" milestone is placed at "Lesson end", but mastery is reached through the Mastery Condition, usually in Independent practice. **Land in:** EXPERIENCE State Patterns.
- **L16 — Algorithm Status values.** The Algorithm tile shows *Learning* / *Learned* only. *Not started* (Glossary) is not shown, and the Learned count must not be framed as an achievement (SM-C3). **Land in:** DESIGN Algorithm tile.

## Checked and consistent (no action)

- Certainty wording and the categorical-only rule (AD-5).
- MoveNote template and the no-purpose rule (AD-20, FR-16).
- The EXPECTED badge and frame labels (FR-7).
- The stale-scan discard and re-offer (AD-3).
- Checkpoint content-revision fallback (AD-10).
- Resume into Confirm-your-cube (FR-19, AD-10).
- FR-8 switch-to-physical confirm with match / scan / enter.
- FR-9 ordering referenced in State Patterns.
- FR-11 reset on a new Objective.
- Hint counter.
- FR-12 disturbed-layer treatment, with the Checkpoint kept.
- FR-17 / FR-28 statuses offered, never set automatically.
- FR-19 progress reset leaves calibration and preferences untouched.
- FR-21 auto-capture, manual shutter and orientation re-prompt.
- FR-22 fallback to per-face Guided Scan (Flow 3).
- FR-28 ambiguous and not-at-LL handling.
- AD-4 gesture confidence threshold.
- AD-17 single glyph table.
- TalkBack description from `CubeState`.
- All six user journeys (UJ-1 to UJ-6) have a Key Flow.
