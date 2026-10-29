---
title: Adversarial review — Architecture Spine (RubikXplorer)
reviewed: ../ARCHITECTURE-SPINE.md
against: ../../../prds/prd-RubikXplorer-2026-10-29/prd.md (FR-1..FR-28), addendum.md
lens: "Construct two epics that each obey every AD to the letter yet still build incompatibly."
date: 2026-10-29
---

# Adversarial review — two compliant units, one broken build

**Method.** For each hole, two epics one level below the spine (interactive cube + renderer, lesson framework/content, physical-cube mode, tutor diagnosis/recovery, solver + playback, 2-look + Library, progress persistence, scan capture, scan uses) are each built so that they comply with every AD as written. The spine is the only shared contract, so wherever the two still conflict, the spine has a hole. Each entry proposes AD text to close it.

**Counts:** Critical 5 · High 9 · Medium 6 · Low 2 (22 total)

| # | Sev | Hole | ADs to amend |
|---|---|---|---|
| C1 | Critical | Cube Rotations / wide / slice turns cannot be represented under "centers fix the frame" | AD-2, AD-7 |
| C2 | Critical | No home orientation, Facelet value type or per-face sticker index orientation | AD-2, AD-9 |
| C3 | Critical | "One event loop per session" without defining what a session is, plus one Checkpoint per profile | AD-3, AD-10 |
| C4 | Critical | Effect results have no correlation, so stale scan or solve results land after Undo | AD-3, AD-5, AD-8 |
| C5 | Critical | A Quick Check is partial, but ScanResult/CubeValidator only handle 54 stickers | AD-5, AD-9 |
| H1 | High | Undo/redo across `PhysicalConfirmed`, `ScanAccepted` and `ModeSwitched`; which field each event mutates | AD-3, AD-5 |
| H2 | High | Checkpoint blob format vs content `schemaVersion` / content revisions | AD-10, AD-6 |
| H3 | High | Concurrent `PersistCheckpoint` effects can reorder; attempt log and checkpoint not atomic | AD-10, AD-3 |
| H4 | High | Two owners of Color Calibration | AD-9, AD-10 |
| H5 | High | Two owners of Algorithm Status, and Case-vs-Algorithm keying | AD-10, AD-6 |
| H6 | High | Stage status (skipped / skipped by placement / reset) under insert-only rows | AD-10 |
| H7 | High | Predicate parameter shape, orientation anchoring, preserved-piece sets | AD-6 |
| H8 | High | LL Case pattern and AUF canonicalization differ between Library, test and recognition | AD-6 |
| H9 | High | Certainty has many producers; candidate enumeration is unbounded and unshared | AD-5 |
| M1 | Medium | Clock/seed use inside a pure reducer; scramble persistence | AD-11 |
| M2 | Medium | AnimationQueue vs non-Move state jumps; input during animation | AD-4 |
| M3 | Medium | String-key namespace, placeholders, and the domain-message vs `strings.xml` split | AD-6 |
| M4 | Medium | Attempt boundary, who records it, and the meaning of "reduced assistance" | AD-10 |
| M5 | Medium | Scan sub-flow return contract for session and non-session callers | AD-9, AD-3 |
| M6 | Medium | Recovery planning inline in the reducer vs as an effect | AD-3, AD-8 |
| L1 | Low | Move equality/canonical form (`R2` vs `R2'`, `r` vs `Rw`, slice direction) | AD-7 |
| L2 | Low | "Last Teaching Mode" preference has two writers | AD-10 |

---

## Critical

### C1 — Cube Rotations, wide turns and slice turns vs "centers fix the frame"

- **Unit A (interactive cube, E01/E03):** implements `x`, `y`, `z`, `M`, `E`, `S` and wide turns by re-expressing the cubies in the new frame (e.g. `M` = `L' R` with an implicit `x'` folded in), so `CubeState` stays centers-relative and has no orientation field. AD-2 says "Centers fix the frame", so this complies.
- **Unit B (lesson framework/physical mode, E04/E06):** a *Guide me* step says `y` ("turn the cube so the red center faces you"), followed by `R U R'`. B reads `R` as "the face physically on the right *now*" (FR-2: "Notation follows the cube's own orientation") and keeps a separate `heldOrientation` in `SessionState`. AD-2 does not forbid this field.
- **Break:**
  - After `y R`, A turns the centers-relative R face, while B turns the face that is physically on the right after the `y`. These are different faces, so the Expected State diverges from the Learner's cube.
  - Predicates, the renderer and Quick Check comparison disagree about which face is "U".
  - Without an orientation in the state, FR-10's Error Category "cube held in an unexpected orientation" cannot be expressed at all.
  - The `isSolved` check after `x` differs between the two units.
- **Proposed AD (amend AD-2):**
  > `CubeState = (cubies, orientation)`. `cubies` are expressed relative to the centers. `orientation: Orientation` is one of the 24 whole-cube rotations and maps spatial faces (U R F D L B as seen by the Learner) to center identities. Applying a Move interprets its face spatially through `orientation`. Cube Rotations change only `orientation`. Slice and wide turns are applied as their face-turn equivalent plus an orientation change. `isSolved` and every predicate ignore `orientation`. Equality for sync checks compares `cubies` and `orientation` separately: an orientation-only difference is the Error Category *cube held in an unexpected orientation*. The renderer draws through `orientation`. `CubeSolver` receives `cubies` only and returns face Moves in center space, which `SolveCube` maps back through `orientation`.

### C2 — No home orientation, no Facelet value type, no sticker index orientation per face

- **Unit A (scan capture, E10a):** follows FR-21's prompt "white center facing you, green on top". It captures each face in screen reading order (top-left to bottom-right as seen in the viewfinder) and emits `Facelets` of `Color` in URFDLB *face* order, with the stickers in viewfinder order. This complies with AD-2 (URFDLB order) and AD-9 (FaceSample → ScanResult).
- **Unit B (solver interop / Manual Cube Editor, E02):** uses the Kociemba facelet diagram (U viewed from above with B at the top, D viewed from below with F at the top, and so on). Its `Facelets` hold `Face` letters, as the Kociemba string does, mapped from colors on the assumption that **white is on U and green is on F**. Content authors write Lesson entry states as scramble strings applied from that solved state.
- **Break:**
  - Scanned faces arrive rotated 90° or 180° relative to B's indexing. Most results fail validation, so FR-23's "names the likely bad stickers" message points at the wrong stickers.
  - Some results validate but describe the wrong cube; a uniform face is one example.
  - The scan's frame puts white on F with green on U, while B's solved state puts white on U. A scanned *solved* cube therefore compares as "not solved" against Expected, and Lessons authored "white cross on D" have no fixed meaning.
  - AD-2 names the face order only, not the value type, the per-face index orientation or the home color mapping.
- **Proposed AD (amend AD-2, AD-9):**
  > **Home orientation:** in the solved home state, WHITE is on U and GREEN is on F (so RED is on R). All content entry states, scrambles and fixtures are expressed from home.
  >
  > **Facelets:** `Facelets` is `List<Color>` of length 54 in URFDLB face order. Within each face, indices 1–9 run row-major as shown in the Kociemba net diagram (U: B edge at the top; D: F edge at the top; F, R, B, L: U edge at the top). This orientation is checked into `:core:domain` as a fixture with a diagram.
  >
  > **Scan assembly:** `FaceSample` carries `centerColor`, `upNeighborColor` and the 9 samples in viewfinder order. Only `ScanAssembler` (in `:core:domain`) rotates them into Facelets order. The camera adapter never reorders stickers.
  >
  > **Kociemba interop:** a conversion to the Kociemba face-letter string exists only inside `:core:solver`.

### C3 — "One event loop per session" without defining a session; one Checkpoint per profile

- **Unit A (solver + playback, E02/E08):** FR-16 is bound by AD-3, so *Solve my cube* creates its own `LearningSession` for playback. Per AD-10 it emits `PersistCheckpoint` on every committed transition.
- **Unit B (lesson framework, E04):** `LessonViewModel` creates a `LearningSession` per Lesson screen. The ERD gives `LEARNER_PROFILE ||--o| CHECKPOINT` (zero or one per profile).
- **Unit C (Library, E11):** FR-18 is not bound by AD-3, so the Library keeps its Demonstration cube state in its own ViewModel and builds a second Demonstration player with its own step/back semantics.
- **Break:**
  - When the Learner leaves a Lesson to solve a sibling's cube, A's checkpoints overwrite B's single Checkpoint, and the Lesson resume (FR-19, UJ-1 step 71) is lost.
  - If `ui/lesson` and `ui/physical` each hold a `@HiltViewModel`-scoped session, FR-8's mid-Lesson mode switch produces two event loops over the same Checkpoint.
  - FR-6 Demonstrations (session-driven) and FR-18 Demonstrations (ViewModel-local) diverge in their controls and in how they reach the renderer.
  - Placement (FR-27) is bound to AD-3 but has no Lesson to attach a session to.
- **Proposed AD (amend AD-3, AD-10):**
  > A `LearningSession` is one of `SessionKind {LESSON, PRACTICE, TWO_LOOK, SOLVE_PLAYBACK}`. At most one session is active app-wide. It is held by an `@Singleton` `SessionHost` in `:app`, never by a ViewModel. Starting a session of another kind first suspends the active one. `CheckpointRepository` keys Checkpoints by `SessionKind`: one resumable Checkpoint per kind, and only `LESSON`/`PRACTICE`/`TWO_LOOK` resume on cold start.
  >
  > Demonstrations, the Library, the Manual Cube Editor and Scan review are **not** sessions. They use the pure `DemonstrationPlayer` (`:core:domain`), which both FR-6 and FR-18 render through `CubeRenderer`, and they are never checkpointed. Placement is a use case (`PlaceFromScan`) that writes progress events (H6) and then starts a `LESSON` session.

### C4 — Effect results are uncorrelated, so stale results land after Undo

- **Unit A (scan uses / Quick Check, E10c):** the reducer emits `StartQuickCheck(facesToCapture)`. When capture completes, the handler dispatches `ScanAccepted(observation)`, and the reducer compares it with the **current** `expected`. This complies with AD-3: "results come back as events".
- **Unit B (physical mode, E06):** while the camera sheet is open, the Learner taps *Done* or Undo on the step underneath (FR-26: *Done* "still works"). The reducer advances or reverts `expected`.
- **Break:**
  - The observation captured for revision *n* is judged against revision *n±1*. A cube that matched is reported as a mismatch, or the reverse.
  - Worse, the stale observation fits exactly one candidate from the *new* Expected State, so the tutor reports a false *verified*. This breaks the release-gate metric "0 false verified".
  - The same hole exists for `SolveCube`: an Invalid or Solved result for an old editor state overwrites playback for the current one. AD-8 re-verifies the solution against "the input", but nothing says the input is still current.
- **Proposed AD (amend AD-3):**
  > Every `SessionState` carries a monotonically increasing `revision`. Every async `Effect` carries a new `effectId` and the `revision` it was issued at, and every result event echoes both. The reducer applies a result only if its `revision` equals the current one (or, for `ScanAccepted`, if the observation's `basedOnRevision` is current). Otherwise it records `EffectResultDiscarded` and, when the user started the effect, re-offers it. Any event that changes `expected` or `virtual` cancels outstanding effects that depend on them (`CancelEffect(effectId)`). Scan observations are always compared against the Expected State snapshot captured in the effect, never against the live one.

### C5 — A Quick Check is partial, but ScanResult and CubeValidator only handle 54 stickers

- **Unit A (scan capture/validation, E10a/b):** per AD-9, `ScanResult` = `Facelets` (54) + confidence, and "every consumer obtains a CubeState only via CubeValidator". A Quick Check captures at most two faces (FR-26 assumption). To produce a validatable 54-sticker result, A fills the unseen faces from `expected`.
- **Unit B (tutor diagnosis, E05):** per AD-5, a `PhysicalObservation` "is never derived from expected". B models the observation as `Map<Face, FaceColors>` and diagnoses directly from the partial faces, skipping CubeValidator because no `CubeState` is formed.
- **Break:**
  - A's observation is mostly copied from Expected and passes the validator, so it "matches" by construction. That produces false *verified* results.
  - B's path is legitimate but bypasses AD-9's validation clause, and A's `ScanResult` type cannot carry it. The two units cannot exchange observations.
  - FR-26's "the Learner can adopt the observed state" is undefined for a partial observation.
- **Proposed AD (amend AD-5, AD-9):**
  > `PhysicalObservation` is a sealed type:
  > - `Full(state: CubeState)`: produced only through `CubeValidator`.
  > - `Partial(faces: Map<Face, List<Color>>, orientationHint)`: never completed from `expected`.
  >
  > `ScanResult` is likewise `Full` | `Partial`. A `Partial` observation is checked for internal consistency (centers, per-face color counts ≤ 9) but is never converted to a `CubeState`. It can be *consistent with* a candidate, but it can never become one. A `Partial` observation can be adopted as a starting state only after it is completed to `Full` by a Guided 6-face Scan or the Manual Cube Editor.

---

## High

### H1 — Undo/redo across physical confirmations, scans and mode switches; which field each event mutates

- **Unit A (interactive cube, E01):** Undo pops the last history entry of any type, so it can revert a `PhysicalConfirmed` or a `ScanAccepted` (restoring the observation to `null`). Moves mutate `virtual`. The renderer draws `virtual` (AD-4).
- **Unit B (physical mode, E06):** `PhysicalConfirmed` mutates `expected` only. In physical mode, Undo means "I undid it on my cube", so B appends the inverse Move as a new confirmation and keeps history append-only. Redo is not cleared by a scan.
- **Break:**
  - In physical mode the screen shows a stale `virtual` while the tutor evaluates `expected`. The two units each update only one field and render a different one.
  - After undoing past a `ScanAccepted`, A discards evidence the tutor already used. B keeps it.
  - FR-3's "any new Move clears redo" is undefined for scans and confirmations.
  - The result fails NFR-4 ("zero state mismatches across undo").
- **Proposed AD (new AD-3a, "Transition table"):** a normative table, kept in `session/`, lists for each `SessionEvent`, per mode:
  - which of `virtual`, `expected` and `observed` it mutates;
  - whether it is undoable;
  - whether it clears redo.

  The minimum rules are:
  > - In physical mode the renderer projects `expected` (labeled "Expected"), and `virtual` is kept equal to it.
  > - Undo of a `PhysicalConfirmed` reverts `expected` and prompts the Learner to perform the inverse; it is not a new confirmation.
  > - `ScanAccepted`, `ModeSwitched` and `CheckpointRestored` are **undo barriers**: Undo never crosses them, and they clear redo.
  > - Any `PhysicalConfirmed` after an observation marks that observation `stale` (it is no longer evidence for the current revision).

### H2 — Checkpoint blob format vs content `schemaVersion` and content revisions

- **Unit A (persistence, E07):** serializes the whole `SessionState` with kotlinx.serialization, including the embedded `Lesson` object and the step *index*, and tags it with the content `schemaVersion` (AD-10). Room migrations cover tables only.
- **Unit B (lesson content, E04):** edits a Lesson's steps (inserts a step, renames a string key) without changing the JSON *shape*, so `schemaVersion` is unchanged (AD-6 versions the schema, not the content).
- **Break:**
  - On restore, A's step index now points to a different step, and the embedded Lesson is stale. The tag matches, so nothing detects it.
  - When the shape does change, A cannot tell whether to discard or migrate, because AD-10 defines no policy.
  - A Kotlin refactor of `SessionState` silently breaks every stored blob, because no Room migration test covers JSON inside a column.
- **Proposed AD (amend AD-10):**
  > A Checkpoint is a dedicated `CheckpointV<n>` DTO, not `SessionState`. It carries `checkpointFormat: Int`, `contentRevision: String` (a hash of the content bundle), `sessionKind`, content **IDs** (`lessonId`, `stepId`; never indices and never embedded content), cube states encoded as 54-char facelet strings plus orientation, history as Notation, and `mode`. Restore resolves the IDs against the loaded content. If `contentRevision` differs or an ID is missing, it restores to the start of the same Lesson (or the Stage's first Lesson) with the stored cube state, and tells the Learner. Every `checkpointFormat` bump ships a decoder for the previous format and a round-trip test.

### H3 — `PersistCheckpoint` effects can race; attempt log and checkpoint are not atomic

- **Unit A (session, E04):** emits `PersistCheckpoint` on every transition (AD-10). The shell launches each effect on `Dispatchers.IO` (AD-11 confines only the reducer).
- **Unit B (persistence, E07):** `CheckpointRepository.save()` does a plain upsert. `ProgressRepository.recordAttempt()` runs in a separate transaction.
- **Break:**
  - Two quick Moves produce two concurrent writes. The older write can commit last, leaving a stale Checkpoint after process death (NFR-6).
  - A kill between the attempt insert and the checkpoint write replays the completion on resume, which inserts a duplicate Attempt and counts toward mastery twice.
- **Proposed AD (amend AD-10/AD-11):**
  > Persistence effects run on a single serial writer per database. `PersistCheckpoint(revision)` is conditional: `UPDATE … WHERE revision < :new`. When one transition emits both log rows and a Checkpoint, they are written in **one** Room transaction. Log rows carry an `(sessionId, revision)` idempotency key, with a unique index.

### H4 — Two owners of Color Calibration

- **Unit A (scan capture, E10a):** the Calibration screen (FR-24) writes reference colors through `PreferencesRepository`, because AD-10 lists only three ports and calibration sits under the profile in the ERD.
- **Unit B (scan uses, E10c):** a Quick Check in a Lesson runs inside the session, so B puts the active calibration into `SessionState`, updates it through a `CalibrationUpdated` event, and lets it ride in the Checkpoint. AD-3 says all session changes go through events.
- **Break:**
  - After the Learner recalibrates in A, a resumed Lesson restores B's stale copy from the Checkpoint. Classification then uses the old reference colors.
  - FR-19's separate "reset calibration" clears A's copy but not B's.
- **Proposed AD (amend AD-9/AD-10):**
  > Color Calibration has one owner: the `CalibrationRepository` port. Only the `UpdateCalibration` and `ResetCalibration` use cases write to it. `StickerClassifier` receives the calibration as a parameter, which the classification effect handler reads at classification time. Calibration is never part of `SessionState` or a Checkpoint.

### H5 — Two owners of Algorithm Status, and Case-vs-Algorithm keying

- **Unit A (Library, E11):** FR-18 is not session-bound. The Library ViewModel updates a mutable `algorithm_status` table keyed by `algorithmId` (`oll-45`).
- **Unit B (2-look Lesson / case recognition, E11/E10c):** FR-17/FR-28 offer status changes from inside a session. B emits a `LearningEvent(AlgorithmStatusSet, caseId)`, which is insert-only per AD-10, and derives the current status from the latest event. B keys by Case, because FR-28 recognizes Cases and a 2-look "dot" Case maps to several Algorithms.
- **Break:**
  - The Library and the Lessons each read a different store, so a status set in one never shows in the other.
  - The `oll-45` ID format does not say whether it names a Case or an Algorithm. When P1 adds alternative Algorithms per Case, the two keyings split.
  - "Reset all progress and Algorithm Statuses" deletes rows in A, while B needs a reset marker.
- **Proposed AD (amend AD-6/AD-10):**
  > Case IDs (`case-oll-45`) and Algorithm IDs (`alg-oll-45-a`) are distinct namespaces, and each Algorithm declares its `caseId`. Algorithm Status is keyed by **algorithmId**. It is owned by `AlgorithmStatusRepository` and written only by the `SetAlgorithmStatus` use case, which the Library, Lessons and recognition all call. Lessons do not write it through reducer state. The repository stores an append-only `algorithm_status_event` log and exposes `Flow<Map<AlgorithmId, Status>>`. Reset is an inserted `StatusReset` event, never a delete.

### H6 — Stage status (skipped / skipped by placement / reset) under insert-only rows

- **Unit A (progress, E07):** AD-10 says mastery and trends "are computed from" insert-only Attempts, so A derives the Stage status (completed/mastered) from Attempts only.
- **Unit B (scan uses, Placement, E10c):** FR-27 must mark earlier Stages *skipped by placement* and unlock the suggested one. No Attempt describes that, so B writes a mutable `stage_status` row.
- **Break:**
  - A's derived view never shows *skipped*, so Placement unlocks nothing.
  - If A later adds a mutable row too, two sources of truth disagree on which Stage is unlocked.
  - The FR-4 skip-ahead check has the same problem.
  - FR-19's "reset all progress" either deletes insert-only rows (violating AD-10) or does nothing.
- **Proposed AD (amend AD-10):**
  > Stage progress is derived only from an append-only `progress_event` log with the types `LessonCompleted`, `StageSkippedByCheck`, `StageSkippedByPlacement`, `StageUnlocked` and `ProgressReset(epoch)`. `Mastery` is a pure function over Attempts and progress events in the current epoch. Only use cases write progress events (`CompleteLesson`, `PassSkipCheck`, `PlaceFromScan`, `ResetProgress`).

### H7 — Predicate parameter shape, orientation anchoring and preserved-piece sets

- **Unit A (content authoring, E04):** writes `{"predicate":"crossSolved","color":"WHITE"}` as in AD-6, and builds Stage Predicates such as "first layer solved" by adding an `allOf` predicate whose parameter is a list of predicates. Adding a registry entry is allowed.
- **Unit B (Placement and recovery, E10c/E05):**
  - B's `crossSolved` means "cross on the **D** face", matching the Lesson frame.
  - B's parameters are nested: `{"predicate":"crossSolved","params":{"color":"WHITE"}}`.
  - B's Guided recovery (FR-12) needs "the current Objective's preserved pieces". Predicates return `Boolean`, and the Lesson `preserve` field (addendum §I) is a text string key.
- **Break:**
  - The two units' content JSON fails each other's parser.
  - Placement on a scanned cube held with white on U says the cross is *not* solved.
  - `RecoveryPlanner` has no machine-readable set of pieces to preserve, so it falls back to the whole cube or a Solver path (AD-8 forbids the latter for tutoring).
- **Proposed AD (amend AD-6):**
  > Each predicate is a `@Serializable` sealed subclass of `CompletionPredicate`, using the class discriminator `"predicate"` and flat parameters. Composition is limited to the registry-provided `allOf` / `anyOf`. Predicates are **color-anchored and orientation-invariant**: they locate the face by its center color, never by its face letter. Every predicate also implements `preservedPieces(state): Set<Cubie>`, which `RecoveryPlanner` and the Error Category *completed layer disturbed* use. The Lesson `preserve` text is display-only.

### H8 — LL Case pattern and AUF canonicalization differ across consumers

- **Unit A (Library, E11):** derives each Case by applying the inverse of the Algorithm to the home solved state (white on U, per C2), so the LL is the white face on U. The diagram stores full sticker colors for the U face and the side bands. The FR-18 test checks "LL solved up to AUF" with `U^k` applied only *after* the Algorithm.
- **Unit B (Case recognition, E10c):** normalizes the scanned cube to yellow on U (the beginner-method LL). It compares OLL by orientation mask only and PLL by side colors. It allows `U^k` *before* matching but ignores that viewing the cube from a different side is a whole-cube `y`, not a U-layer turn.
- **Break:**
  - Patterns derived with a white LL never match a yellow-top scan.
  - PLL Cases can be confused with each other, or missed, depending on whether AUF is applied before or after and whether `y` is included.
  - The 2-look Completion Predicates ("this LL step is achieved") define AUF in a third way.
  - FR-28's "never picks one silently" cannot be guaranteed, because the candidate sets differ between units.
- **Proposed AD (amend AD-6):**
  > One `LastLayerMatcher` in `:core:domain/recognition` is used by the FR-18 verification, diagram derivation, FR-28 and the 2-look predicates.
  > - **Canonical frame:** the LL is the face whose center color is opposite the solved F2L's color (YELLOW for the course), rotated to U.
  > - **OLL** patterns are the 20-bit orientation mask, matched under `U^k` (pre-AUF).
  > - **PLL** patterns are the LL permutation relative to F2L, matched under pre- and post-AUF and under `y^k` viewing.
  > - Partial views (U plus two sides) return the candidate *set*.
  > - Case states are derived with the LL on U from a cube rotated so that YELLOW is on U (`x2` applied to home).

### H9 — Certainty has many producers; candidate enumeration is unbounded and unshared

- **Unit A (scan uses, `ConfirmPhysical`, E10c):** computes Certainty itself. FR-26 says the confirmation itself is "recorded with Certainty verified", and AD-5 restricts only *Diagnosis*, so A may mark the confirmation VERIFIED. A enumerates single-Move alternatives (18 face turns) from Expected.
- **Unit B (tutor diagnosis, E05):** enumerates sequences of up to 2 Moves (including Cube Rotations) to "explain" a mismatch. AD-5 says VERIFIED only when "exactly one candidate explains it".
- **Break:**
  - For the same partial observation, A finds exactly one candidate and reports VERIFIED, while B finds three and reports LIKELY. The two screens show different Certainty for the same evidence.
  - A also marks a confirmation VERIFIED when only the faces affected by the most recent Move were seen, even though an earlier unverified confirmation could have gone wrong on another face.
- **Proposed AD (amend AD-5):**
  > Certainty is produced **only** by `tutor/Assess` in `:core:domain`, for both confirmations and Diagnoses.
  > - **Candidate space:** `CandidateEnumerator(expected, maxDepth)`, with `maxDepth` and the move set set in tutor configuration data. The same enumerator is used everywhere.
  > - **Verified match:** a match is VERIFIED only if the observed faces cover every face that any Move since the last `Full` verified observation could have touched, and exactly one candidate within `maxDepth` is consistent.
  > - **Otherwise:** LIKELY or UNKNOWN.

---

## Medium

### M1 — Clock/seed use inside a pure reducer; scramble persistence

- **Unit A (practice, E07):** the reducer emits `GenerateScramble(seed)`. The Checkpoint stores the seed and regenerates the scramble on restore.
- **Unit B (lesson, E04):** a use case draws the scramble Moves through `RandomSource` before dispatching `LessonStarted(moves)`. The time of a `LearningEvent` is stamped by the persistence handler when it writes the row.
- **Break:**
  - A change to the scramble generator makes A's stored seeds regenerate different cubes, so resumed practice no longer matches the Learner's physical cube.
  - Event timestamps reflect write order, not event order, which skews the FR-20 trends.
- **Proposed AD (amend AD-11):**
  > The reducer never reads `Clock` or `RandomSource`. The session loop stamps every event with `at: Instant` at dispatch. Scrambles are generated by the `NewScramble` use case and enter the session as an explicit `List<Move>`. Seeds are only for test reproduction and are never persisted as a substitute for the Moves.

### M2 — AnimationQueue vs non-Move state jumps; input during animation

- **Unit A (renderer, E03):** assumes `state == apply(previousState, queue)` and animates Undo as an inverse Move.
- **Unit B (session, E04):** Undo restores a snapshot. `CheckpointRestored` and `ScanAccepted` replace the state with no Moves at all.
- **Break:**
  - On a jump, the renderer's internal state fails reconciliation or animates a wrong path.
  - FR-1's rule "input during animation is queued or rejected deterministically" is decided in A's ViewModel for one unit and in B's reducer for the other, so they behave differently.
- **Proposed AD (amend AD-4):**
  > A `Transition` yields `Presentation`: a list of `Animate(move)` | `Snap`. Each item carries the target `revision` and `CubeState`. The renderer must end at exactly the last target state. Undo yields `Animate(inverse)`. Restores and scans yield `Snap`. Input during animation is always accepted by the reducer immediately (commit-first). The presentation queue then plays at accelerated speed, never dropping a committed Move.

### M3 — String-key namespace, placeholders and the domain-message split

- **Unit A (lessons, E04):** uses keys such as `hint.1` and `objective` in `content/strings/en.json`, with `%s` placeholders.
- **Unit B (editor and playback, E02/E08):** puts validation messages ("this corner has two whites") and FR-16 Move-note templates in `strings.xml` as "shell chrome", with `{face}` placeholders. B's domain `ValidationResult` returns raw codes.
- **Break:**
  - Keys collide across Lessons.
  - Domain-produced messages live in two catalogs, so localization (NFR-10) needs code changes.
  - Placeholder syntax differs between the two catalogs.
- **Proposed AD (amend AD-6):**
  > - **Key format:** `<area>.<contentId>.<field>[.<n>]` (e.g. `lesson.lesson-02-03.step-2.goal`, `msg.validation.twisted-corner`, `note.move.template`).
  > - **Domain messages:** every message a `:core:*` result can produce is a `msg.*` key in `content/strings/<locale>.json`. `strings.xml` is limited to static navigation chrome.
  > - **Placeholders:** use ICU named arguments (`{face}`).
  > - **Content test:** a test fails the build on duplicate keys, on a key that is referenced but missing, or on a mismatch in arguments.

### M4 — Attempt boundary, who records it, and the meaning of "reduced assistance"

- **Unit A (adaptive tutor, E05):** an attempt is a Lesson run. "Reduced assistance" is the FR-13 assistance level being below the default.
- **Unit B (mastery, E07):** an attempt is a Stage-Objective solve. "Reduced assistance" means zero hints. The 2-look ViewModel writes Attempts directly to the repository instead of through a reducer effect.
- **Break:** the two units use different denominators. FR-20 mastery and the SM-5 hint trend give different answers for the same play history.
- **Proposed AD (amend AD-10):**
  > The reducer emits `AttemptStarted` / `AttemptEnded(outcome, errors, hints, assistanceLevel)` effects. These are the only source of Attempt rows, and no ViewModel writes them. An Attempt is one run toward one Objective (`objectiveId`). "Reduced assistance" is defined in the Mastery configuration as a maximum `assistanceLevel`, and the reducer records that level per attempt.

### M5 — Scan sub-flow return contract

- **Unit A (scan capture, E10a/b):** the Scan → Correct → Validate screens publish the accepted state on a singleton `SharedFlow` that any screen can collect.
- **Unit B (Quick Check / Solve my cube, E10c):** expects a navigation result. The physical session expects `ScanAccepted` to be dispatched by the session host, not by the scan screen.
- **Break:** a scan started from *Solve my cube* is consumed by the background Lesson session, or the result is lost when the screen is recreated.
- **Proposed AD (amend AD-9):**
  > The scan flow is a sub-flow that returns `ScanOutcome {Accepted(observation, basedOnRevision?), Cancelled, FellBackToEditor(observation?)}` to its caller via a typed navigation result. Only the caller dispatches it, to its own session or use case. The scan flow never touches a session.

### M6 — Recovery planning inline in the reducer vs as an effect

- **Unit A (tutor, E05):** `reduce()` calls `RecoveryPlanner` inline, so the `TutorDecision` already carries the recovery Moves. IDA* is pure, and this complies with AD-3.
- **Unit B (`PlanRecovery`, E05):** `PlanRecovery` uses `CubeSolver` (AD-8 allows it), and AD-8 says the Solver runs off the main thread, so B runs it as an effect.
- **Break:**
  - A blocks the confined reducer for an unbounded search, which risks the NFR-1 100 ms acknowledgement.
  - The two units' `TutorDecision` shapes disagree on whether recovery Moves are present synchronously.
- **Proposed AD (amend AD-3/AD-8):**
  > `reduce()` performs only bounded, constant-time-class work: predicates, rule tables and enumeration within the configured `maxDepth`. Any search, including `RecoveryPlanner` and `CubeSolver`, is an Effect with a timeout. `TutorDecision.recovery` is `Pending(effectId)` | `Ready(moves)` | `Unavailable`.

---

## Low

### L1 — Move equality and canonical form

- **Unit A (Notation, E01):** keeps `R2'` as amount −2 and `r` distinct from `Rw` for round-trip display.
- **Unit B (diagnosis, E05):** compares Moves structurally, so it flags `R2` vs `R2'` as "wrong direction".
- **Proposed AD (amend AD-7):**
  > `Move` equality is by effect (`R2 == R2'`). `Notation.format` emits the canonical form (`R2`, `r`), and the original spelling is kept only in content. Slice directions follow WCA (`M` as `L`, `E` as `D`, `S` as `F`).

### L2 — "Last Teaching Mode" preference has two writers

- **Unit A (session, E04):** writes the preference from the `ModeSwitched` effect.
- **Unit B (settings screen, E07):** writes it directly through `PreferencesRepository`.
- **Break:** the last write wins, depending on ordering.
- **Proposed AD (amend AD-10):**
  > Preferences are written only by `UpdatePreference` use cases. The session emits `UpdatePreference(LastTeachingMode)` as an effect.

---

## Cross-cutting recommendation

Add an **Ownership Matrix** to the spine. It should list each entity and each piece of state, with exactly one writer (a use case or the reducer), its readers and its store:

| Entity | Writer | Readers | Store |
| --- | --- | --- | --- |
| CubeState / orientation | | | |
| Checkpoint | | | |
| Attempt | | | |
| progress_event | | | |
| Algorithm Status | | | |
| Color Calibration | | | |
| Preferences | | | |
| Scan observation | | | |
| Certainty | | | |

Most of the holes above are two cells filled in differently by two epics.
