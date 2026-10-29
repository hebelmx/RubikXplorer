---
type: reconcile-review
subject: ARCHITECTURE-SPINE.md (RubikXplorer, 2026-10-29)
inputs:
  - prds/prd-RubikXplorer-2026-10-29/prd.md
  - prds/prd-RubikXplorer-2026-10-29/addendum.md
  - architecture-RubikXplorer-2026-10-29/.memlog.md
created: '2026-10-29'
---

# Input reconciliation: PRD + addendum → Architecture Spine

Scope filter: a finding is listed only if two independently built units could diverge, or a hard requirement has no architectural home. UX-only details (contrast, copy tone) are excluded.

**Counts:** high 5 · medium 8 · low 7 (20 total)

---

## High

### R-1 Partial observations have no type; Quick Check and Case recognition contradict AD-9
- **Input:** FR-26 (Quick Check of "only the faces the latest Moves affected", ≤ 2 captures); FR-28 (one corner capture: U + two side faces); FR-22 / §4.8 (Quick Scan falls back to Guided Scan "for the missing or uncertain faces only").
- **Spine:** AD-9 says "every consumer obtains a `CubeState` only via `CubeValidator`", and `ScanResult` is 54 `Facelets`. AD-5 `observed: PhysicalObservation?` implies a full state. A partial scan cannot pass full-state validation, so Quick Check and Case recognition either cannot be built or have to bypass AD-9. Each feature team would invent its own partial type. Quick Scan + Guided fallback also needs a face-level merge of captures from two modes, which is not defined.
- **Landing:** new AD (or tighten AD-5/AD-9). Define `PartialObservation` (a set of observed faces → facelets + `StickerConfidence`) next to the full `ScanResult`. Define the rule: a partial observation is never turned into a `CubeState`. It is only *matched* against candidate states (Quick Check) or Case patterns (FR-28). Define `ScanAssembler` as the single place that merges face captures across modes into a full `ScanResult`.
- **Severity:** high

### R-2 Candidate set behind verified / likely / unknown is undefined
- **Input:** FR-26 ("*verified* only when the observed faces fit exactly one candidate Move sequence from the Expected State… if several fit, *likely*"); FR-10 (several causes → *likely* or *unknown*); Principle 3; addendum §J Gate 2 ("Quick Check never yields a false *verified*").
- **Spine:** AD-5 requires "exactly one candidate", but does not say what the candidate space is (which Moves, what depth, Cube Rotations or not), who enumerates it, or when the result is *likely* rather than *unknown*. Diagnosis, Quick Check and FR-28 could each enumerate differently. A shallower search in one of them would report a false *verified*. That is the one claim Principle 3 forbids.
- **Landing:** tighten AD-5. Add a single pure `CandidateGenerator` in `:core:domain/tutor`, configured from content (for example, single wrong quarter / half / direction Moves and Cube Rotations up to depth N since the last verified state). Add the rule: *verified* means exactly 1 match within the declared space, *likely* means 2–k matches, *unknown* means 0 matches or more than k. The declared space is part of the decision, so the claim can only be as strong as the search was complete.
- **Severity:** high

### R-3 Expected / Virtual / Physical sync has no state machine
- **Input:** addendum §A ("state diagrams: tutor flow, and sync between the Expected, Virtual and Physical states"); §C rules 1–6; FR-8 (mode switch asks the Learner to confirm, scan or enter); FR-26 ("adopt the observed state as the new starting point"); FR-3 undo; FR-16 physical playback.
- **Spine:** AD-5 names the three fields and AD-3 lists `ModeSwitched`, but no transition table exists. Several behaviours are undefined:
  - what `virtual` becomes on physical → virtual;
  - whether `Undo` in physical mode rewinds `expected`, and what it asks the Learner to do;
  - when `observed` goes stale (after the next confirmed Move? on restore?);
  - what "adopt observed" overwrites.

  The reducer author and the UI author will each fill these gaps their own way, which is the NFR-4 drift risk.
- **Landing:** new AD with a state diagram: a session sub-state `{VirtualAuthoritative, PhysicalPredicted, PhysicalAwaitingConfirm, PhysicalVerified}`, the events allowed in each, and the rule that any committed Move invalidates `observed`, which drops Certainty back to the expected-only level.
- **Severity:** high

### R-4 "Checkpoint" is conflated with the per-commit snapshot; resume semantics are missing
- **Input:** Glossary (Checkpoint = saved Cube State + Lesson position); FR-12 ("keeps the earlier Checkpoint" when a Move breaks the cross); FR-19 (forced restart restores the latest committed Checkpoint, and a Physical-mode Checkpoint asks to confirm, scan or re-enter); UJ-1 edge case; Gate 3 (undo, pause, restart, resume).
- **Spine:** AD-10 writes one snapshot of the whole `SessionState` on every transition, and the ERD has one `CHECKPOINT` per profile. That cannot both *keep an earlier Checkpoint* (FR-12) and record the latest commit. Also undefined:
  - Restore in Physical-cube Mode must not resume as if the cube were known. The restored state must enter the awaiting-confirmation state and drop any `observed` and *verified* Certainty.
  - In-flight effects (a pending solve, a scan, the animation queue) and the undo/redo stacks have no restore rule.
  - A checkpoint whose Lesson ID or `schemaVersion` no longer matches the shipped content has no fallback.
- **Landing:** tighten AD-10. Split `SessionSnapshot` (per commit, for process death) from `Checkpoint` (a semantic resume point the tutor emits at Objective boundaries, and kept across a disturbance). Add a restore rule: physical → `PhysicalAwaitingConfirm`, effects are never resumed, and the undo stack either persists or is explicitly cleared. Add a content-mismatch rule: fall back to the start of the Lesson at the same Stage.
- **Severity:** high

### R-5 The Lesson-definition schema, including the voice fields, is not specified
- **Input:** addendum §A ("must produce… the Lesson-definition schema, including the explanation-voice fields (§I)"); §I (Goal, Preserve, Watch, Notice: "each has a field in the Lesson schema"); FR-4 (required fields: prerequisites, Objective, Demonstration, learner action, Completion Predicate, Recovery Path; a schema test fails the build if any is missing); FR-6.
- **Spine:** AD-6 says content is versioned JSON with predicate IDs but gives no Lesson shape and no required-field list. The content author, the parser, the tutor and the Demonstration player will disagree on structure. Also missed: *Preserve* is more than text. FR-12 ("restores the current Objective's preserved pieces") and the "completed layer disturbed" Error Category both need it as a **predicate reference**, not just a string key.
- **Landing:** tighten AD-6 with a minimal schema skeleton:
  - `Lesson { id, prerequisites[], objective: TextKey, entryState, demonstration: MoveSeqRef, learnerAction, completion: PredicateRef, preserve: PredicateRef, recovery: RecoveryRef, steps[]: { goal, preserve, watch, notice: TextKey, moves } }`;
  - all fields required by the schema test.
- **Severity:** high

---

## Medium

### R-6 Error Category enum, Recovery Path map and tutor flow are not fixed
- **Input:** PRD §4.4 (7 named Error Categories, including *unknown*); FR-10 (each maps to a rule or *unknown*, each has a Recovery Path); FR-9 (fixed order for information requests: Quick Check → which Move → one face → full re-entry); addendum §A ("tutor decision rules and the Error Category → Recovery Path map"), §D (tutor flow).
- **Spine:** only the `tutor/` package and `TutorDecision` are named. It does not say whether `ErrorCategory` is a closed enum, or whether the Error Category → Recovery Path map is content data (AD-6) or code. The FR-9 ladder could be ordered differently by tutor code and by UI.
- **Landing:** Convention plus a tightened AD-6: `ErrorCategory` is a closed enum in `:core:domain` that mirrors the PRD list. The Recovery Path map and the FR-9 clarification order are content data, with a schema test that every enum value is mapped. Add the tutor-flow state diagram from §D (see R-3).
- **Severity:** medium

### R-7 Teaching Modes and the measure of "helping vs harmful" Moves have no home
- **Input:** FR-5 (four modes evaluate the same predicate; in *Test me* / *Let me try* the next Move is revealed only after a hint); addendum §F P0.1 exit ("one Completion Predicate tells a helping Move from a harmful one"); FR-12 ("shortest correction"); FR-19 (last Teaching Mode persisted); the cut order removes *Test me* first.
- **Spine:** `TeachingMode` is not placed in `SessionState`, and the reveal gating has no owner (tutor rules or UI). A boolean Completion Predicate cannot rank a Move as helping or harming. *Test me* evaluation, the P0.1 proof of concept and Guided recovery all need a progress or distance measure, and the spine leaves it to the deferred `RecoveryPlanner`.
- **Landing:** tighten AD-3/AD-6:
  - `teachingMode` is a field of `SessionState`;
  - predicate evaluation never depends on the mode;
  - the reveal policy lives in tutor rules (`TutorDecision.reveal`), not in screens;
  - add a pure `ObjectiveProgress` measure per Objective (for example, the count of satisfied sub-predicates, plus whether the preserved pieces are intact), shared by *Test me*, Diagnosis and the RecoveryPlanner.
- **Severity:** medium

### R-8 Color-blind display has no rendering contract
- **Input:** NFR-7 (optional letters or patterns on stickers, P0 by assumption); FR-19 (persisted preference).
- **Spine:** NFR-7 appears in `binds` but has no AD. AD-4 limits the renderer's input to `CubeState` + `AnimationQueue`, which leaves no channel for display options. Stickers are also drawn by the Manual Cube Editor, the scan-correction net, recognition diagrams and the mini cube net. Each surface will invent its own letter or pattern mapping.
- **Landing:** tighten AD-4 so the renderer also receives a `RenderStyle` (color-blind mode, speed). Convention: a single `StickerAppearance(Color) → {fill, glyph, pattern}` table in one place, used by every sticker-drawing surface.
- **Severity:** medium

### R-9 Non-gesture alternatives and gesture-ambiguity rules are not pinned
- **Input:** §4.1 (buttons for every Move type; "an ambiguous gesture never changes the Cube State"); FR-1 (input during an animation is "queued or rejected deterministically"); NFR-7 (a non-gesture alternative for every essential action; content labels on every control).
- **Spine:** AD-4 says the renderer emits `MoveIntent`, but does not require that buttons and gestures produce the identical `MoveIntent` and go through the same path. It does not choose between queueing and rejecting, or set a queue bound. It has no accessible representation of the cube for screen readers (a canvas has no semantics).
- **Landing:** tighten AD-4:
  - every input source (gesture, button, accessibility action) produces `MoveIntent`, and the ViewModel maps it to the same `MoveCommitted`;
  - a gesture below the confidence threshold produces no intent;
  - the in-animation policy is a fixed queue with bound N (or reject), applied the same way everywhere;
  - the renderer exposes a semantics description derived from `CubeState`.
- **Severity:** medium

### R-10 Beta labeling, the spike-failure fallback and cut-order toggles have no mechanism
- **Input:** FR-22 (Quick Scan is labeled "beta" unless it meets its targets); §6.2 NOTE (if the spike misses: the Manual Cube Editor becomes the primary input and every camera feature ships labeled beta); the cut order (Quick Scan beta, FR-28, FR-27, diagrams, *Test me*, FR-13 reduced); release gate 6.
- **Spine:** this is not mentioned. The scan screens, Home, Solve-my-cube entry, the Placement entry and the Lesson mode picker would each hard-code their own flags.
- **Landing:** Convention (or a small AD): a single `FeatureMaturity` map `{GA, BETA, OFF}` per feature (QuickScan, GuidedScan, QuickCheck, Placement, CaseRecognition, TestMeMode, AdaptiveAssist), set at build time and read by the UI and entry routing. "Primary input = Manual Editor" is a value of that map, not a code fork.
- **Severity:** medium

### R-11 Recognition test-matrix fixtures: format, coverage of the camera adapter, and how they are captured
- **Input:** OQ-8 (cubes × lighting × devices, and how ground truth is recorded); §4.8 NFR table (per-sticker and per-scan accuracy, at most 0.1% confidently wrong); addendum §J; FR-21 (wrong-orientation detection).
- **Spine:** AD-14 runs "recognition fixtures" on the JVM, but does not define the fixture format. The memlog says FaceSample JSON, which leaves grid detection, perspective unwarp and quality flags in `:app/camera` with no test at all. AD-9 says "frames are never persisted", which blocks building the image corpus the matrix needs. No component computes or reports the confidently-wrong rate.
- **Landing:** tighten AD-9/AD-14:
  - two fixture levels: (a) `FaceSample` JSON + ground-truth `Facelets` for classifier and assembler tests on the JVM; (b) a frame or image corpus for adapter tests, as instrumentation or a JVM image harness;
  - a debug-only capture tool, excluded from release, as the stated exception to "never persisted";
  - a fixed ground-truth schema `{cube, lighting, device, truth: Facelets}`;
  - a report task that outputs the §4.8 metrics.
- **Severity:** medium

### R-12 Reference devices and performance budgets are not fixed
- **Input:** §5 ("The final set [of reference devices] is fixed during architecture"); NFR-1 (100 ms), NFR-2 (30/60 FPS), NFR-3 (95% of states within 3 s; median, p95, memory and failure rate reported); FR-1 (250 ms per quarter turn).
- **Spine:** no reference devices are named. AD-8 has "a timeout" with no value. There is no benchmark or measurement harness, so the Solver team and the UI team cannot agree on a budget.
- **Landing:** Convention or Deferred with an owner: name the minimum and mid-range reference devices, set the Solver timeout (for example 3 s, from NFR-3), and add a JVM Solver benchmark task (corpus median, p95, memory) to AD-14, plus a Macrobenchmark for FPS on the release branch.
- **Severity:** medium

### R-13 No test oracle for "rendered == logical"
- **Input:** FR-1 ("the rendered cube and the Virtual State are identical, checked sticker by sticker in tests"); NFR-4 (zero mismatches across the randomized Move, undo and interruption suite); gate 3.
- **Spine:** AD-4 makes the renderer a projection, but gives it no way to report what it actually drew. The renderer implementer and the test author would each invent a probe, or the test could only compare state with state, which proves nothing.
- **Landing:** tighten AD-4: `CubeRenderer` exposes `renderedFacelets(): Facelets` after the animation settles (a test hook), and the NFR-4 suite compares it with `CubeState.toFacelets()`.
- **Severity:** medium

---

## Low

### R-14 No size gate for NFR-11
- **Input:** NFR-11 (< 30 MB including Solver tables and any recognition model); OQ-4, OQ-7.
- **Spine:** only Deferred notes. It does not say whether min2phase pruning tables are bundled or generated at first run (a size versus cold-start trade-off).
- **Landing:** add an APK/AAB size check to AD-14 and record the bundled-vs-generated choice for the tables under the OQ-4 deferral.
- **Severity:** low

### R-15 The "observation port" is not named
- **Input:** addendum §A ("keep recognition behind an **observation port** so manual input, scan and future sources all feed the same validation").
- **Spine:** the intent is mostly met by AD-5 and AD-9, but no port is named. It is not stated that the Manual Cube Editor produces the same `ScanResult`-shaped value (all stickers `CONFIDENT`) so it reuses the correction and validation path, and the use case that drives camera → classifier has no port interface.
- **Landing:** tighten AD-9: define a `CubeObservationSource` port (manual, guided, quick, partial) whose outputs all flow through `ScanAssembler` → `CubeValidator`.
- **Severity:** low

### R-16 Separate-seams advice: gestures are bundled into the renderer port
- **Input:** addendum §A ("rendering, gestures, animation timing, camera recognition and cube-state correctness as separate problems behind separate seams… start with a button-driven Compose screen and a simple 2D net"); §F P0.1 proof of concept.
- **Spine:** `CubeRenderer` both draws and interprets gestures. There is no 2D-net adapter, although it could also serve the editor and scan correction.
- **Landing:** Convention: a pure `GestureInterpreter` (touch path + view pose → `MoveIntent?`) that can be tested on the JVM, separate from drawing; a `Net2DRenderer` as the first `CubeRenderer` adapter for P0.1, before the OQ-3 outcome.
- **Severity:** low

### R-17 Localization: parameterized domain messages
- **Input:** NFR-10; FR-14 (plain-language explanation of the failed rule); FR-23 ("this corner has two whites"); FR-16 note template; FR-23 scan guidance.
- **Spine:** AD-6 uses string keys and separates `content/strings` from `strings.xml`, but it does not say how domain results carry arguments (`MessageKey + args`), which bundle validation and scan messages belong to, or the placeholder syntax.
- **Landing:** Convention: domain returns `Text(key, args)`, with a single placeholder syntax; domain-originated messages live in the content bundle, and only shell chrome lives in `strings.xml`.
- **Severity:** low

### R-18 "Reset all progress" vs insert-only rows
- **Input:** FR-19 (reset all progress and Algorithm Statuses; separate resets for calibration and preferences).
- **Spine:** AD-10 makes `Attempt` and `LearningEvent` rows insert-only. A whole-table wipe is not stated as allowed, and the three separate reset scopes are not mapped to repositories.
- **Landing:** tighten AD-10: rows are never *updated*. The only deletion is a scoped wipe (progress, calibration, preferences) through the repository port.
- **Severity:** low

### R-19 Solution Playback notes must be generated, not authored (Principle 3)
- **Input:** FR-16 (fixed template of face, direction and piece group; never a strategic purpose for Solver output).
- **Spine:** no rule that notes are a pure function of the `Move`. A content or UI author could attach free text.
- **Landing:** Convention: `MoveNote.of(move): Text` in `:core:domain`, and playback text comes only from it.
- **Severity:** low

### R-20 Acceptance precondition for a Scan Result
- **Input:** FR-21 ("uncertain stickers are highlighted for one-tap correction before the result can be accepted"); FR-23.
- **Spine:** `ScanAccepted` has no precondition. It does not say whether any `UNCERTAIN` sticker blocks acceptance until the Learner reviews it, and whether that rule is enforced in the reducer or only in the UI.
- **Landing:** tighten AD-9: the reducer rejects `ScanAccepted` unless the result is valid and every `UNCERTAIN` sticker has been corrected or confirmed by the Learner.
- **Severity:** low

---

## Minor drift noted
- The memlog names the use case `RecoveryPlanner`, while the spine uses `PlanRecovery` (AD-8) and "`RecoveryPlanner` search strategy" (Deferred). Pick one name.
- The spine's `binds` lists NFR-7, NFR-9, NFR-11 and NFR-13. NFR-9 is covered (Android vitals) and NFR-13 has no AD, which is acceptable. NFR-7 and NFR-11 have no landing beyond the frontmatter (see R-8, R-9, R-14).
