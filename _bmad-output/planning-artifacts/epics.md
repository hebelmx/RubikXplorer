---
status: final
stepsCompleted: [step-01-validate-prerequisites, step-02-design-epics, step-03-create-stories, step-04-final-validation]
mode: fast-path
created: 2026-10-29
updated: 2026-11-24
inputDocuments:
  - _bmad-output/planning-artifacts/prds/prd-RubikXplorer-2026-10-29/prd.md
  - _bmad-output/planning-artifacts/prds/prd-RubikXplorer-2026-10-29/addendum.md
  - _bmad-output/planning-artifacts/architecture/architecture-RubikXplorer-2026-10-29/ARCHITECTURE-SPINE.md
  - _bmad-output/planning-artifacts/ux-designs/ux-RubikXplorer-2026-10-29/DESIGN.md
  - _bmad-output/planning-artifacts/ux-designs/ux-RubikXplorer-2026-10-29/EXPERIENCE.md
referenceDocuments:
  - _bmad-output/planning-artifacts/ux-designs/ux-RubikXplorer-2026-10-29/mockups/ (visual reference only)
  - _bmad-output/planning-artifacts/prds/prd-RubikXplorer-2026-10-29/review-rubric.md (scanned for leftovers)
  - _bmad-output/planning-artifacts/prds/prd-RubikXplorer-2026-10-29/reconcile-mvp-draft.md (scanned)
  - _bmad-output/planning-artifacts/architecture/architecture-RubikXplorer-2026-10-29/reviews/*.md (scanned)
  - _bmad-output/planning-artifacts/ux-designs/ux-RubikXplorer-2026-10-29/reconcile-prd-architecture.md (scanned)
---

# RubikXplorer - Epic Breakdown

## Overview

This document provides the complete epic and story breakdown for RubikXplorer, decomposing the requirements from the PRD, UX Design if it exists, and Architecture requirements into implementable stories.

**How to read it:**
- Epics are grouped by user value and sequenced by the ~16-week roadmap (addendum §F). Each epic lists the addendum sketch IDs (E01–E11) it absorbs.
- Every story carries a **Refs** line citing its FR, NFR, AD (architecture spine), UX-DR (below) and UX Key Flow IDs.
- Glossary terms are used verbatim. `[ASSUMPTION: …]` marks Fast-path inferences to confirm.
- Story IDs are stable identifiers. Stories normally execute in numeric order; approved split follow-ups may execute earlier than their appended ID. Story 3.8 depends on 3.2 and must pass before production acceptance of 3.7. Stories 3.3–3.6 may proceed against accepted APIs without selecting an unmeasured production delivery policy. Across epics, preserve the existing dependency direction and explicitly documented parallel tracks.

## Requirements Inventory

### Functional Requirements

FR-1: Render a 3×3 cube (6 × 9 stickers) and apply Moves. Gestures cover face turns and Cube Rotations; buttons cover every Move type, including wide and slice turns A drag previews its Move and commits on release; a middle-layer drag makes a slice and a two-finger drag on the cube makes a wide Move (SCP 2026-11-18). After every committed Move the rendered cube equals the Virtual State sticker by sticker; input during animation is handled deterministically; a quarter turn animates in ≤ 250 ms on the reference device.
FR-2: Rotate the view freely without changing the Cube State. No sequence of View Rotations changes the Virtual State; Notation follows the cube's orientation, not the camera [ASSUMPTION].
FR-3: Undo repeatedly, redo, and reset. Undo restores the exact previous state; redo restores an undone state and any new Move clears redo; reset asks for confirmation if it would discard unsaved Lesson progress.
FR-4: Stage-gated Course (7 Stages). A Stage unlocks by prerequisites, a skip-ahead check, a chosen entry level (SCP 2026-11-20, #209; skipped, never mastered, nothing downgraded), or Placement. Every Lesson declares prerequisites, Objective, Demonstration, learner action, Completion Predicate and Recovery Path (schema test fails the build otherwise); every Lesson has an automated legal path to its predicate; completion is by predicate, never Move count; "I already know this" runs a check from a fresh Scramble and marks *skipped*, not *mastered*.
FR-5: Four Teaching Modes (*Show me*, *Guide me*, *Test me*, *Let me try*) all evaluate the same Completion Predicate; *Guide me* states the Objective before each Move; *Test me* / *Let me try* reveal the next Move only after a hint request.
FR-6: Explained Demonstrations with Notation step by step, highlighted pieces and direction arrows; play, pause, step forward/back, replay, speed; every step states goal, preserve, watch and (after) notice, each a Lesson-schema field; all text outside UI code.
FR-7: Physical-cube Mode: perform the Move on a real cube, then tap *Done* or enter the Move actually made. The Expected State changes only after confirmation or a validated Scan; it always carries an "Expected" label; View Rotation changes neither Notation nor Expected State.
FR-8: Switch between virtual and Physical-cube Mode mid-Lesson without restarting; switching to physical asks the Learner to confirm their cube matches, scan it, or enter it.
FR-9: Report "my cube doesn't match" / "I'm not sure what I did" and enter a Recovery Path. A physical error is *detected* only with a Learner-entered Move or validated Scan as evidence; otherwise Certainty is *unknown* and the tutor asks, in order: Quick Check → which Move → one face → full re-entry.
FR-10: Diagnose the supported Error Categories from Cube State, Move history and Scan evidence. Each maps to a deterministic rule or explicit *unknown*, with scenario tests; each has a tested Recovery Path; several possible causes → *likely* or *unknown*, never false *verified*; never silently replace the Learner's state.
FR-11: Hint Ladder: each request moves up exactly one rung (clue → highlighted target or Move → full Demonstration); resets when the Objective changes; hint use recorded per attempt; full Solution never shown by default.
FR-12: Recover from mistakes: retry, undo, or Guided recovery to the shortest correction that restores the current Objective's preserved pieces; never restarts the Lesson unless chosen. Disturbed-cross scenario: say so, offer undo/Guided recovery, keep the earlier Checkpoint, don't unlock the next Stage.
FR-13: Rule-based adaptive assistance (first mistake → explain + highlight; repeated → demonstrate; continued difficulty → guide step by step; repeated success → less help). Thresholds are configuration data with tests; tuning from pilot data is P1.
FR-14: Manual Cube Editor for 54 sticker colors with validation that rejects wrong color counts, impossible pieces, a twisted corner, a flipped edge and a parity violation, each with a plain-language reason; 54 colors entered never counts as valid by itself; same validation applies to every Scan Result; fallback whenever the camera is unavailable; each face may be entered in any rotation (resolved through `ScanAssembler`, asked only when ambiguous or unresolvable), messages are specific (color counts, named pieces and faces), and no piece error shows while faces of a new entry are not entered yet (SCP 2026-11-20, #253).
FR-15: Solve any valid Cube State offline. Solution always solves its input (verified over solved + ≥ 10,000 random legal states, incl. deep Scrambles); invalid input rejected before the Solver; timeout/failure → explicit error with recovery prompt, never a made-up Solution; never blocks the UI thread.
FR-16: Solution Playback: step forward/back, pause, replay, with a templated note per Move (face, direction, piece group); notes never claim strategic purpose; in Physical-cube Mode each *Done* advances one step and a Quick Check is available at any step; the Learner can switch between the 3D cube and the 2D net (opens in 3D, on the net in Physical-cube Mode, then remembers the choice) and see an optional picture of each Move (SCP 2026-11-17).
FR-17: 2-look LL mini-Course (2-look OLL + 2-look PLL) unlocked after the Course or a skip-ahead check; Lesson predicates are LL-step predicates on Cube State; Case Lessons reference Library entries; *completed* when all predicates met, *mastered* after two hint-free LL solves of fresh Scrambles using only 2-look Algorithms [ASSUMPTION]; offers (never sets) Algorithm Status *Learning*.
FR-18: Algorithm Library of all 78 (57 OLL + 21 PLL) Algorithms grouped by family; watch Demonstration (play, pause, step, speed, View Rotation), see recognition diagram and Notation, set Algorithm Status; practice it on a real cube with *Done* and a Quick Check at any step (SCP 2026-11-20, #208); build-failing verification test per Algorithm (inverse from solved matches Case; Algorithm solves LL up to AUF); filter by status and family; diagrams derived from Case state; one primary Algorithm per Case [ASSUMPTION].
FR-19: Persist across restarts and forced process death: Stage completed/skipped/mastered, current Checkpoint, attempt outcomes (errors, hints), Algorithm Statuses, Color Calibration, preferences (animation speed, color-blind display, last Teaching Mode, Solution Playback view, Move pictures (SCP 2026-11-17)). Latest committed Checkpoint restored; physical Checkpoint asks to confirm/scan/re-enter; repeats append, never corrupt; reset progress + Algorithm Statuses with confirmation, separate resets for calibration and preferences.
FR-20: Independent practice on a fresh Scramble with reduced help; a Stage is *mastered* only by its Mastery Condition (initially 2 successful completions, ≥ 1 with reduced assistance, configurable [ASSUMPTION]); transitions follow configuration with tests; neither Lesson completion nor *skipped* marks *mastered*; the Learner can see their hint and error trend.
FR-21: Guided 6-face Scan: live viewfinder, face prompts, automatic capture when aligned and stable plus a manual shutter; each face's rotation worked out from adjacency checks, and the faces involved re-prompted with a named top when it can't be (SCP 2026-11-16; with no valid state, only when at most 2 faces are involved, else straight to review with the best-fitting turns, SCP 2026-11-19, #164); every sticker has a Sticker Confidence, uncertain ones highlighted for correction before acceptance.
FR-22: Quick Scan: 2–3 corner-angle captures, each showing three faces; two opposite-corner captures yield 54 stickers; uncertain/invalid → ask only for missing faces via Guided 6-face Scan, never a guessed state; the Learner can switch to the Guided Scan at any time, keeping captured faces (SCP 2026-11-20, #198); "beta" unless it meets its targets.
FR-23: Review a Scan Result on a net (or 3D cube), correct any sticker, accept only once valid; invalid states name likely bad stickers; 54 recognized colors never valid by themselves; low light/glare/blur/incomplete capture → specific guidance and recapture.
FR-24: Color Calibration from the six centers; stickers classified against the cube's own centers (stickerless/pastel work after calibration); persists per device, resettable; standard color scheme only [ASSUMPTION].
FR-25: Scan to solve in *Solve my cube*: scan → correct → validate → solve → playback without leaving the flow; Solver never called on an unvalidated Scan Result; camera permission denied → Manual Cube Editor.
FR-26: Quick Check in Physical-cube Mode (Guided 6-face Scan or only affected faces, ≤ 2 captures [ASSUMPTION]): match → recorded *verified*; mismatch → observed faces become Diagnosis evidence; *verified* only when exactly one candidate Move sequence fits, and a Quick Check of only some faces verifies only a match (a mismatch is verified only by a Guided 6-face Scan), otherwise *likely* + offer a Guided 6-face Scan *(SCP 2026-11-17)*; Learner can adopt the observed state for Guided recovery; offered at milestones and after mismatches, available at any step including mid-Algorithm (SCP 2026-11-20, #208), never required.
FR-27: Placement: scan a cube, evaluate every Stage Predicate, suggest the first unmet Stage (white cross only [ASSUMPTION]); Learner accepts or picks another; earlier Stages marked *skipped by placement*, never *mastered*, always revisitable; the result states what the scan saw and why, with a ≤ 2-Moves-from-solved outcome first (#251); re-placement from outside Welcome only adds progress (#252) (SCP 2026-11-20).
FR-28: Recognize an OLL/PLL Case from two opposite top-corner captures (yellow on top; U + two sides each): compare against every Case's derived pattern under AUF and intersect the captures' candidates; ambiguous → list candidates and offer another angle; no fit → say so; F2L not solved → say so and offer Placement; offer (never force) an Algorithm Status change.
FR-29 (P1, out of MVP scope): recognition and execution drills with spaced repetition and a per-Algorithm mastery rule separate from Algorithm Status.
FR-30 (v1.0, SCP 2026-11-20, #210): method-aware solutions (beginner, 2-look, full OLL + PLL) built from the Course's predicates and Library Algorithms, segment-tagged by Stage or Case, preferring *Learning*/*Learned* Algorithms; the Solve tab keeps the optimal solution as default.

### NonFunctional Requirements

NFR-1: Responsiveness — face-turn input acknowledged within 100 ms.
NFR-2: Rendering — ≥ 30 FPS during face turns and View Rotation on the minimum reference device; 60 FPS target on mid-range.
NFR-3: Solver performance — 95% of a representative random-state corpus solved within 3 s on the minimum reference device; median, p95, memory and failure rate reported. *(SCP 2026-11-12)*
NFR-4: Correctness — zero state mismatches between engine, rendering and Expected State across the randomized Move/undo/interruption suite; property tests: inverse restores, M⁴ = identity, sequence + inverse = identity, illegal states rejected; cube engine has the strongest coverage.
NFR-5: Offline — a fresh install with no network can complete the Course, use the Library, scan and solve; no network permission declared; camera is the only runtime permission.
NFR-6: Persistence — the latest Checkpoint survives forced process death.
NFR-7: Accessibility — content labels on every control, WCAG AA contrast, scalable text, a non-gesture alternative for every essential action, color-blind display (P0).
NFR-8: Privacy — no account, no PII, no analytics SDK, no ads; data in app-private storage only; Play data-safety form declares "no data collected".
NFR-9: Stability — ≥ 99.5% crash-free sessions; ANR rate below the Play bad-behavior threshold.
NFR-10: Localizability — all Learner-facing text in resources and Lesson data; English only at launch.
NFR-11: App size — install < 30 MB including Solver tables and any recognition model [ASSUMPTION].
NFR-12: Code quality — `:core:*` pure Kotlin with ≥ 80% line coverage; static analysis and formatting enforced in CI; every merge to main builds, tests and lints green.
NFR-13: Compatibility — minSdk 29; phones and tablets, portrait and landscape; targetSdk meets Play policy.
Recognition NFRs (PRD §4.8, provisional until the spike): Guided 6-face Scan ≥ 98% valid after ≤ 3 corrections, ≥ 99.5% stickers correct indoors, < 45 s repeat user; Quick Scan GA ≥ 95% valid after ≤ 3 corrections, < 15 s; Quick Check 0 false *verified*, ≥ 98% matching cubes confirmed, ≤ 2 captures; Case recognition ≤ 1% wrong when one Case is named, < 1 s; ≤ 0.1% of stickers misread *and* marked *confident*; frames processed in memory only; camera permission requested at first scan with explanation; every scan feature has a manual fallback.

### Additional Requirements

From the architecture spine (no starter template is specified; Epic 1 Story 1 is a from-scratch scaffold):

- AR-1 (AD-1, Stack, Structural Seed): Gradle multi-module scaffold `:core:domain`, `:core:solver`, `:core:application` (all `kotlin("jvm")`, no Android deps) and `:app`; `build-logic` convention plugins (jvm-core, android-app, quality, release); versions only in `gradle/libs.versions.toml`; Kotlin 2.4.21 pinned on the root classpath; AGP 9.5.0 built-in Kotlin (no `kotlin-android`, no kapt); compileSdk 37 / targetSdk 36 / minSdk 29; package root `dev.rubikxplorer.<module>.<area>` [ASSUMPTION].
- AR-2 (Stack, handoff): confirm KSP 2.3.12 against Kotlin 2.4.21 at scaffold; then refresh `AGENTS.md` via `bmad-project-context` with the real `./gradlew` commands.
- AR-3 (AD-14): CI on GitHub Actions for every PR to `main`: build, Spotless/ktlint, Android lint incl. accessibility, JVM tests, Kover ≥ 80% on `:core:*`, AD-13 checks, AAB size check; detekt 2.x non-blocking; release tags also run instrumentation, UI and renderer-sync tests.
- AR-4 (AD-13): merged manifest has no `INTERNET`; `CAMERA` the only runtime permission; `allowBackup="false"` and exclude-all `dataExtractionRules`; release logs never contain Cube States or scan samples; CI fails on violation or a denylisted analytics/ads/crash SDK.
- AR-5 (AD-15): AAB only, Play App Signing, upload keystore in GitHub secrets; `debug` (StrictMode, fixture recorder, all flags) vs `release` (R8 + resource shrinking, keep rules in `build-logic`); R8 mapping uploaded; `versionCode` = CI run number; `vX.Y.Z` tag → internal track; manual promotion.
- AR-6 (AD-2): `CubeState = (cubies, orientation)`; home frame WHITE on U, GREEN on F; `Facelets` 54 × URFDLB row-major per Kociemba net with a checked-in diagram fixture; `CubeValidator` is the only route from `Facelets` to `CubeState`; orientation-only difference = `UNEXPECTED_ORIENTATION`.
- AR-7 (AD-3): one active `LearningSession` app-wide in `@Singleton SessionHost`; `SessionKind {LESSON, PRACTICE, TWO_LOOK, SOLVE_PLAYBACK}`; pure `reduce(SessionState, SessionEvent): Transition(state, effects, presentation)`; events stamped `at: Instant`; reducer never reads `Clock`/`RandomSource`; `revision` + `effectId` correlation, stale results discarded (`EffectResultDiscarded`); normative `session/TRANSITIONS.md`.
- AR-8 (AD-4): commit before animate; every input → `MoveIntent` → `MoveCommitted`; presentation queue of `Animate`/`Snap` with fast-forward above 3 queued; `CubeRenderer` port never mutates state and exposes `renderedFacelets()`; pure `GestureInterpreter`; View Rotation never a `SessionEvent`; 2D net renderer first.
- AR-9 (AD-5): `PhysicalObservation = Full | Partial`; Certainty only from `tutor/Assess` with one `CandidateEnumerator(expected, maxDepth, moveSet)`; `Diagnosis.verified(...)` smart constructor; closed `ErrorCategory` enum with exhaustive `when` and one test per category; fixed `TutorDecision` fields.
- AR-10 (AD-6): content JSON under `:core:domain` resources, kotlinx.serialization; `content/schema/*.schema.json` checked against the Kotlin model; `schemaVersion` + `contentRevision`; sealed `Predicate` registry with `allOf`/`anyOf`, color-anchored, orientation-invariant, `preservedPieces`, `ObjectiveProgress`; stable distinct ID namespaces; `Text(key, args)` with ICU args in `content/strings/<locale>.json`; content test fails on duplicate/missing/mismatched keys.
- AR-11 (AD-7): `Notation.parse`/`format` are the only string↔Move conversions; equality by effect; WCA slice directions.
- AR-12 (AD-8): clean-room Kotlin Kociemba two-phase `CubeSolver` in `:core:solver`, no third-party code; called only by `SolveCube` and `PlanRecovery` as Effects with a 3 s timeout; receives `cubies` only; `SolveResult.{Solved, Invalid, TimedOut, Failed}`; every `Solved` re-verified; pruning tables built off-main and cached; bundled-vs-first-run decided in the solver epic within NFR-11.
- AR-13 (AD-9): only the CameraX adapter touches pixels and emits `FaceSample` (center, up-neighbor, 9 CIELAB samples in viewfinder order, quality flags, pose); `ScanAssembler` and `StickerClassifier` in `:core:domain`; `ScanAccepted` rejected unless Full + valid + all uncertain stickers resolved + current `basedOnRevision`; scan sub-flow returns `ScanOutcome {Accepted, Cancelled, FellBackToEditor}`; Color Calibration owned by `CalibrationRepository`; debug-only fixture recorder exports `FaceSample` JSON.
- AR-14 (AD-10): `SessionSnapshot` per committed transition; `Checkpoint` at Objective boundaries; versioned Checkpoint DTO (format, `contentRevision`, IDs, Facelets + orientation, Notation history, mode) with previous-format decoder and round-trip test; serial writer, revision-conditional, single transaction, idempotency key `(sessionId, revision)`; append-only `attempt`, `progress_event` (with `ProgressReset(epoch)`), `algorithm_status_event`; Room migration + migration test per schema change.
- AR-15 (AD-11, AD-12, Conventions): Hilt only in `:app`; reducer on one confined dispatcher, search/classification on `Dispatchers.Default`; `NewScramble` via `RandomSource`, Scrambles enter sessions as `List<Move>`; sealed results, explicit unknown variants, effect failures → `…Failed` events; `Logger` port implemented only in `:app`.
- AR-16 (AD-16): Android vitals is the only field signal; StrictMode in debug; Solver, classification and I/O never on the main thread.
- AR-17 (AD-17): one `StickerAppearance(Color) → {fill, glyph, pattern}` table used by every sticker surface; `MoveButtonBar` for every gesture action; cube semantics description from `CubeState`; layout keyed on `WindowSizeClass`.
- AR-18 (AD-18): `FeatureFlags` port, closed enum `{QUICK_SCAN, PLACEMENT, CASE_RECOGNITION, TEST_ME, CAMERA_SCAN, ADAPTIVE_ASSISTANCE}` → `{OFF, BETA, GA}` set per build type in `build-logic`; no `BuildConfig` checks in features.
- AR-19 (AD-19): one `LastLayerMatcher` (canonical LL frame, OLL by orientation mask under pre-AUF, PLL by permutation under pre/post-AUF and `y^k`, partial views → candidate set) used by Library verification, diagram derivation, FR-28 and 2-look predicates.
- AR-20 (AD-20): one pure `PlaybackController` (play, pause, step, back, replay, speed) for Demonstrations, Solution Playback and the Library; never checkpointed; Solution Playback text only from `MoveNote.of(move)`.
- AR-21 (Ownership Matrix): single writers — reducer (session states, Checkpoint, Attempts), `CompleteLesson`/`PassSkipCheck`/`PlaceFromScan`/`ResetProgress` (Stage progress), `SetAlgorithmStatus`, `UpdateCalibration`/`ResetCalibration`, `UpdatePreference`.
- AR-22 (Deferred / open, carried into stories): reference devices ARCH-OQ-1 (closed 2026-10-30; minimum replaced by the Lenovo Tab M10 Plus 3rd Gen, SCP 2026-11-12) and OQ-9 by end of week 2; OQ-3 renderer spike; OQ-7 Quick Scan method; OQ-8 test matrix; OQ-2 2-look set + primary Algorithms; OQ-6 Algorithm data licensing; OQ-1 store name; `RecoveryPlanner` search (bounded IDA*) decided in Epic 4; tutor config values set during curriculum authoring.
- AR-23 (leftover from UX reconcile L14): cut-order item 4 (Library recognition diagrams) has no flag in AD-18's closed enum. [ASSUMPTION: if that cut fires, raise it through `bmad-correct-course` to add a flag; the tile fallback is designed (UX-DR17).]

### UX Design Requirements

UX-DR1: Implement the DESIGN.md color tokens as a Compose Material3 theme: chrome (`surface-base/raised/stage`, `ink-primary/secondary/disabled`, `hairline`, `accent`, `on-accent`) in light and dark; status tokens (`verified`, `likely`, `unknown`, `invalid`) per theme; sticker tokens, `cubie-body`, `sticker-highlight`, `scan-grid`, `sticker-dimmed-overlay` theme-invariant. A contrast test asserts text ≥ 4.5:1 on every surface token, status ≥ 3:1 as icons and ≥ 4.5:1 as chip text, highlight ≈ 7:1 on `cubie-body`. [ASSUMPTION: hex values pending confirmation.]
UX-DR2: Bundle IBM Plex Sans and IBM Plex Mono (no network) and map the roles `display`, `title`, `body`, `label`, `meta`, `notation`, `data` to the M3 type scale; `notation` one step above surrounding text; `data` uses tabular figures; at most one `display` per screen; no all-caps except short status chips. [ASSUMPTION: font pairing pending confirmation.]
UX-DR3: Spacing scale 4/8/12/16/24/32/48dp, `touch-min` 48dp, gutters 16dp compact / 24dp medium+expanded; shapes `xs` 2dp (stickers), `sm` 4dp (keys, inputs), `md` 8dp (cards, buttons, tiles), `lg` 12dp (stage well, sheets), `full` only for certainty chips; no pill buttons, no card shadows.
UX-DR4: Cube stage component: `surface-stage` well; top toolbar Undo · Redo · Reset (icon buttons, TalkBack labels, `ink-disabled` when disabled); frame label VIRTUAL / EXPECTED (dashed `expected-badge`) / VERIFIED (status icon), none on Solution Playback, which shows Certainty only through the certainty chip below the stage (SCP 2026-11-19); double-tap reset; a View rotated badge with a one-tap Reset view in the top-left overlay (after the frame label when shown) while the view is off home, and face letters beside the visible faces (SCP 2026-11-18); dimming of off-step pieces with Keep pieces lit; SEEN strip of observed face thumbnails with differing stickers outlined during a mismatch; a Partial observation is never drawn as a whole cube; on Solution Playback a toolbar view switch between the 3D cube and the 2D net, labeled with the view it switches to (SCP 2026-11-17).
UX-DR5: Move key bar: `move-key` buttons labeled in `notation` (`U`, `U'`, `U2`…), press inversion, ⟳ x/y/z group, "more" for wide and slice turns (also reachable by gesture, UX-DR26), long-press plain-language name, light haptic tick on commit, labels never change with View Rotation; horizontal scrollable on compact, vertical grid on expanded; in Physical-cube Mode shown only after *I did something else*.
UX-DR6: Move arrow overlay and piece highlight: curved `sticker-highlight` arrow following the turning layer (Demonstrations, hint rung 2, and the current Move of Solution Playback in 3D, SCP 2026-11-17) with the Move in `notation` at its tip (static when reduce motion is on); highlight = 3dp `sticker-highlight` outline with 1dp `cubie-body` inner stroke; stickers never recolored.
UX-DR7: Lesson panel in this order: Objective (`title`) → step card → Teaching Mode switcher → Hint (secondary) + **Next** (`button-primary`; disabled = `hairline` fill + `ink-disabled` text). Step card shows Goal, Keep, Watch before the Move and Notice after it, with Moves as `notation-chip`s; the UI label *Keep* renders schema field `preserve`.
UX-DR8: Teaching Mode switcher: segmented control (*Show me*, *Guide me*, *Test me*, *Let me try*), active segment `accent`; switching keeps state and progress; renders `TutorDecision.reveal` only; *Test me* hidden when `TEST_ME` is OFF; last mode remembered.
UX-DR9: Hint button labeled **Hint (n/3)**; each tap moves one rung (clue → highlighted target or Move arrow → full Demonstration); resets on a new Objective.
UX-DR10: Assistance line: one `meta` line under the step card explaining why help changed, with *Back to my usual help*; never switches Teaching Mode without a tap; absent when `ADAPTIVE_ASSISTANCE` is OFF.
UX-DR11: Recovery prompt: inline in the lesson panel, never a dialog; *Undo*, *Guide me back*, *Try again* in that order; pending copy "Finding the shortest way back…", plan copy ("3 moves — your cross stays") with preserved pieces highlighted, no-plan copy "I can't find a short way back. Undo, or start this step again."
UX-DR12: Physical controls: **Done** stays primary (even under a recovery prompt); secondary *I did something else*; overflow with *My cube doesn't match* and *I'm not sure what I did*; *Quick Check* camera icon at Objective boundaries, Stage milestones and after mismatch reports, and available at any step including mid-Algorithm (SCP 2026-11-20), never required.
UX-DR13: Certainty chip (`rounded.full`, icon + label + status color: ✓ *Verified*, ≈ *Likely*, ? *Unknown*), tappable to explain its evidence, never a percentage; Expected badge (dashed outline, `EXPECTED`) on every rendering of a predicted physical state; no chip while Expected-only.
UX-DR14: Playback transport shared by Demonstrations, Solution Playback and Algorithm detail: |◀ ◀ ▶/❚❚ ▶ ▶|, speed 0.5×/1×/2×, step counter in `data`, current Move in large `notation` with its MoveNote line in `meta`, current Move in the strip marked by an `ink-primary` outline, scrubbing snaps to Move boundaries; the Move strip and a visible scrubber are shown (read-only strip, no scrubber in Physical-cube Mode), with move pictures (UX-DR32) on the current Move label and strip chips when *Move pictures* is on (SCP 2026-11-17).
UX-DR15: Scan viewfinder: full-bleed preview, 3×3 grid in `scan-grid` at 70% with 1dp dark halo, auto-capture after ~600 ms aligned and steady, manual shutter, face prompt naming the center color with a glyph swatch (the up color only on a re-prompt, SCP 2026-11-16 / Story 10.14), mini net progress, inline quality coaching, wrong orientation → re-prompt not error; Quick Scan variant with a Y-shaped corner guide and "1 / 2" counter (SCP 2026-11-20, Story 10.18: the cube's real edges and corners are detected in the frame, so the Learner never aligns with a sketch, and each capture gets a visible cue), unreadable faces hand off to the per-face grid; portrait camera with rotating overlays in landscape.
UX-DR16: Sticker review net (Scan review and Editor): uncertain stickers pre-selected with a dashed `accent` ring and "?" badge; tap a sticker, then a color; invalid pieces outlined in `status-invalid` with labels; non-local errors on their own lines (twisted corner, flipped edge, swapped pieces); **Accept** disabled until every uncertain sticker is resolved and the state is valid. *(SCP 2026-11-20)* Paint mode by long-pressing a palette color (#249); in the Editor, any face rotation, neighbour-center bands, face-tab ✓ and warning dot, face-done line, specific count and piece messages, and no piece errors before every face is entered (#253).
UX-DR17: Algorithm tile (diagram of U face + side bands derived from state, Case name in `label`, Algorithm in `notation`, status label *Learning*/*Learned*, nothing for *Not started*); fallback tile without a diagram; neutral M3 filter chips (selected = `surface-raised` + ✓, never violet); no stars, scores or badges.
UX-DR18: Stage row (number in `data`, name in `title`, state text + icon: Locked, In progress, Completed, ◇ Skipped, ◇ Skipped by placement, ◆ Mastered in `accent`), reused for the 2-look mini-Course; Mastery progress line ("Mastery: 1 of 2 solves · 1 with reduced help") with a two-segment indicator; Trend row (dot sparkline of hints per attempt in date order, latest emphasized, attempt count in `data`, no totals, no red).
UX-DR19: Beta label (tiny outlined `BETA` tag) shown from the flag, with per-feature tooltip copy; flag OFF hides the entry point and every cross-link, with no dead buttons; the manual route always one tap away.
UX-DR20: Milestone moment only for the first solve and each Stage mastered: ≤ 1.2 s burst of the six sticker colors, then a `display` line, no loops or sound; in Physical-cube Mode only after a Quick Check or explicit *Yes, it's solved*; static frame under reduce motion.
UX-DR21: Color-blind glyphs W Y R O G B on every sticker surface from `StickerAppearance` (glyph in `cubie-body` on light stickers incl. orange, `sticker-white` on dark ones, legible at 16dp); a Settings toggle offered at first launch and first scan; patterns deferred (field empty).
UX-DR22: Information architecture: Welcome (first launch only) and a 4-tab bottom bar (Learn, Solve, Library, You) that becomes a navigation rail at medium/expanded widths; Lesson, Scan and Playback are full-screen task flows with a top app bar; never more than one modal level; the Scan sub-flow returns to its caller. [ASSUMPTION: 4-tab IA pending confirmation.]
UX-DR23: Responsive Lesson layout per window class: compact stacked (stage ≈ 45% height, min 280dp, key bar on stage bottom edge); medium side by side (stage ≈ 55% left, vertical key grid); expanded side by side with the panel capped at 560dp; Library 4 columns at expanded; orientation change never changes session state.
UX-DR24: Voice and copy: all strings as content keys; Goal/Keep/Watch/Notice; fixed Certainty wording each with its next action; the Do/Don't table and UI-label↔Glossary map from EXPERIENCE.md; no exclamation marks except milestones; mistakes in neutral ink, never red.
UX-DR25: State-pattern copy and treatments from EXPERIENCE.md State Patterns, each implemented where its feature lands (resume notes, session paused, content changed, Objective met variants, mismatch ladder, Test me outcomes, error-category copy, stale scan, solver working/timeout/tables building, invalid entry, permission denied, Placement outcomes (with cube reading and reason, SCP 2026-11-20), re-placement, entry level, Recognize not-at-LL/ambiguous/no-fit/last-layer-solved, filter empty, progress reset).
UX-DR26: Gestures: one-finger drag starting on a sticker along a row/column previews that layer's Move (an outer face or a middle slice) and commits it on release (UX-DR33); a parallel two-finger drag starting on the cube previews and commits the wide Move; a one- or two-finger drag starting off the cube = View Rotation; below threshold nothing happens; two-finger twist = z; x/y via ⟳ keys [ASSUMPTION]; Cube Rotation animates 200 ms and briefly labels the new front color; The View rotated badge is shown while the view is off home (UX-DR34).
UX-DR27: Haptics: light tick on each committed Move, soft confirm on Objective met, nothing on a mistake; all switchable off in Settings.
UX-DR28: Banned patterns enforced in review: shake on error, red flash for a Learner mistake, countdown timers (outside P1 drills), streaks/points/push notifications/badges, auto-advance past a Notice, carousels.
UX-DR29: Accessibility floor: cube semantics summary ("White cross solved; 2 of 4 first-layer corners placed") plus a *Read faces* action; every Certainty change announced; Move keys announce plain names; View Rotation arrow buttons in the stage overflow; the previewed Move announced by name, and "View rotated" / "View reset" announced; dynamic type to 200% with panel reflow and no truncation; focus order frame label → cube description → Move key bar → lesson panel → mode switcher; reduce motion makes Demonstrations step, milestones static, camera transitions snap.
UX-DR30: Themes: light and dark designed equally, follow the system, with a Settings override.
UX-DR31: Back: leaving a Lesson is immediate with no confirmation (everything is committed and checkpointed first).
UX-DR32: Move picture: an ink-only glyph per Move generated in code (3×3 face, moving layer shaded; curved arrow R/L/U/D, round F/B seen from the front with B's arrow behind the grid, straight M/E/S; prime reverses, half turn adds a superscript 2; wide and x/y/z designs), decorative for TalkBack; *Move pictures* Settings toggle next to color-blind glyphs, default on (SCP 2026-11-17).
UX-DR33: Drag preview: once a drag locks, the layer turns with the finger (clamped to a quarter turn) and is highlighted without replacing Lesson emphasis, and a ghost arrow carries the Move in notation (cube frame) while any teaching arrow is hidden; reversing through the start flips the Move; lift commits through the Move key path (also when lifted off the stage) and the turn continues from the ghost angle; returning to within the lock distance of where the drag began and lifting cancels with a snap back and no tick; TalkBack announces the previewed Move (SCP 2026-11-18).
UX-DR34: View-rotated badge and face labels: a "View rotated" badge with a Reset view action in the stage's top-left overlay (after the frame label when shown) while the view is off home; reset animates home (jumps under reduce motion); face letters U/D/F/B/R/L in notation outside the cube's silhouette beside each face that clearly faces the camera, never on stickers, while rotated (SCP 2026-11-18).

### FR Coverage Map

FR-1: Epic 1 (buttons, 2D net, all Move types) · Epic 5 (3D, gestures, 250 ms animation) · Epic 12 (drag preview, slices and wide Moves by gesture, 12.16)
FR-2: Epic 5 — View Rotation independent of Cube State · Epic 12 (view-rotated badge, Reset view, face labels, 12.17)
FR-3: Epic 1 (free-cube undo/redo/reset) · Epic 2 (in-session undo/redo, reset confirmation) · Epic 8 (physical undo)
FR-4: Epic 2 (Lesson schema, path tests) · Epic 7 (Stage gating, all Stages, skip-ahead check) · Epic 14 (entry level, Story 14.1)
FR-5: Epic 2 — four Teaching Modes
FR-6: Epic 2 (explained Demonstrations on the net) · Epic 5 (3D arrows and highlights)
FR-7: Epic 8 — confirm physical Moves, Expected label
FR-8: Epic 8 — switch modes mid-Lesson
FR-9: Epic 8 (honest mismatch, no-camera ladder) · Epic 10 (Quick Check rung, v1.0, flag-gated)
FR-10: Epic 4 — Diagnosis of Error Categories
FR-11: Epic 4 — Hint Ladder
FR-12: Epic 4 — retry, undo, Guided recovery
FR-13: Epic 4 — rule-based adaptive assistance
FR-14: Epic 1 (`CubeValidator` rules) · Epic 3 (Manual Cube Editor)
FR-15: Epic 3 — offline Solver
FR-16: Epic 3 (Solution Playback) · Epic 8 (physical follow-along) · Epic 10 (Quick Check in playback, v1.0, flag-gated) · Epic 12 (3D/2D view switch, move pictures and Move strip; Stories 12.14–12.15)
FR-17: Epic 9 — 2-look LL mini-Course
FR-18: Epic 9 — Algorithm Library · Epic 14 (practice on a real cube, Story 14.2)
FR-19: Epic 6 (snapshots, Checkpoints, attempts, preferences) · Epic 7 (progress reset) · Epic 9 (Algorithm Status) · Epic 10 (calibration reset, v1.0, flag-gated) · Epic 12 (playback view and Move pictures preferences)
FR-20: Epic 7 — Independent practice, mastery, trend
FR-21: Epic 10 — Guided 6-face Scan (v1.0, flag-gated)
FR-22: Epic 11 — Quick Scan (v1.0, flag-gated) · Epic 10 (switch to the Guided Scan, Story 10.17)
FR-23: Epic 10 — validate and correct a Scan Result (v1.0, flag-gated)
FR-24: Epic 10 — Color Calibration (v1.0, flag-gated)
FR-25: Epic 10 — scan to solve (v1.0, flag-gated)
FR-26: Epic 10 — Quick Check (v1.0, flag-gated) · Epic 14 (any step, Stories 14.2–14.3)
FR-27: Epic 11 — Placement (v1.0, flag-gated)
FR-28: Epic 11 — Case recognition (v1.0, flag-gated)
FR-29: not covered — P1, out of MVP scope (deferred in PRD §6.2 and spine Deferred)
FR-30: Epic 15 — method-aware solutions (v1.0, SCP 2026-11-20)

NFRs: NFR-1/2 Epics 5, 12 · NFR-3 Epic 3 · NFR-4 Epics 1, 2, 5, 8 · NFR-5/8 Epic 1 (build checks), Epic 12, Epic 13 (privacy facts and public policy) · NFR-6 Epic 6 · NFR-7 Epics 1, 5, 12 (+ every UI story) · NFR-9/11 Epics 7, 12 · NFR-10 Epic 2 · NFR-12 Epic 1 (gates), all `:core:*` stories · NFR-13 Epic 12 (+ every UI story).

*(SCP 2026-11-25 retire-v1-1: v1.1 is retired; v1.0 scope.)*

## Epic List

### Epic 1: Turn and verify a cube (P0.1, weeks 1–2)
A Learner can turn a correct, validated cube on a 2D net with buttons, undo and redo, and the app can tell a helping Move from a harmful one for one Objective. The developer has a gated repo, and the week-2 spikes and decisions are made.
**Absorbs:** E01, E02 (validation), E03 (2D net), spikes.
**FRs covered:** FR-1 (buttons), FR-3 (free cube), FR-14 (validation rules). **Also:** NFR-4, NFR-5, NFR-8, NFR-12, AR-1–AR-6, AR-11, AR-15, AR-17, AR-18, UX-DR1–5, UX-DR21, UX-DR30.

### Epic 2: Learn with a guided Lesson (P0.2, weeks 3–5)
A Learner can open a data-defined Lesson, see its Objective, watch an explained Demonstration, and work through it in any of the four Teaching Modes until the Completion Predicate is met. Stages 1–3 are authored.
**Absorbs:** E04 (schema + engine), part of E08 (shared playback).
**FRs covered:** FR-3 (in-session), FR-4 (schema), FR-5, FR-6. **Also:** NFR-4, NFR-10, AR-7, AR-10, AR-20, UX-DR7, UX-DR8, UX-DR14, UX-DR22, UX-DR24, UX-DR31.

### Epic 3: Solve any cube offline (P0.S, weeks 3–6, parallel track)
A Learner can enter any cube's colors, get a verified Solution from the in-house Solver, and step through it with a plain note per Move.
**Absorbs:** E02 (Solver), E08 (Solution Playback).
**FRs covered:** FR-14 (editor), FR-15, FR-16. **Also:** NFR-3, NFR-11, AR-12, AR-20, UX-DR16, UX-DR25.
*(SCP 2026-11-24 github-issue-triage: Story 3.9, cold solver preparation, #104, is v1.1 scope and reopens the epic; it never gates the v1.0 release.)* *(SCP 2026-11-25 retire-v1-1: v1.1 is retired; v1.0 scope. Story 3.9 is v1.0 scope; it does not gate the v1.0.0 tag; Epic 3 is in-progress until 3.9 is done or re-triaged. This supersedes the 2026-11-24 "v1.1 scope" wording.)*

### Epic 4: Get help when I go wrong (P0.2, weeks 4–5)
When a Learner makes a mistake, the tutor names what happened with honest Certainty, offers the next hint rung, and leads back by undo or the shortest Guided recovery, with assistance adapting to their history.
**Absorbs:** E05.
**FRs covered:** FR-10, FR-11, FR-12, FR-13. **Also:** AR-9, UX-DR9, UX-DR10, UX-DR11, UX-DR13.

### Epic 5: Turn the cube in 3D (P0.2–P0.3, weeks 5–7)
A Learner can turn and inspect a smooth 3D cube with gestures, rotate the view freely, and see Demonstrations with arrows and highlights, with every action also reachable without gestures or sight.
**Absorbs:** E03 (3D + gestures), part of E09.
**FRs covered:** FR-1 (3D, gestures), FR-2, FR-6 (3D overlays). **Also:** NFR-1, NFR-2, NFR-4, NFR-7, AR-8, AR-17, UX-DR6, UX-DR26, UX-DR27, UX-DR29.

### Epic 6: Pick up where I left off (P0.3, week 6)
A Learner's Lesson survives closing the app or process death, their attempts are recorded, and their preferences stick. The free cube also gets one event owner (Story 6.6), with no persistence.
**Absorbs:** E07.
**FRs covered:** FR-19 (snapshots, Checkpoints, attempts, preferences). **Also:** NFR-6, AR-14, AR-21, UX-DR25, UX-DR30.

### Epic 7: Complete the Course to a first solve and mastery (P0.3, weeks 7–9)
A Learner can progress through all seven Stages to a first solve, practice on fresh Scrambles to mastery, see their trend, skip ahead when they already know a Stage, and a pilot build reaches closed testing.
**Absorbs:** E04 (curriculum), progress parts of E07.
**FRs covered:** FR-4 (gating, skip-ahead), FR-19 (progress reset), FR-20. **Also:** NFR-9 (pilot pipeline), AR-5, UX-DR18, UX-DR20, UX-DR22.

### Epic 8: Use my real cube (P0.3, weeks 8–9)
A Learner can follow Lessons and Solution Playback on a physical cube, with the app honestly tracking the Expected State and handling mismatches without a camera.
**Absorbs:** E06.
**FRs covered:** FR-3 (physical undo), FR-7, FR-8, FR-9, FR-16 (physical follow-along). **Also:** NFR-4, AR-7, AR-9, UX-DR12, UX-DR13.

### Epic 9: Faster last layer and the Algorithm Library (P0.3, weeks 8–9)
A Learner can browse and watch all 78 verified OLL/PLL Algorithms, track their own Algorithm Status, and take the 2-look LL mini-Course to mastery.
**Absorbs:** E11.
**FRs covered:** FR-17, FR-18, FR-19 (Algorithm Status). **Also:** AR-19, AR-23, UX-DR17.

### Epic 10: Scan my cube (v1.0, flag-gated)
A Learner can scan a cube face by face, correct and calibrate it, solve it from the scan, and verify physical Moves with a Quick Check. Built on `main` with every camera flag OFF in release; each feature reaches users only when the PO raises its flag at a release (Story 10.11). Restored from `post-launch/v1.1-camera-recognition.md` by `sprint-change-proposal-2026-11-14-v1-1-camera.md`. *(SCP 2026-11-20: moves into v1.0 as the dependency of #198, #208, #249, #251, #252; Story 10.11 raises `CAMERA_SCAN` for the v1.0 release.)*
**FRs covered:** FR-21 – FR-26, plus the Quick Check rung of FR-9 and FR-16, and the calibration reset of FR-19. **Also:** recognition NFRs, NFR-5, NFR-8, NFR-11, NFR-12, AD-9, AD-13, AD-18, AR-13, UX-DR15, UX-DR16, UX-DR19 – UX-DR22, UX-DR25.
**Order:** 10.9 → 10.10 → UX pass → 10.12 → 10.13 → 10.1 → (10.6 ∥ 10.2 → 10.3 → 10.14 → 10.4 → 10.5 → 10.15 → 10.7) → Epic 11 → 10.8 → 10.11; 10.16 after 10.14; 11.1 → 10.18 (Quick Scan finds the cube's real edges and corners, #195) → 10.17 (after 10.16 too) → 10.19 → 10.22 → (10.20 ∥ 10.23) → 10.21 *(SCP 2026-11-22 quick-scan-reliability: 10.22 before the fixture session)* *(SCP 2026-11-22 quick-scan-sticker-reader: 10.23, the shadow sticker reader, after 10.22; it does not block 10.20, 10.21 or 10.11)* *(SCP 2026-11-22 quick-scan-corners-and-solver-copy: 10.21 → 10.24 (retake a corner, #299) → 10.25 (start from whichever corner is shown, #302); 10.26 (fill a few unread stickers from cube rules, #301) only after the 10.21 BETA call. PO Q-h: the 10.11-n run that raises `QUICK_SCAN` does not wait for 10.25, and no new story is added to its dependencies.)* *(SCP 2026-11-21 quick-scan-side-face-accuracy, #274: 10.20 and 10.21 do not block Story 10.11, which raises `CAMERA_SCAN` and `PLACEMENT` with `QUICK_SCAN` OFF; Quick Scan is raised in a later 10.11-n after 10.21's BETA call)* *(SCP 2026-11-24 camera-release-timeline: 10.21's BETA call (2026-11-24) left `QUICK_SCAN` OFF on this pipeline; 10.24–10.27 are superseded by the continuous facelet scan approach (PR #363) and do not start. The new Quick Scan port stories, written by the spike session's SCP, come before the 10.11 run that raises `QUICK_SCAN`. 10.11 no longer waits for 12.5: its first run, release 0.11.0, raises `CAMERA_SCAN` to GA and `PLACEMENT` to BETA, and `QUICK_SCAN` to BETA only when its preconditions hold (Story 10.11); 12.5 then promotes a new v1.0.0 tag with the same levels.)* *(SCP 2026-11-24 continuous-quick-scan: 10.24–10.27 are dropped; the continuous Quick Scan port chain **10.30 → 10.31 → 10.32 → 10.33** replaces them and runs before the Story 10.11 run that raises `QUICK_SCAN`.)*; all v1.0 scope (SCP 2026-11-20) *(SCP 2026-11-15; 10.14 added by SCP 2026-11-16; 10.5 split into 10.5 and 10.15 by SCP 2026-11-16 main-app entry points)*. *(SCP 2026-11-24 github-issue-triage: 10.28 (the recorder's count check, #337) after 10.20, independent of 10.27, before any new corner-fixture recording; it blocks neither 10.21 nor 10.11. 10.29 (Solution Playback shows the Lesson's Quick Check result, #326) is v1.1 scope, after 10.7. #321, #323, #325 and #313 are v1.0 issue fixes against Stories 10.15, 10.3 and 10.2.)* Device sessions: A = 10.10; A2 = 10.12; B = 10.2 and 10.4 acceptance (10.4 partial); B2 = 10.14; B3 = 10.5 (main app; re-runs the 10.4 checks not done on a device, #166); C = 11.1, 11.3 capture and 10.8.
**Main-app rule (SCP 2026-11-16 main-app entry points):** a camera story is accepted on the main app build, reached from its real entry point. A debug-only launcher is never a story's device-session gate, and debug raises a flag above OFF only once that flag's entry point is wired in the main app (AD-15, AD-18).
**Never blocks** tester-feedback fixes or Stories 12.3, 12.4, 12.5 and 13.6: camera stories merge only with release camera flags OFF, and Story 10.9's gate keeps the release free of camera code and permissions. *(Superseded by SCP 2026-11-20: for v1.0, Story 10.11 raises `CAMERA_SCAN`, `QUICK_SCAN` and `PLACEMENT` before Stories 12.3–12.5, and the Story 10.9 gate then checks the flags-ON release instead.)* *(SCP 2026-11-21 quick-scan-side-face-accuracy: `QUICK_SCAN` leaves this first raise and follows Story 10.21.)* *(SCP 2026-11-24 camera-release-timeline: Story 10.11 now runs after Stories 12.3, 12.4 and 13.6 and before 12.5. Its first run is release 0.11.0, and Story 12.5 promotes a v1.0.0 tag cut from main with the same flag levels.)*

*(SCP 2026-11-25 retire-v1-1: v1.1 is retired; v1.0 scope.)*

### Epic 11: Scan shortcuts: Quick Scan, Placement and Case recognition (v1.0, flag-gated)
First on the PRD cut order (Quick Scan → beta, then Case recognition, then Placement). Each ships behind its AD-18 flag and can be turned OFF without dead UI. *(SCP 2026-11-20: Quick Scan and Placement move into v1.0 with #198, #251 and #252.)*
**FRs covered:** FR-22, FR-27, FR-28. **Also:** recognition NFRs, NFR-11, AD-6, AD-9, AD-10, AD-18, AD-19, UX-DR19, UX-DR25.

*(SCP 2026-11-25 retire-v1-1: v1.1 is retired; v1.0 scope.)*

### Epic 12: Release-ready for learners and reviewers (P0.6, weeks 15–16)
The app meets every release gate. It is accessible, fast and stable on the reference devices, the pilot's findings are fixed, and the store listing and public repo are ready for a hiring manager's five-minute look.
**Absorbs:** E09, PRD §7.
**FRs covered:** none new (verifies all), plus FR-16 and FR-19 additions in 12.14–12.15, and FR-1/FR-2 gesture and view changes in 12.16–12.17. **Also:** NFR-1, NFR-2, NFR-7, NFR-8, NFR-9, NFR-11, NFR-13, AR-3, AR-5, AR-16, UX-DR4, UX-DR6, UX-DR14, UX-DR28, UX-DR5, UX-DR26, UX-DR29, UX-DR32, UX-DR33, UX-DR34, Flow 6.
**Also carries** the pre-pilot stories 12.6–12.10 (Cubentia rebrand; Epic 8 retrospective fixes), which run before Story 7.8 (`sprint-change-proposal-2026-11-08-cubentia-and-pre-pilot.md`), and 12.11–12.12 (Epic 9 seams; 2-look rules), also before 7.8 (`sprint-change-proposal-2026-11-10-two-look-rules-and-epic-9-seams.md`), and 12.13 (durable session end; Story 12.11 deferrals), also before 7.8 (`sprint-change-proposal-2026-11-10-session-end-deferrals.md`). It also carries 12.14 (Solution Playback in 3D or on the 2D net, #175) and then 12.15 (Move pictures and the Move strip, #176), v1 release scope that runs before Story 12.3 and the store screenshots of Story 12.5 (`sprint-change-proposal-2026-11-17-playback-view-and-move-glyphs.md`). It also carries 12.16 (drag preview, #206) and 12.17 (view-rotated badge, #207), which run before 12.3 (`sprint-change-proposal-2026-11-18-drag-preview-and-view-rotated.md`).
**Epic 13** (public website and privacy policy, `sprint-change-proposal-2026-11-13-cubentia-site.md`) runs before Stories 12.3, 12.4 and 12.5. Story 12.3 depends on 13.6, and Story 12.5 on 13.4 and 13.5. Epics 10 and 11 (v1.1 camera, flag-gated) run in parallel with Epics 12 and 13 and never gate them (`sprint-change-proposal-2026-11-14-v1-1-camera.md`). *(Superseded by SCP 2026-11-20: Stories 10.11, 10.17, 10.18 and Epics 14–15, plus the #249/#251/#252/#253 fixes, are v1.0 scope and run before Stories 12.3, 12.4 and 12.5.)* *(SCP 2026-11-24 camera-release-timeline: Story 10.11 runs after 12.3, 12.4 and 13.6 and before 12.5; 12.5's production promotion carries the camera build as a v1.0.0 tag.)* *(SCP 2026-11-25 retire-v1-1: v1.1 is retired; v1.0 scope.)*
**Device batch** (reference devices connected after the device-free stories; `sprint-change-proposal-2026-11-10-session-end-deferrals.md`): Stories 3.8, 1.11 and 12.2, plus the Story 12.1 physical pass in `docs/accessibility-manual-check.md`. Story 3.7 stays in `review` until 3.8 passes (its last AC), and Story 12.1 stays in `review` until that physical pass is recorded.
**Reference devices (SCP 2026-11-12, `sprint-change-proposal-2026-11-12-lenovo-minimum-reference.md`):** minimum Lenovo Tab M10 Plus 3rd Gen (TB128XU, Android 13 / API 33), mid Samsung Galaxy S24 FE. The Galaxy Tab S7 FE 5G, the minimum until 2026-11-12, is kept as an additional compatibility device, and its 3.8, 1.11 and 12.2 results stay as dated evidence. The Lenovo was measured with the 3.8 and 12.2 methods and meets every threshold (`docs/benchmarks.md` § Additional device: Lenovo Tab M10 Plus 3rd Gen); its limitations are tracked in #119.

### Epic 13: Cubentia on the web, with a privacy policy (pre-pilot, 2026-11-13)
Anyone can open Cubentia's public website, read a privacy policy that states exactly what the app does with data, and find support. Play Console has a privacy-policy URL that never changes. The site lives in its own repository, `hebelmx/cubentia-site`, published by GitHub Pages. Its privacy page is checked in CI against `docs/privacy-facts.json`, which this repository's build derives from the release manifest.
**Absorbs:** the privacy-policy page of Story 12.5 AC2 (PRD §7 store readiness).
**FRs covered:** none. **Also:** NFR-5, NFR-7, NFR-8, NFR-10, AD-13, AD-14, AR-4.
**Runs before** Stories 12.3, 12.4 and 12.5 and the #127 closed-testing review (`sprint-change-proposal-2026-11-13-cubentia-site.md`).

### Epic 14: Practice on my real cube and start at my level (SCP 2026-11-20)
A Learner who can already solve picks an entry level instead of proving every Stage, and any Algorithm or Lesson step can be practiced on a real cube with *Done* and a Quick Check at any step.
**FRs covered:** FR-4 (entry level), FR-18 (practice on a real cube), FR-26 (any step), FR-17 (physical 2-look). **Also:** AD-3, AD-10, AD-18, AD-20, UX-DR12, UX-DR18, UX-DR25, Flows 8 and 11. GitHub #208, #209.
**Order:** 14.1 → 14.2 → 14.3. **v1.0 scope** (PO, 2026-11-20): runs before release Stories 12.3, 12.4 and 12.5; camera ACs stay behind `CAMERA_SCAN`, which Story 10.11 raises for v1.0.

### Epic 15: Method-aware solutions (v1.0, SCP 2026-11-20)
A Learner can see a solution shaped by the method they are learning instead of the shortest one, each segment tagged by Stage or Case.
**FRs covered:** FR-30. **Also:** AD-8 (unchanged), AD-19, AD-20, AD-21, NFR-3, NFR-12. GitHub #210.
**Order:** 15.1 → 15.2 → 15.3. **v1.0 scope** (PO, 2026-11-20): runs before release Stories 12.3, 12.4 and 12.5.

**SCP 2026-11-20 v1.1 promotion** (`sprint-change-proposal-2026-11-20-v1-1-promotion.md`): #249, #251, #252 and #253 are built as issue fixes (`/bmad-orchestrate-issues`) against the amended Stories 10.3, 11.2 and 3.5; #198 is Story 10.17; #208 and #209 are Epic 14; #210 is Epic 15. The Lenovo Tab M10 Plus 3rd Gen (TB128XU) camera is the minimum camera spec for every device gate. **Scope move v1.1 → v1.0 (PO, 2026-11-20, ahead of schedule):** all of these ship in v1.0, together with what they depend on: the camera scan and review (`CAMERA_SCAN`, Epic 10), Placement (`PLACEMENT`, Story 11.2), Quick Scan (`QUICK_SCAN`, Story 11.1) and its corner-guide rework (Story 10.18, which detects the cube's real edges and corners in the frame, #195–#197), and Story 10.11, which raises those flags for the v1.0 release from the test-matrix evidence (BETA or GA). Release Stories 12.3, 12.4 and 12.5 run before Story 10.11, which depends on them and is the last PR before the tag. *(SCP 2026-11-24 camera-release-timeline: 12.5 now runs after 10.11. 10.11's flag-raise PR is the last PR before the `v0.11.0` tag; production is a new `v1.0.0` tag cut from main with the same flag levels, gates 3 and 6 are re-run on it, and 12.5 records the gates on the 1.0.0 build.)* *(SCP 2026-11-21 quick-scan-front-light: `QUICK_SCAN` reaches 10.11 only through Stories 10.17 and 10.19 and Story 10.8's matrix; if it stays OFF, 10.11 raises `CAMERA_SCAN` and `PLACEMENT` without it.)* *(SCP 2026-11-24 continuous-quick-scan: superseded — `QUICK_SCAN` now reaches 10.11 through Stories 10.30–10.33 and Story 10.33's PO BETA call; if it stays OFF, 10.11 raises `CAMERA_SCAN` and `PLACEMENT` without it.)* `CASE_RECOGNITION` (Story 11.3) is not a dependency and keeps its own release decision at 10.11. *(SCP 2026-11-24 camera-release-timeline, PO: `CASE_RECOGNITION` stays OFF in 0.11.0.)*

## Epic 1: Turn and verify a cube

A Learner can turn a correct, validated cube on a 2D net with buttons, undo and redo, and the app can tell a helping Move from a harmful one for one Objective. P0.1 exit criterion (addendum §F): a random Scramble applies, renders and validates with no mismatch, and one Completion Predicate tells a helping Move from a harmful one. The spikes and the week-2 decisions close here.

### Story 1.1: Scaffold the gated multi-module project

As the developer,
I want a buildable multi-module repo whose CI enforces the architecture and privacy rules from the first commit,
So that every later story lands on green gates and the portfolio claims are backed by the build.

**Refs:** NFR-5, NFR-8, NFR-12 · AD-1, AD-11, AD-13, AD-14, AD-15, AD-18 · AR-1, AR-2, AR-3, AR-4, AR-18

**Acceptance Criteria:**

**Given** a clean checkout
**When** `./gradlew build` runs
**Then** `:core:domain`, `:core:solver`, `:core:application` (all `kotlin("jvm")`) and `:app` compile with the Stack versions from `gradle/libs.versions.toml`, KGP 2.4.21 pinned on the root classpath, and no `kotlin-android` or kapt
**And** the KSP 2.3.12 vs Kotlin 2.4.21 compatibility is confirmed or a working KSP version is pinned and recorded in the architecture memlog

**Given** a `:core:*` module
**When** a dependency on Android, Compose, Room, CameraX or Hilt is added
**Then** the build fails through a `build-logic` convention check (AD-1)

**Given** a PR to `main`
**When** CI runs on GitHub Actions
**Then** build, Spotless/ktlint, Android lint, JVM tests and Kover (≥ 80% on `:core:*`) must pass; detekt runs non-blocking
**And** a manifest check fails on `INTERNET`, on any runtime permission besides `CAMERA`, on `allowBackup` not false, or on a dependency from the analytics/ads/crash-SDK denylist (AD-13)

**Given** the `FeatureFlags` port in `:core:application` with the closed enum from AD-18
**When** debug and release builds are assembled
**Then** each build type gets its flag levels from `build-logic`, and a test confirms no feature code reads `BuildConfig` directly

**Given** the scaffold is merged
**When** the developer runs `bmad-project-context` (refresh)
**Then** `AGENTS.md` lists the real `./gradlew` invocations in place of the TODO

### Story 1.2: Model the Cube State and face Moves

As a Learner,
I want the app's cube to behave exactly like a real cube,
So that everything I learn on screen transfers to my own cube.

**Refs:** FR-1 · NFR-4, NFR-12 · AD-2, AD-12 · AR-6

**Acceptance Criteria:**

**Given** the solved home state (WHITE on U, GREEN on F, RED on R)
**When** any face Move (U R F D L B, quarter or half, either direction) is applied
**Then** a new immutable `CubeState(cubies, orientation)` is returned and the input is unchanged

**Given** Kotest property tests with pinned seeds
**When** they run on random states and Move sequences
**Then** M⁴ = identity, M·M⁻¹ = identity, and any sequence followed by its inverse = identity

**Given** a state whose only difference from solved is `orientation`
**When** `isSolved` is evaluated
**Then** it returns true (orientation is ignored)

**Given** the `:core:domain` cube package
**When** Kover runs
**Then** it has the highest line coverage of any package and at least 90% [ASSUMPTION: concrete threshold for "strongest coverage" in NFR-4]

### Story 1.3: Parse and format every Move type in Notation

As a Learner,
I want Notation like `R U R' U'`, `M2`, `r` and `y` to mean exactly one thing everywhere,
So that Demonstrations, buttons and Algorithms never disagree.

**Refs:** FR-1 · NFR-4 · AD-2, AD-7 · AR-11

**Acceptance Criteria:**

**Given** `Notation.parse`
**When** it receives face, wide (u d l r f b), slice (M E S) and Cube Rotation (x y z) tokens with `'` and `2` suffixes
**Then** it returns `Move` values, or a sealed parse error naming the bad token (never an exception)

**Given** a Cube Rotation
**When** it is applied
**Then** only `orientation` changes, and slice/wide Moves equal their face-turn equivalent plus a rotation (AD-2)

**Given** two Moves with the same effect, such as `R2` and `R2'`
**When** they are compared
**Then** they are equal, and `Notation.format` emits the canonical form

**Given** the WCA slice convention
**When** `M`, `E` and `S` are applied
**Then** they turn like `L`, `D` and `F` respectively, verified against sticker fixtures

### Story 1.4: Validate a 54-sticker cube

As a Learner,
I want to be told in plain words why a cube I entered can't exist,
So that I can fix the sticker instead of guessing.

**Refs:** FR-14, FR-23 · NFR-4, NFR-10 · AD-2, AD-6, AD-12 · AR-6, AR-10 · UX-DR24

**Acceptance Criteria:**

**Given** a checked-in diagram fixture of the URFDLB row-major `Facelets` layout
**When** `Facelets` are converted to and from `CubeState`
**Then** round-trips match the fixture for the solved state and 1,000 random legal states

**Given** `CubeValidator.validate(Facelets)`
**When** the input has wrong color counts, an impossible piece, a twisted corner, a flipped edge or a parity violation
**Then** it returns a sealed `ValidationResult` failure for that rule, with the offending positions where the rule is local and a `Text` key for the plain-language reason

**Given** 54 colors that are all filled in
**When** they form an illegal state
**Then** validation still fails ("54 colors entered" is never valid by itself)

**Given** the codebase
**When** any module needs a `CubeState` from `Facelets`
**Then** `CubeValidator` is the only route (no other public constructor path)

### Story 1.5: Generate Scrambles

As a Learner,
I want a fresh random Scramble when I practice,
So that I'm tested on cubes I haven't memorized.

**Refs:** FR-4, FR-20 · NFR-4 · AD-11 · AR-15

**Acceptance Criteria:**

**Given** the `NewScramble` use case and an injected `RandomSource`
**When** it is called
**Then** it returns an explicit `List<Move>` from home that yields a legal state

**Given** a pinned seed in a test
**When** `NewScramble` runs twice
**Then** it returns the same Moves, and no seed is ever persisted in place of Moves

**Given** 10,000 generated Scrambles
**When** each is applied and validated
**Then** every resulting state is valid and the distribution covers deep Scrambles [ASSUMPTION: ≥ 25 random face turns]

### Story 1.6: Turn a free cube on a 2D net with buttons

As a Learner,
I want to turn a cube with buttons and undo, redo or reset my turns,
So that I can explore Moves before any Lesson.

**Refs:** FR-1, FR-3 · NFR-4, NFR-7 · AD-4, AD-17, AD-20 · AR-8, AR-17 · UX-DR1, UX-DR2, UX-DR3, UX-DR4, UX-DR5, UX-DR21, UX-DR30

**Acceptance Criteria:**

**Given** the app theme
**When** the free cube screen renders in light or dark (following the system)
**Then** it uses the DESIGN.md tokens, bundled IBM Plex fonts and the spacing/shape scale, and sticker colors are identical in both themes

**Given** `Net2DRenderer` behind the `CubeRenderer` port
**When** any Move is committed from the Move key bar (every face, wide, slice and Cube Rotation key)
**Then** `renderedFacelets()` equals the committed state's `Facelets` after the presentation queue drains

**Given** a pure `MoveHistory` in `:core:domain`
**When** the Learner taps Undo, Redo or Reset on the cube stage toolbar
**Then** undo restores the exact previous state, redo restores the undone state, a new Move clears redo, and reset returns to solved without a confirmation (no Lesson progress at stake)

**Given** `StickerAppearance`
**When** color-blind glyphs are switched on in a debug toggle
**Then** every sticker on the net shows its W Y R O G B glyph with the DESIGN.md contrast rules

**Given** a Move key
**When** it is long-pressed or reached by TalkBack
**Then** it announces its plain name ("R prime: right face counter-clockwise") and its touch target is ≥ 48dp

### Story 1.7: Tell a helping Move from a harmful one (Objective proof of concept)

As a Learner,
I want the app to know whether my last Move brought me closer to the white cross,
So that later Lessons can coach me instead of only checking the end state.

**Refs:** FR-4, FR-5, FR-10 · AD-6 · AR-10

**Acceptance Criteria:**

**Given** a color-anchored, orientation-invariant `whiteCrossSolved` predicate with `preservedPieces` and an `ObjectiveProgress` measure
**When** it is evaluated on solved, scrambled and partly solved fixtures, in all 24 orientations
**Then** satisfaction and progress values are identical across orientations

**Given** a fixture one Move from the cross
**When** the helping Move and a harmful Move are each applied
**Then** `ObjectiveProgress` increases for the helping Move and decreases (or stays) for the harmful one

**Given** the free cube from Story 1.6
**When** a debug overlay is enabled
**Then** it shows the predicate result after each Move, demonstrating the P0.1 exit criterion end to end on a random Scramble with no mismatch

### Story 1.8: Spike — choose the 3D rendering approach (OQ-3)

As the developer,
I want evidence for SceneView vs custom OpenGL ES,
So that the 3D cube in Epic 5 is built once on the right engine.

**Refs:** FR-1, FR-2 · NFR-2, NFR-11 · AD-4 · OQ-3

**Acceptance Criteria:**

**Given** a time box of [ASSUMPTION: 3 days] within weeks 1–2
**When** both candidates render a 27-cubie cube with one animated quarter turn behind a throwaway `CubeRenderer` implementation
**Then** frame time, APK size delta, animation control and `renderedFacelets()` feasibility are measured on the available devices

**Given** the measurements
**When** the spike ends
**Then** the decision is appended to the architecture memlog with `uv run _bmad/scripts/memlog.py append`, and the spine's SceneView `[ASSUMPTION]` is confirmed or a `bmad-architecture` update is requested

### Story 1.9: Spike — Guided 6-face Scan prototype and recognition test matrix (OQ-7, OQ-8)

As the developer,
I want to know early whether on-device color recognition is good enough,
So that the camera features ship as GA or, by the decided fallback, as BETA.

**Refs:** FR-21, FR-24 · recognition NFRs, NFR-11 · AD-9, AD-18 · OQ-7, OQ-8 · UX Flow 9

**Acceptance Criteria:**

**Given** a prototype CameraX fixed-grid viewfinder that emits `FaceSample`s and a nearest-calibrated-center CIELAB classifier
**When** it scans the test cubes (≥ 1 stickered, ≥ 1 stickerless) under indoor light on two devices
**Then** per-sticker accuracy is reported against recorded ground truth

**Given** the exit criterion (≥ 99% of stickers correct under normal indoor light on two reference devices)
**When** it is met
**Then** `CAMERA_SCAN` is planned as GA; otherwise `CAMERA_SCAN` is set to BETA and the Manual Cube Editor becomes the primary Solve action (PRD §6.2), and the result is appended to the PRD memlog

**Given** OQ-8
**When** the spike closes
**Then** the test matrix (cubes × four lighting conditions × devices) and the `FaceSample` JSON fixture format with ground truth are written down for Epic 10, and an initial Quick Scan method recommendation (Kotlin, OpenCV or LiteRT) for OQ-7 is recorded with its size cost

### Story 1.10: Week-2 decision gate: schedule and benchmark harnesses

As the developer,
I want the open week-2 decisions closed and the benchmark harnesses in place,
So that the rest of P0 is planned against a confirmed scope and performance can be measured on demand.

**Refs:** NFR-2, NFR-3 · AD-14 · OQ-9, ARCH-OQ-1

**Acceptance Criteria:**

**Given** the Story 1.8 spike result and the PRD §6.2 fallback (camera ships as beta if recognition misses ≥ 99%)
**When** the gate is held
**Then** OQ-9 is recorded as decided (PO 2026-10-30: keep ~16 weeks) in the PRD memlog, and a Story 1.9 result below the exit criterion is recorded as a trigger to reopen OQ-9

**Given** ARCH-OQ-1, closed by the PO on 2026-10-30
**When** the gate closes
**Then** the architecture memlog and the spine's Reference devices row are confirmed to name the minimum and mid-tier devices, and nothing is re-recorded

**Given** no physical device is required
**When** a Macrobenchmark module (2D net frame timing, NFR-2) and a JVM benchmark harness (solve-corpus placeholder, NFR-3) are added
**Then** both build in CI, the JVM harness runs on demand with one Gradle command and prints its metrics, and the Macrobenchmark runs on demand against a connected device or emulator; device baselines are Story 1.11

### Story 1.11: Reference-device baselines and SceneView spike removal

As the developer,
I want frame-timing baselines measured on the two reference devices,
So that the rendering choice is confirmed on real hardware and the spike code can go.

**Refs:** NFR-1, NFR-2, NFR-13 · AD-4, AD-14 · ARCH-OQ-1

**Acceptance Criteria:**

**Given** the reference devices (minimum Galaxy Tab S7 FE 5G, mid Galaxy S24 FE) and the Story 1.10 Macrobenchmark module
**When** it runs on each device
**Then** a 2D net frame-timing baseline (NFR-2) is recorded per device

**Given** the OpenGL ES renderer from Story 1.8
**When** it is re-measured on both devices with the in-app `onDrawFrame` log
**Then** p90 ≤ 33.3 ms confirms the choice, and the result is appended to the architecture memlog; a p90 above 33.3 ms reopens the rendering choice (AD-4)

**Given** the choice is confirmed
**When** the story closes
**Then** the Story 1.8 SceneView spike is deleted: `app/src/spike3dSceneView/`, `app/src/spike3dNoSceneView/`, the `rubikxplorer.spike3d.sceneview` switch in `app/build.gradle.kts`, and the `sceneview` entry in `gradle/libs.versions.toml`

*Annotated by SCP 2026-11-12: the minimum reference device is now the Lenovo Tab M10 Plus 3rd Gen. This story's results on the Galaxy Tab S7 FE 5G (then the minimum) and the S24 FE stay as dated evidence. On the Lenovo, the Story 12.2 Macrobenchmark measured frame-interval p90 18.35 ms (face turns) and 19.66 ms (View Rotation), within 33.3 ms, so the AD-4 renderer choice holds on the new minimum. The 2D net baseline is still waived (#106, v1.1).*

## Epic 2: Learn with a guided Lesson

A Learner can open a data-defined Lesson, see its Objective, watch an explained Demonstration, and work through it in any of the four Teaching Modes until the Completion Predicate is met. P0.2 exit: cross and corner Lessons pass scenario tests.

### Story 2.1: Load validated Lesson content and strings

As a Learner,
I want every Lesson's words and rules to come from checked content,
So that no Lesson ships with missing text or a broken step.

**Refs:** FR-4, FR-6 · NFR-10 · AD-6, AD-12 · AR-10 · UX-DR24

**Acceptance Criteria:**

**Given** `content/schema/*.schema.json` and the Kotlin content model (Stage, Lesson, steps with `goal`, `preserve`, `watch`, `notice`, `moves`)
**When** the content test runs
**Then** it fails on any schema/model mismatch, any missing required Lesson field, an unknown predicate, or a duplicate, missing or argument-mismatched `Text` key in `content/strings/en.json`

**Given** a content bundle
**When** it is loaded by kotlinx.serialization
**Then** it exposes `schemaVersion` and a `contentRevision` hash, and load failures are sealed results

**Given** a Learner-facing string anywhere in `:core:*` or the UI
**When** a lint/test check scans the code
**Then** it is a `Text(key, args)` or a navigation-chrome `strings.xml` entry, never a literal

### Story 2.2: Predicate registry for Stages 1–3

As a Learner,
I want the app to judge my progress by what my cube actually looks like,
So that any correct way of reaching the goal counts.

**Refs:** FR-4, FR-5, FR-12 · AD-6 · AR-10

**Acceptance Criteria:**

**Given** the sealed `@Serializable` `Predicate` hierarchy with discriminator `"predicate"`, flat parameters and the `allOf`/`anyOf` combinators
**When** predicates for Stage 1 (Notation exercises), Stage 2 (white cross aligned) and Stage 3 (first-layer corners with the cross preserved) are added
**Then** each implements `preservedPieces(state)` and `ObjectiveProgress`, is color-anchored and orientation-invariant, and has fixture tests

**Given** a JSON predicate with an unknown discriminator
**When** content loads
**Then** the content test fails

### Story 2.3: Run a Lesson through the session reducer

As a Learner,
I want each Move in a Lesson recorded exactly once and reflected exactly on screen,
So that the Lesson never gets out of step with my cube.

**Refs:** FR-1, FR-3, FR-4 · NFR-4 · AD-3, AD-4, AD-11, AD-12 · AR-7, AR-8, AR-15

**Acceptance Criteria:**

**Given** `@Singleton SessionHost` in `:app` holding at most one `LearningSession` (`SessionKind.LESSON`)
**When** `SessionEvent`s (`LessonStarted`, `MoveCommitted`, `Undo`, `Redo`, `Reset`) are dispatched
**Then** the pure `reduce()` on one confined dispatcher returns `Transition(state, effects, presentation)`, `revision` increases monotonically, and events carry `at: Instant`

**Given** an async Effect issued at revision N
**When** its result arrives after revision N+1
**Then** the reducer records `EffectResultDiscarded`, and a test proves it

**Given** a reducer test
**When** it scans `reduce()`
**Then** it never reads `Clock` or `RandomSource`

**Given** `session/TRANSITIONS.md`
**When** the table tests run
**Then** every virtual-mode event row (field mutated, undoable, clears redo) is asserted

**Given** a randomized Move/undo/redo suite of [ASSUMPTION: 10,000] steps
**When** it runs against the reducer and `Net2DRenderer`
**Then** `renderedFacelets()` equals `virtual` at every drained step (NFR-4)

**Given** a Lesson with unsaved steps
**When** the Learner taps Reset
**Then** it asks "Reset the cube? This lesson's unsaved steps will be lost." before resetting (FR-3)

### Story 2.4: Watch an explained Demonstration

As a Learner,
I want to watch a Move sequence step by step with the pieces that matter highlighted,
So that I see what changes before I try it myself.

**Refs:** FR-6 · AD-4, AD-20 · AR-20 · UX-DR4, UX-DR6, UX-DR14, UX-DR29 · UX Flow 1

**Acceptance Criteria:**

**Given** a pure `PlaybackController` over `List<Move>`
**When** play, pause, step forward, step back, replay and speed (0.5×/1×/2×) are invoked
**Then** it emits the expected position and presentation items, scrubbing snaps to Move boundaries, and it is never checkpointed

**Given** a Demonstration on the 2D net
**When** a step plays
**Then** pieces outside the step are dimmed, the Keep pieces stay lit, the target piece gets the violet highlight outline, and the playback transport shows the step counter and current Move

**Given** reduce motion is on
**When** a Demonstration plays
**Then** it steps instead of animating

### Story 2.5: Lesson screen with Show me and Guide me

As a Learner,
I want the Lesson to tell me the goal before it shows the Move,
So that I learn to think before I copy.

**Refs:** FR-5, FR-6 · NFR-7, NFR-10, NFR-13 · AD-3, AD-5, AD-17 · UX-DR7, UX-DR8, UX-DR22, UX-DR23, UX-DR24, UX-DR31 · UX Flow 1

**Acceptance Criteria:**

**Given** a navigation shell (Welcome arrives in Story 7.7), the Learn tab listing available Lessons [ASSUMPTION: flat list until Story 7.2 replaces it with Stage rows], and Lesson as a full-screen task flow
**When** a Lesson opens
**Then** the panel shows Objective → step card → Teaching Mode switcher → Hint + Next, in compact stacked or medium/expanded side-by-side layout per window class

**Given** *Show me*
**When** the Learner taps play after the Objective
**Then** the full Demonstration plays with each step's Goal, Keep and Watch, and Notice after the Move

**Given** *Guide me*
**When** a step begins
**Then** Goal, Keep and Watch show first, and the Move chip is revealed only on tap, driven by `TutorDecision.reveal`, not by the screen

**Given** the Completion Predicate is met
**When** the last Move commits
**Then** the Objective-met state shows (subtle check, violet pulse on affected pieces, Notice text, *Next* enabled) and never auto-advances past Notice

**Given** system back in a Lesson
**When** it is pressed
**Then** the Lesson closes immediately with no confirmation

### Story 2.6: Test me and Let me try

As a Learner,
I want to choose the next Move myself and be told whether it helped,
So that I prove to myself I understand the step.

**Refs:** FR-5, FR-11 · AD-3, AD-6, AD-18 · UX-DR8, UX-DR25

**Acceptance Criteria:**

**Given** *Test me*
**When** the Learner commits a Move
**Then** `ObjectiveProgress` classifies it as Progress ("That works too — you're closer."), Neutral ("That didn't change much. Try another turn.") or Harmful [ASSUMPTION: shows a neutral "That moved a piece you need" until Epic 4 adds Diagnosis], and the next Move is hidden until a hint is requested

**Given** *Let me try*
**When** the step shows
**Then** Goal and Keep stay visible, Moves stay hidden until a hint, and the same Completion Predicate decides completion

**Given** `TEST_ME` is OFF
**When** the switcher renders
**Then** *Test me* is hidden, and switching between remaining modes keeps the Cube State and progress

**Given** the four modes
**When** a scenario test runs the same Lesson in each
**Then** all evaluate the same Completion Predicate, and predicate evaluation never reads `teachingMode`

### Story 2.7: Author Stages 1–3 with path tests

As a Learner,
I want the first three Stages (fundamentals, first cross, first-layer corners) ready to learn,
So that I can go from zero to a solved first layer.

**Refs:** FR-4, FR-5, FR-6 · NFR-10 · AD-6 · AR-10, AR-22 · UX-DR24 · UX Flow 1

**Acceptance Criteria:**

**Given** Lesson JSON for Stages 1–3 with IDs in the stable namespaces (`stage-02-first-cross`, `lesson-02-03`)
**When** the content test runs
**Then** every Lesson validates, and every step's text follows the Goal/Keep/Watch/Notice voice guide

**Given** each Lesson's entry state
**When** an automated test drives at least one legal path
**Then** it reaches the Completion Predicate, and a deliberate harmful Move fails the predicate (scenario tests, P0.2 exit)

**Given** tutor configuration values needed so far
**When** they are authored
**Then** they live in `tutor-config.json`, not code

## Epic 3: Solve any cube offline

A Learner can enter any cube's colors, get a verified Solution from the in-house Solver, and step through it. Stories 3.1–3.4 are the P0.S parallel track and depend only on Epic 1. P0.S exit: solves the corpus within NFR-3, and every Solution is re-verified.

Story 3.8 owns reference-device pruning-table measurements, the production delivery decision and its integration, plus the reference-device-equivalent JVM profile calibration and calibrated solve-corpus evidence deferred from Story 3.3. Story 3.3 depends on 3.2's verified APIs, not on the packaging decision. Story 3.4 may implement and test its injected port/effect contract before 3.8; final production loading is integrated and accepted in 3.8. Story 3.7 cannot receive production end-to-end acceptance before 3.8 passes. Epic 3/P0.S closure preserves NFR-3 and all original exit criteria; the existing JVM proxy does not establish physical performance.

### Story 3.1: Solver coordinates and move tables (clean room)

As the developer,
I want the two-phase coordinate model built from the published description,
So that the Solver is fast and license-clean.

**Refs:** FR-15 · NFR-12 · AD-1, AD-8 · AR-12

**Acceptance Criteria:**

**Given** `:core:solver` with no third-party solver code
**When** phase-1 (twist, flip, slice) and phase-2 (corner permutation, edge permutation, slice permutation) coordinates and their move tables are implemented
**Then** coordinate ↔ cubie round-trips and move-table transitions match direct cubie application for all coordinates in tests

**Given** the repo
**When** a reviewer checks provenance
**Then** each source file cites the published algorithm description it was written from, and none is derived from min2phase

### Story 3.2: Pruning tables, cached off the main thread

As the developer,
I want tested clean-room pruning-table generation, optional off-main cache infrastructure and a reproducible device measurement harness,
So that search can use verified table APIs while production delivery is decided from real-device evidence in Story 3.8.

**Refs:** FR-15 · NFR-3, NFR-11, NFR-12 · AD-1, AD-8, AD-11, AD-12, AD-16 · AR-12, AR-22 · UX-DR25

**Acceptance Criteria:**

**Given** the clean-room Story 3.1 coordinate transitions
**When** phase-1 twist/slice and flip/slice and phase-2 corner/slice and edge/slice pruning projections are generated
**Then** all reachable entries have breadth-first distances from solved, solved entries have distance zero, graph consistency and descending-path tests pass, and the API exposes admissible phase heuristics and deterministic payload size without third-party solver code

**Given** the optional pruning-table provider
**When** tables are generated or a versioned private-file cache is loaded or replaced
**Then** CPU generation uses `Dispatchers.Default`, disk access uses an I/O dispatcher, the bounded cache validates its version and checksum, corrupt data is regenerated, replacement is atomic, and load failures are sealed values

**Given** cancellation, concurrent callers and failed cache writes
**When** provider tests run
**Then** cancellation is cooperative and retryable, concurrent loads are serialized, no partial table result is published, and a failed replacement preserves the prior destination and cleans temporary files

**Given** the debug measurement Activity and documented adb procedure
**When** the debug build is produced
**Then** the harness can report device identity, cold-process generation duration, payload/cache bytes, an independent warm disk-cache load and main-thread StrictMode violations; no unrun physical result is claimed

**Given** app startup and Lessons
**When** the infrastructure is installed
**Then** neither calls or awaits it, and no bundled-versus-first-run production policy is selected by this story

**Given** the implementation and acceptance record
**When** Story 3.2 is independently reviewed
**Then** JVM/cache/cancellation tests and CI-equivalent gates pass, host and format facts are distinguished from device results, and every physical measurement, delivery decision, production wiring and applicable StrictMode obligation transferred below remains required in Story 3.8

**Closure boundary:** Story 3.2 can close independently after this replacement is approved, its independent acceptance gate is rerun and normal PR/CI gates pass. Approval alone does not mark it done or authorize a fabricated production decision. Its current before-merge hardware gate is superseded only for this revised infrastructure scope.

### Story 3.3: Two-phase search verified on a 10,000-state corpus

As a Learner,
I want every Solution the app gives me to actually solve my cube,
So that I can trust it with the real thing.

**Refs:** FR-15 · NFR-3, NFR-4 · AD-8, AD-12 · AR-12

**Acceptance Criteria:**

**Given** `CubeSolver.solve(cubies)` returning `SolveResult.{Solved, Invalid, TimedOut, Failed}`
**When** it runs over the solved state plus ≥ 10,000 random legal states including deep Scrambles
**Then** applying each `Solved` Solution to its input yields solved, every time, in CI

**Given** the JVM benchmark harness from Story 1.10
**When** the corpus runs
**Then** median, p95, memory and failure rate are reported with the actual host/JVM profile, zero failures and at least 95% successful solves within 3 s pass as a host regression check; no reference-device equivalence is claimed from uncalibrated host results

**Given** a cancelled coroutine
**When** search is in progress
**Then** it stops promptly and returns no partial result

**Approved calibration deferral (2026-11-02):** Story 3.3 may close after independent acceptance and normal CI on the revised implementation/host-evidence scope. Reference-device-equivalent JVM profile calibration and the calibrated corpus performance criterion are mandatory Story 3.8 acceptance, before production Story 3.7 acceptance or Epic 3/P0.S closure. Story 12.2 retains final physical production corpus confirmation. NFR-3 and the 3 s timeout are unchanged. See `sprint-change-proposal-2026-11-02-search-calibration-deferral.md`.

### Story 3.4: SolveCube use case

As a Learner,
I want solving to never freeze the app or invent an answer,
So that a failure is clear and I can try again.

**Refs:** FR-15 · AD-2, AD-3, AD-8, AD-12 · AR-12

**Acceptance Criteria:**

**Given** a validated `CubeState` with any `orientation`
**When** `SolveCube` runs as an Effect with a 3 s timeout
**Then** the Solver receives `cubies` only, the Moves are mapped back through `orientation`, and the result is re-verified before it is returned

**Given** a timeout or failure
**When** `SolveCube` completes
**Then** it returns `TimedOut` or `Failed` with a `Text` recovery prompt, never a made-up Solution

**Given** unvalidated input
**When** a caller tries to reach the Solver
**Then** it is impossible by type (only `CubeValidator` produces `CubeState`)

### Story 3.5: Enter a cube in the Manual Cube Editor

As a Learner,
I want to paint my cube's 54 stickers and see exactly which ones are wrong,
So that I can solve a real cube without a camera.

**Refs:** FR-14 · NFR-7 · AD-2, AD-17 · UX-DR16, UX-DR21, UX-DR25 · UX Flow 3 (failure path)

**Acceptance Criteria:**

**Given** the sticker review net component
**When** the Learner taps a sticker, then a color
**Then** the sticker updates, centers are fixed by the home frame, and glyphs show when color-blind display is on

**Given** an invalid entry
**When** validation runs
**Then** offending pieces are outlined in `status-invalid` with a plain explanation, non-local errors get their own lines ("One corner is twisted…", "One edge is flipped.", "Two pieces are swapped…"), and **Accept** stays disabled

**Given** a valid entry
**When** Accept is tapped
**Then** the editor returns the validated `CubeState` to its caller, and it is not a session (AD-3)

*Amended by SCP 2026-11-20 v1.1 promotion (#253), built as an issue fix through `/bmad-orchestrate-issues`; acceptance on the Lenovo Tab M10 Plus 3rd Gen and the Galaxy S24 FE, main app build, Solve → Enter colors:*

**Given** every face entered, with any face turned 90°, 180° or 270° from the editor's hold
**When** the faces resolve through `ScanAssembler` (AD-9) to exactly one valid cube
**Then** it is accepted with no question, the net redraws the turned face, and a `meta` note (and TalkBack) says "We turned the Up face to match the others."

**Given** entered faces that resolve to no valid cube or to more than one
**When** validation runs
**Then** the instruction line names the face and its hold ("Check the Up face: hold green toward you and look at the top."), that face's tab is marked, and TalkBack reads it; the neighbour-center bands around the edited face always show the hold

**Given** an Editor opened on the default solved cube with faces not yet touched
**When** validation runs
**Then** no piece or count error shows; the line reads "{n} faces left to enter: …", and *Accept* stays disabled

**Given** an Editor seeded from a Solve-tab scan with unset stickers *(SCP 2026-11-24 github-issue-triage, #321; Story 10.15)*
**When** it renders and validation runs
**Then** each unset sticker shows as the review net's blank well (neutral, no sticker color, dashed edge and "?", DESIGN.md) and TalkBack reads it as "{face} sticker {n}, not set"; tapping it and then a color sets it, as for any sticker; a face counts as entered once all nine of its stickers are set; while any sticker is unset no count or piece error shows, the line names the faces left to enter, and *Accept* stays disabled

**Given** an invalid entry once every face is entered
**When** validation runs
**Then** color counts name each color that is off ("Red: 10 (one too many) · Green: 8 (one missing)"), impossible pieces name the piece and its faces, suspect stickers carry Scan review's numbered markers, and their face tabs a warning dot (unchanged by SCP 2026-11-24, #323: its likely-misread proximity rule applies to Scan review only)

**Given** auto-advance
**When** a sticker or a whole face is set
**Then** the next sticker carries the pulsing cursor, a completed face's tab gets a ✓ and "Front done ✓ → Right" shows for about 1.5 s, TalkBack announces "Front face done. Right face next.", and the update-cue ✓ is legible on every sticker color, green included (DESIGN.md)

### Story 3.6: Step through a Solution with generated notes

As a Learner,
I want to step through a Solution with a short note for every Move,
So that I can follow it on my cube at my own pace.

**Refs:** FR-16 · AD-3, AD-20 · AR-20 · UX-DR14, UX-DR24 · UX Flow 3

**Acceptance Criteria:**

**Given** `MoveNote.of(move): Text`
**When** it is generated for every Move type
**Then** it follows the template face + direction + piece group ("Turn the Right face clockwise: the right column moves up") and a test asserts no note claims a strategic purpose

**Given** a `SOLVE_PLAYBACK` session started with a validated state and its Solution
**When** the Learner uses the shared playback transport
**Then** they can step forward and back, pause and replay, with the MoveNote under the current Move, and the final step reaches the solved state

*Deferred from this story: the UX-DR14 Move strip and visible scrubber (`epic-3-deferred-3-6-ux-dr14-move-strip`), now built by Story 12.15 (SCP 2026-11-17 playback view and move glyphs).*

### Story 3.7: Solve tab end to end

As a Learner who just wants a stuck cube solved,
I want one place to enter my cube and get it solved,
So that I don't have to take a course first.

**Refs:** FR-14, FR-15, FR-16 · AD-3, AD-10, AD-18 · UX-DR22, UX-DR25 · UX Flow 3

**Acceptance Criteria:**

**Given** the Solve tab in the bottom bar
**When** it opens
**Then** it offers *Enter colors* (and *Scan my cube* only when its flag is raised (Story 10.11), hidden while absent), and an open Lesson shows "Starting a solve pauses your lesson." before the Lesson session is suspended

**Given** a valid entry
**When** the Solver runs
**Then** a cancelable indeterminate progress bar shows; while the solver prepares (the first solve in each app process) "Preparing the solver." shows (SCP 2026-11-22 quick-scan-corners-and-solver-copy, PO Q-g, #262; it replaces the SCP 2026-11-12 and SCP 2026-11-11 texts, and makes no claim about how long later solves take; a JVM test checks that the line shows only on the first solve in a process), or, when no valid shipped or saved tables exist and they must be generated, "Setting up the solver for first use. This can take a little while." (SCP 2026-11-11); and a timeout shows "Couldn't find a solution in time. Your colors are still here — try again." with no partial result

**Given** the last Playback step
**When** the cube is solved
**Then** a quiet offer "Want to learn how to do this yourself?" links to Learn, and solves are not resumed after the app closes

*(SCP 2026-11-25 retire-v1-1: v1.1 is retired; v1.0 scope.)*

### Story 3.8: Reference-device pruning-table delivery decision and production integration

As a Learner,
I want pruning tables delivered using a measured production strategy,
So that solving is ready without freezing Lessons or exceeding the app's size budget.

**Refs:** FR-15 · NFR-3, NFR-5, NFR-11 · AD-8, AD-11, AD-13, AD-16 · AR-12, AR-22 · UX-DR25

**Dependencies:** accepted Story 3.2 infrastructure; production loading integration coordinated with Story 3.4. May proceed after 3.2 while search is developed. Required before production end-to-end acceptance of Story 3.7 and closure of Epic 3/P0.S delivery readiness. Story 12.2 remains the final device solve-corpus performance confirmation.

**Acceptance Criteria:**

**Given** the minimum reference device, Samsung Galaxy Tab S7 FE 5G, and the Story 3.2 harness
**When** repeated cold-process generation and independent warm-cache loads run
**Then** generation duration, payload/cache bytes and cache loading duration are recorded with device/Android identity, revision, run conditions and available memory evidence; no host timing or emulator measurement substitutes for the minimum-device result

**Given** a release-equivalent unbundled baseline and a temporary bundled candidate
**When** installed size and table delivery costs are measured
**Then** the measurement method, installed totals/deltas, cache cost and recognition-model budget are recorded against NFR-11's install size below 30 MB; APK file size alone is not accepted as installed-size evidence

**Given** those device and size measurements
**When** bundled versus first-run delivery is selected
**Then** the rationale, tradeoffs and evidence are appended to the architecture memlog using `uv run _bmad/scripts/memlog.py append`, and the selected mode respects AD-8, offline operation and NFR-11

**Given** the selected production delivery mode
**When** it is integrated into the production Solver loading path
**Then** table generation/access remains off-main; if first-run is chosen, generation uses `Dispatchers.Default` and results are cached in app-private storage; bundled delivery also uses off-main loading and any applicable private cache; Lessons never wait for readiness and no app-startup wait is introduced

**Given** the actual selected production integration on the minimum reference device
**When** cold and warm loading paths run under debug StrictMode
**Then** no main-thread disk access is reported, logs are inspected, Lessons remain independently usable, and cancellation/failure never publishes an invented or partial Solution

**Given** the minimum reference device and the reproducible Story 3.3 solve corpus (solved plus at least 10,000 random legal states including deep Scrambles)
**When** the Developer establishes a reference-device-equivalent JVM profile using measured paired device/JVM runs and reruns that corpus on the calibrated profile
**Then** calibration method, device/Android and host/JVM identities, revision, seed, warmup/table-readiness boundaries and run conditions are linked; every returned Solution is independently verified, zero failures are required, median, p95, memory and failure rate are reported, and at least 95% solve within 3 s on the calibrated profile; uncalibrated host or emulator results cannot close this criterion

**Given** completed integration and the calibration evidence transferred from Story 3.3
**When** the story is reviewed
**Then** affected correctness, CI and release-size checks pass and link their evidence; Story 3.8 remains open if any required hardware result, calibration or calibrated corpus result, delivery decision, memlog append or production verification is unavailable; production acceptance of Story 3.7 and closure of Epic 3/P0.S remain blocked until all pass

*Annotated by SCP 2026-11-12: the minimum reference device is now the Lenovo Tab M10 Plus 3rd Gen; this story's measurements on the Galaxy Tab S7 FE 5G stay as dated evidence, and the delivery decision (bundled) stands. With the same methods the Lenovo measured: generation plus write 14.35 s (first-run rule G ≤ 5 s fails, so bundled is also selected there), per-process preparation 12.6 s debug and ≤ 10.5 s release-equivalent (#104, v1.1), and 0 StrictMode violations (no waiver needed; #107 is Samsung-only). The calibrated JVM profile (k = 16.94) was paired with the Tab S7 FE debug build; recalibration against the new minimum belongs to #105 (v1.1).*

### Story 3.9: Measure and shorten cold solver preparation

As a Learner who opens the app to solve a cube,
I want the first solve after the app starts to be ready sooner,
So that I am not left waiting on "Preparing the solver."

**Refs:** FR-15 · NFR-3, NFR-11 · AD-8, AD-16 · Stories 3.3, 3.8, 12.2 · #104

**Depends on** Story 3.8. **v1.0 scope; does not gate the v1.0.0 tag**. **Human gate:** a measurement session on the physical Lenovo Tab M10 Plus 3rd Gen (TB128XU), the minimum reference device. *(SCP 2026-11-24 github-issue-triage, #104: the cold-preparation follow-up to Story 3.8; bundled pruning tables did not remove the per-process move-table build, 12.6 s debug median and ≤ 10.5 s release-equivalent on the Lenovo.)* *(SCP 2026-11-25 retire-v1-1: v1.1 is retired; v1.0 scope. This supersedes the 2026-11-24 "v1.1 scope" wording.)*

**Acceptance Criteria:**

**Given** the physical Lenovo TB128XU and a release-equivalent build (R8, release code)
**When** at least five cold process starts each run the production Solve flow to a Solution
**Then** move-table preparation and search are timed and reported separately (median, minimum, maximum), with device and Android identity, revision and run conditions, in `docs/benchmarks.md`, beside the existing 12.6 s debug / ≤ 10.5 s release-equivalent baseline

**Given** that baseline
**When** the PO agrees a target from it
**Then** the target is recorded in the story spec before any mitigation is merged, and #104 closes only when the target is met

**Given** one bounded mitigation (for example shipping the move tables, building them faster, or starting preparation when the Solve tab opens)
**When** it is measured with the same method on the same device
**Then** it is compared with the baseline, and the comparison and the choice are appended to the architecture memlog with `uv run _bmad/scripts/memlog.py append`

**Given** the chosen mitigation
**When** it is verified
**Then** every Story 3.3 corpus Solution is still independently verified, preparation stays off the main thread (debug StrictMode adds no app main-thread disk violation), Lessons never wait for the Solver, no wait is added at app start, the install stays within NFR-11, and the copy "Preparing the solver." is unchanged

## Epic 4: Get help when I go wrong

When a Learner makes a mistake, the tutor names what happened with honest Certainty, offers the next hint rung, and leads back by undo or the shortest Guided recovery.

### Story 4.1: Assess evidence and produce a TutorDecision

As a Learner,
I want the tutor's confidence in what went wrong to rest on evidence,
So that it never tells me something it can't know.

**Refs:** FR-9, FR-10 · AD-5, AD-6, AD-12 · AR-9 · PRD Principle 3

**Acceptance Criteria:**

**Given** `tutor/Assess` and one `CandidateEnumerator(expected, maxDepth, moveSet)` with `maxDepth` and `moveSet` from `tutor-config.json`
**When** a virtual-mode state and Move history are assessed
**Then** it returns a `TutorDecision` with exactly the AD-5 fields (`objectiveId`, `satisfied`, `diagnosis?`, `certainty`, `nextAction`, `explanation: Text`, `recovery`, `reveal`, `advance`)

**Given** the closed `ErrorCategory` enum
**When** code switches on it
**Then** the `when` is exhaustive, each category maps to exactly one Recovery Path, and Certainty is only `VERIFIED | LIKELY | UNKNOWN`

**Given** `Diagnosis.verified(...)`
**When** it is called with more than one consistent candidate
**Then** it cannot construct a verified Diagnosis (smart constructor test)

**Given** a code search
**When** CI runs
**Then** no module other than `tutor/Assess` constructs a Certainty value

**Given** a multi-step Lesson in *Let me try* or *Test me*
**When** the Learner meets a step's `done` predicate by any legal route (including steps out of order)
**Then** `TutorDecision.advance` moves to the first step whose `done` is unmet, and the step card follows

**Given** an Undo across a step boundary
**When** the state is re-assessed
**Then** the current step is re-derived from the state, so the card returns to the earlier step

**Given** the shipped Stages 1–3 content
**When** the content test runs
**Then** every step has a `done` predicate, the authored path satisfies each step's `done` in order (extends `ShippedLessonPathsTest`), and the final step's `done` agrees with the Lesson's Completion Predicate

### Story 4.2: Diagnose each Error Category with tested copy

As a Learner,
I want the tutor to say plainly what my mistake was,
So that I understand it instead of just being told "wrong".

**Refs:** FR-10, FR-12 · AD-2, AD-5, AD-6 · AR-9 · UX-DR24, UX-DR25, UX-DR28

**Acceptance Criteria:**

**Given** one scenario test per category (`WRONG_MOVE_OR_DIRECTION`, `WRONG_PIECE`, `WRONG_PIECE_ORIENTATION`, `UNEXPECTED_ORIENTATION`, `COMPLETED_LAYER_DISTURBED`, `INVALID_ENTERED_STATE`, `UNKNOWN_DISCREPANCY`)
**When** each runs
**Then** the expected category, Certainty and `Text` copy from EXPERIENCE.md State Patterns are produced, and ambiguous states yield *likely* or *unknown*, never *verified*

**Given** an orientation-only difference
**When** it is diagnosed
**Then** it is `UNEXPECTED_ORIENTATION` with "Your cube is turned — hold it with {bottom} on the bottom, {front} in front.", the colors naming the Lesson's authored hold (for the shipped home-frame Lessons, WHITE on U and GREEN on F: "…hold it with yellow on the bottom, green in front."), and *Show me how*, without undo

**Given** *Test me* on any shipped Lesson
**When** the Learner commits the next Move of the current step's authored path
**Then** it is never classified Harmful, even if it temporarily lifts a solved piece (e.g. R' opening R' D' R); it is classified On path and shows "That's the move — keep going."

**Given** *Test me* and a Move off the authored path
**When** it is committed
**Then** it is judged against the current step (its `preserve` set and the step's ObjectiveProgress), not whole-Objective progress: Progress if it gets closer without disturbing the step's preserved pieces, Neutral if it neither gets closer nor disturbs a preserved piece (including a step back that touches no preserved piece), Harmful only if it disturbs a preserved piece

**Given** a Harmful Move in *Test me*
**When** it is committed
**Then** it produces the diagnosed error and a recovery prompt, replacing both Story 2.6's placeholder copy and its whole-Objective classifier (`MoveFeedback`); scenario tests cover a taught insert in Stages 2 and 3

**Given** any diagnosis
**When** it is shown
**Then** the Learner's state is never silently replaced, and no red fill, ✗ or shake appears

### Story 4.3: Climb the Hint Ladder

As a Learner,
I want help that gets more specific only as I ask for it,
So that I do as much of the thinking as I can.

**Refs:** FR-11, FR-5 · AD-3, AD-6 · UX-DR9

**Acceptance Criteria:**

**Given** the Hint button **Hint (n/3)**
**When** the Learner taps it
**Then** it moves up exactly one rung: conceptual clue → highlighted target or Move arrow → full Demonstration

**Given** a new Objective
**When** it starts
**Then** the ladder resets to rung 0

**Given** an attempt
**When** hints are requested
**Then** the reducer counts them per attempt in `SessionState` for the attempt log (persisted in Story 6.3), and the full Solution is never shown by default

### Story 4.4: Undo or try again after a mistake, keeping the Checkpoint

As a Learner,
I want a calm way back after breaking something I'd already solved,
So that one slip doesn't cost me the Lesson.

**Refs:** FR-12, FR-4 · AD-3, AD-6 · UX-DR4, UX-DR11, UX-DR25

**Acceptance Criteria:**

**Given** a diagnosed error
**When** the recovery prompt shows inline in the lesson panel
**Then** it offers *Undo*, *Guide me back* (disabled until Story 4.5) and *Try again*, in that order, never as a dialog

**Given** the cross is complete in a corner Lesson and a Move breaks it
**When** the tutor evaluates
**Then** it says the previous layer was disturbed, outlines the disturbed pieces in violet, offers undo or Guided recovery, keeps the earlier Checkpoint marker in session state, and does not unlock the next Stage

**Given** *Try again*
**When** it is tapped
**Then** the Objective restarts from the last Checkpoint state without restarting the Lesson

### Story 4.5: Guided recovery by the shortest correction

As a Learner,
I want the app to show me the fewest turns that put my solved pieces back,
So that I recover without starting over.

**Refs:** FR-12 · AD-3, AD-5, AD-6, AD-8 · AR-22 · UX-DR11

**Acceptance Criteria:**

**Given** `RecoveryPlanner` as a bounded IDA* over the current predicate's `preservedPieces` [ASSUMPTION: decided here per spine Deferred], run through `PlanRecovery` as an Effect with a timeout
**When** *Guide me back* is tapped
**Then** "Finding the shortest way back…" shows, and the result arrives as `recovery: Ready(moves)` with the preserved pieces highlighted ("3 moves — your cross stays")

**Given** no plan within bounds
**When** the effect completes
**Then** "I can't find a short way back. Undo, or start this step again." shows

**Given** the Learner commits a Move while the plan is pending
**When** the result arrives
**Then** it is discarded as stale and re-offered

**Given** scenario tests for every Error Category's Recovery Path
**When** they run
**Then** each path returns to a state that satisfies the current Objective's preserve predicate

### Story 4.6: Adaptive assistance from configuration

As a Learner,
I want the tutor to give me more help when I'm stuck and less when I'm doing well,
So that I'm neither lost nor hand-held.

**Refs:** FR-13 · AD-6, AD-18 · UX-DR10

**Acceptance Criteria:**

**Given** `AdaptivePolicy` with thresholds in `tutor-config.json`
**When** the Learner makes a first mistake, a repeated mistake, continues to struggle, or succeeds repeatedly
**Then** the tutor explains + highlights, demonstrates, guides step by step, or reduces help respectively, with config-driven tests for each threshold

**Given** help changes
**When** the panel renders
**Then** the Assistance line says why ("Showing the turn, since this step tripped you up twice."), offers *Back to my usual help*, and never switches Teaching Mode without a tap

**Given** `ADAPTIVE_ASSISTANCE` is OFF
**When** a Lesson runs
**Then** the Assistance line is absent and the Hint Ladder is fixed

## Epic 5: Turn the cube in 3D

A Learner can turn and inspect a smooth 3D cube with gestures, rotate the view freely, and see Demonstrations with arrows and highlights. Every action is also reachable without gestures or sight. The 3D renderer replaces the 2D net behind the same `CubeRenderer` port (the engine chosen in Story 1.8).

### Story 5.1: Render the cube in 3D behind the CubeRenderer port

As a Learner,
I want a real-looking 3D cube that turns smoothly,
So that it looks like the cube in my hands.

**Refs:** FR-1 · NFR-2, NFR-4 · AD-4, AD-17 · AR-8 · UX-DR4, UX-DR21 · UX Flow 6

**Acceptance Criteria:**

**Given** the 3D adapter implementing `CubeRenderer` with the engine chosen in Story 1.8
**When** a Move commits
**Then** an `Animate(move)` quarter turn completes within 250 ms on the reference device, and `Snap` items jump without animation

**Given** more than 3 queued presentation items
**When** the Learner keeps tapping keys
**Then** the queue fast-forwards at increased speed and never drops a committed Move

**Given** the randomized sync suite from Story 2.3
**When** it runs against the 3D renderer in an instrumented test
**Then** `renderedFacelets()` equals the committed state at every drained step

**Given** stickers on the 3D cube
**When** color-blind glyphs are on
**Then** they draw from `StickerAppearance`, matching the 2D net

### Story 5.2: Turn layers with gestures

As a Learner,
I want to turn a layer by dragging it,
So that the screen cube feels like a real one.

**Refs:** FR-1 · NFR-1 · AD-4, AD-17 · AR-8 · UX-DR26, UX-DR27

**Acceptance Criteria:**

**Given** a pure `GestureInterpreter(touchPath, viewPose) → MoveIntent?` in `:core:domain`
**When** a one-finger drag starts on a sticker and runs along a row or column
**Then** it returns that layer's Move for any view pose, with unit tests over pose × direction fixtures

**Given** a drag below the confidence threshold
**When** it ends
**Then** no intent is returned and the Cube State is unchanged

**Given** a recognized gesture
**When** the `MoveIntent` is produced
**Then** it goes through the same `MoveCommitted` path as a Move key (a `SessionEvent` in a Lesson; on the free cube, the shared `FreeCubeViewModel.onMoveIntent` commit, replaced by the free-cube reducer in Story 6.6), the input is acknowledged within 100 ms, and a light haptic tick fires (switchable off)

*Annotated by SCP 2026-11-18: device testing showed gestures misfire (GitHub #206). Story 12.16 replaces first/last-point recognition with a path-based preview: the layer turns with the finger and commits on release, slices are previewed, and a two-finger drag on the cube makes wide Moves.*

### Story 5.3: Rotate the view and the cube

As a Learner,
I want to look at the cube from any side without changing it, and to reorient it on purpose,
So that I can inspect pieces and hold it like my real cube.

**Refs:** FR-1, FR-2, FR-7 · AD-2, AD-4 · UX-DR4, UX-DR5, UX-DR26, UX-DR29

**Acceptance Criteria:**

**Given** a one-finger drag starting off the cube, or a two-finger drag
**When** it moves
**Then** the camera rotates, no `SessionEvent` is produced, and a property test confirms no sequence of View Rotations changes the state

**Given** a View Rotation
**When** the Move keys and Notation render
**Then** they are unchanged (Notation follows the cube's orientation)

**Given** a two-finger twist (z) or the ⟳ x/y keys
**When** it commits
**Then** a Cube Rotation re-frames the cube with a 200 ms turn and the frame label briefly shows the new front color

**Given** double-tap on the stage, the reset-view affordance, or the overflow View Rotation arrow buttons
**When** used
**Then** the view resets or rotates accordingly without gestures

*Annotated by SCP 2026-11-18: the reset-view affordance was not discoverable (GitHub #207). Story 12.17 adds a View rotated badge with a one-tap Reset view, removes the separate toolbar reset-view button (Reset view now lives on the badge; double-tap and the overflow View Rotation arrows stay), adds face labels while rotated, and adds a distance check on double-tap. Off-cube two-finger drags are still a View Rotation; on-cube parallel two-finger drags become wide Moves (Story 12.16).*

### Story 5.4: Arrows, highlights and dimming in 3D

As a Learner,
I want to see which layer is about to turn and which pieces matter,
So that I know where to look before I act.

**Refs:** FR-6, FR-11 · AD-4, AD-20 · UX-DR4, UX-DR6

**Acceptance Criteria:**

**Given** a Demonstration step or hint rung 2
**When** it shows on the 3D cube
**Then** a curved violet arrow follows the turning layer with the Move in `notation` at its tip, and target pieces get the 3dp highlight with 1dp inner stroke

**Given** a *Guide me* step
**When** it shows
**Then** off-step pieces dim with `sticker-dimmed-overlay` and Keep pieces stay lit

**Given** reduce motion
**When** an arrow appears
**Then** it appears without animating

### Story 5.5: Describe the cube to TalkBack

As a Learner who uses a screen reader,
I want the cube described in words,
So that I can follow a Lesson without seeing the stickers.

**Refs:** NFR-7 · AD-17 · AR-17 · UX-DR29

**Acceptance Criteria:**

**Given** a semantics description derived from `CubeState` and the current predicate
**When** TalkBack focuses the cube
**Then** it reads a summary ("White cross solved; 2 of 4 first-layer corners placed") and offers a *Read faces* action that reads face by face

**Given** the Lesson screen
**When** TalkBack traverses it
**Then** focus order is frame label → cube description → Move key bar → lesson panel → Teaching Mode switcher

**Given** a Certainty change
**When** it happens
**Then** it is announced

## Epic 6: Pick up where I left off

A Learner's Lesson survives closing the app or process death, their attempts are recorded, and their preferences stick. Room tables are created here only for what this epic needs.

### Story 6.1: Survive process death with session snapshots

As a Learner,
I want to come back to exactly the cube I left,
So that a phone call or a killed app doesn't cost me progress.

**Refs:** FR-19 · NFR-6 · AD-10, AD-11, AD-16 · AR-14, AR-16

**Acceptance Criteria:**

**Given** Room (room3, KSP) with a migration-test harness and one serial writer
**When** a transition commits
**Then** a `SessionSnapshot` keyed by `SessionKind` is written conditionally on `revision`, off the main thread

**Given** a forced process death in an instrumented test
**When** the app restarts
**Then** a `LESSON` session resumes at its last committed state, and `SOLVE_PLAYBACK` does not resume

### Story 6.2: Versioned Checkpoints at Objective boundaries

As a Learner,
I want the app to resume at my last finished step even if the Lesson content changed,
So that updates never break my progress.

**Refs:** FR-12, FR-19 · NFR-6 · AD-6, AD-10 · AR-14 · UX-DR25

**Acceptance Criteria:**

**Given** an Objective boundary
**When** the reducer emits `PersistCheckpoint`
**Then** a versioned DTO is written (`checkpointFormat`, `contentRevision`, content IDs, Facelets + orientation, Notation history, mode), never raw `SessionState` or embedded content

**Given** a Checkpoint whose `contentRevision` or IDs no longer match
**When** it is restored
**Then** the Lesson restarts with the stored cube and shows "This lesson was updated. We've restarted it with your cube as it was."

**Given** each format version
**When** tests run
**Then** a round-trip test and a previous-format decoder test pass

### Story 6.3: Record every attempt append-only

As a Learner,
I want each try at a step recorded honestly,
So that my trend reflects what really happened.

**Refs:** FR-11, FR-19, FR-20 · AD-10 · AR-14, AR-21

**Acceptance Criteria:**

**Given** reducer effects `AttemptStarted` and `AttemptEnded(objectiveId, outcome, errors, hints, assistanceLevel)`
**When** they are handled
**Then** `attempt` rows are inserted with idempotency key `(sessionId, revision)` in the same transaction as the snapshot, and rows are never updated

**Given** a Lesson repeated three times
**When** the log is read
**Then** it holds three distinct attempts and earlier rows are unchanged

### Story 6.4: Preferences and Settings

As a Learner,
I want my animation speed, glyphs, theme and last Teaching Mode remembered,
So that the app is set up the way I like each time.

**Refs:** FR-19 · NFR-7 · AD-10, AD-17 · AR-21 · UX-DR21, UX-DR27, UX-DR30

**Acceptance Criteria:**

**Given** `UpdatePreference` as the only writer
**When** the Learner changes theme (system/light/dark), color-blind glyphs, animation speed, haptics or reduce-motion override in Settings (reached from the You tab placeholder)
**Then** the value persists across restarts and applies immediately on every surface

**Given** the last Teaching Mode used
**When** a new Lesson opens
**Then** it starts in that mode

**Given** *Reset preferences* in Settings
**When** confirmed
**Then** only preferences return to defaults

### Story 6.5: Resume from the Continue card

As a Learner,
I want one tap to continue where I stopped,
So that coming back is effortless.

**Refs:** FR-19 · AD-3, AD-10 · UX-DR25 · UX Flow 1

**Acceptance Criteria:**

**Given** a stored virtual-mode Checkpoint
**When** the Learn tab opens
**Then** a Continue card shows the Lesson, and resuming restores silently with "Picked up where you left off."

**Given** a Lesson suspended by a solve (Story 3.7)
**When** the solve ends
**Then** the Lesson resumes from its Checkpoint

### Story 6.6: Give the free cube one event owner

As a Learner,
I want every way of turning the free cube to behave the same,
So that the cube I see always matches the turns I made.

**Refs:** FR-1, FR-3 · NFR-4 · AD-3, AD-4, AD-20 · AR-8

**Acceptance Criteria:**

**Given** a pure `FreeCubeEvent` (`MoveCommitted(move)`, `Undo`, `Redo`, `Reset`, `Scrambled(moves)`) and `reduceFreeCube(state, event)` in `:core:application`, where the state holds the `MoveHistory` and a monotonic `revision`
**When** an event is reduced
**Then** it returns the next state and a revision-stamped presentation: `Animate(move)` for a commit, `Animate(inverse)` for Undo, `Animate(move)` for Redo, and `Snap` for Reset and Scramble. Undo or Redo with nothing to undo or redo returns the state unchanged with no presentation. JVM tests cover Story 1.6's undo, redo, reset and redo-clearing rules

**Given** the free cube screen
**When** a gesture, a Move key or an accessibility action produces a `MoveIntent`, or the Learner taps Undo, Redo, Reset or Scramble
**Then** `FreeCubeViewModel` only dispatches a `FreeCubeEvent` and submits the returned presentation. It is not a second writer of the history, the Scramble Moves are generated outside the reducer (no `RandomSource` in `reduce`), and a test shows a gesture and the equal Move key produce identical transitions

**Given** the existing free-cube suites (the 2D/3D randomized sync suite and the Story 5.2 gesture tests)
**When** they run
**Then** they pass unchanged, and the free cube is still not a Learning Session and persists nothing (AD-20)

## Epic 7: Complete the Course to a first solve and mastery

A Learner can progress through all seven Stages to a first solve, practice to mastery, see their trend, and skip ahead when they already know a Stage. A pilot build reaches closed testing (around week 9).

### Story 7.1: Stage progress log and gating

As a Learner,
I want Stages to open as I'm ready for them,
So that I always know what to do next.

**Refs:** FR-4, FR-19, FR-20 · AD-10 · AR-14, AR-21

**Acceptance Criteria:**

**Given** a `progress_event` table (`LessonCompleted`, `StageSkippedByCheck`, `StageSkippedByPlacement`, `StageUnlocked`, `ProgressReset(epoch)`) written only by `CompleteLesson`, `PassSkipCheck`, `PlaceFromScan`, `ResetProgress`
**When** Stage status is read
**Then** it is a pure function over the current epoch's events (Locked, In progress, Completed, Skipped, Skipped by placement, Mastered)

**Given** a Stage's prerequisites
**When** they are met
**Then** the Stage unlocks; completion is by Completion Predicate only, never by Move count

### Story 7.2: Learn tab and Stage detail

As a Learner,
I want to see the whole Course and each Stage's state at a glance,
So that I know how far I've come.

**Refs:** FR-4, FR-17, FR-20 · AD-10, AD-17 · UX-DR18, UX-DR22, UX-DR23

**Acceptance Criteria:**

**Given** the Learn tab
**When** it opens
**Then** it shows the Continue card, the seven Stage rows with state text + icon, and a *Faster last layer* placeholder row hidden until Epic 9

**Given** a Stage row
**When** tapped
**Then** Stage detail shows its Lessons, the Mastery progress line, *Practice this Stage* (disabled until Story 7.4) and *I already know this* (enabled in Story 7.7)

### Story 7.3: Author Stages 4–6 to a first solve

As a Learner,
I want the middle layer and last-layer Lessons,
So that I can finish my first solve.

**Refs:** FR-4, FR-5, FR-6 · NFR-10 · AD-6 · UX-DR20, UX-DR24 · UX Flow 1

**Acceptance Criteria:**

**Given** predicates and Lesson JSON for Stage 4 (middle layer), Stage 5 (beginner LL orientation) and Stage 6 (LL completion)
**When** the content test and path tests run
**Then** every Lesson validates and has a legal path to its predicate and a harmful-Move scenario

**Given** the end of Stage 6 in virtual mode
**When** the cube is solved
**Then** the first-solve milestone moment plays once (≤ 1.2 s burst, `display` line, static under reduce motion) and *Practice on a new scramble* is offered

### Story 7.4: Independent practice on a fresh Scramble

As a Learner,
I want to practice a Stage on a new Scramble with less help,
So that I prove I can do it without being walked through.

**Refs:** FR-20, FR-11 · AD-3, AD-11 · AR-7, AR-15 · UX Flow 7

**Acceptance Criteria:**

**Given** *Practice this Stage* (and Stage 7, Independent practice)
**When** it starts
**Then** a `PRACTICE` session begins from a `NewScramble` reduced to that Stage's entry condition [ASSUMPTION: the entry state is generated by applying random Moves that preserve the earlier Stages' predicates (e.g. U turns and that Stage's taught sequences), so no Solver is needed], and the step card shows only the Objective with hints on request

**Given** a practice attempt
**When** it ends
**Then** `AttemptEnded` records outcome, errors, hints and `assistanceLevel = REDUCED`

### Story 7.5: Mastery Condition and milestone

As a Learner,
I want the app to recognize when I've really mastered a Stage,
So that "mastered" means something.

**Refs:** FR-20, FR-4 · AD-6, AD-10 · UX-DR18, UX-DR20 · UX Flow 7

**Acceptance Criteria:**

**Given** `tutor/Mastery` with the Mastery Condition in config (2 successful completions, ≥ 1 with reduced assistance [ASSUMPTION])
**When** attempts are evaluated
**Then** config-driven tests cover each transition, and neither Lesson completion nor *skipped* marks *mastered*

**Given** the condition is met
**When** the attempt ends
**Then** the milestone plays, "First cross mastered." shows, the next Stage is offered, and Stage detail reads "Mastery: 2 of 2 solves · 1 with reduced help"

### Story 7.6: Progress on the You tab, with reset

As a Learner,
I want to see my hints falling over time and to start over if I choose,
So that I see my independence growing and stay in control of my data.

**Refs:** FR-19, FR-20 · AD-10 · UX-DR18, UX-DR25, UX-DR28 · UX Flows 7, 9 · SM-5, SM-C1

**Acceptance Criteria:**

**Given** the You tab
**When** it opens
**Then** each Stage shows its state and a trend row (dot sparkline of hints per attempt in date order, latest emphasized, attempt count), with no totals, scores or red

**Given** *Reset progress* in Settings
**When** the confirmation sheet lists exactly what is erased (Stages, attempts) and is confirmed
**Then** `ResetProgress` appends `ProgressReset(epoch)`, and calibration and preferences are untouched

### Story 7.7: Welcome and the skip-ahead check

As a Learner who already knows some of this,
I want to prove it quickly and skip ahead,
So that I'm not forced through Stages I've mastered elsewhere.

**Refs:** FR-4, FR-17 · AD-10, AD-18 · UX-DR21, UX-DR22, UX-DR25 · UX Flow 8

**Acceptance Criteria:**

**Given** first launch with no profile
**When** Welcome shows
**Then** it offers *I'm new* (Stage 1) and *I can already solve it*; *Start from my cube* stays hidden until its flag (`PLACEMENT`) is raised (Story 10.11); color-blind glyphs are offered

**Given** *I already know this* on a Stage, or *I can already solve it* for the whole Course
**When** the Learner solves that Objective from a fresh Scramble with hints off and no time limit
**Then** `PassSkipCheck` marks the Stage(s) ◇ *Skipped* (never mastered) and unlocks the next

**Given** the Learner gives up the check
**When** they exit
**Then** "Let's do this Stage together — it'll be quick." (or "Let's find where to start" from Welcome) shows, with no record against them

*SCP 2026-11-20 v1.1 promotion (#209): Welcome's* I can already solve it *now opens the entry-level picker of Story 14.1; this story's whole-Course check stays as its* Check me with a scramble instead *choice.*

*(SCP 2026-11-25 retire-v1-1: v1.1 is retired; v1.0 scope.)*

### Story 7.8: Ship a pilot build to closed testing

As the developer,
I want a signed build in pilot beginners' hands by week 9,
So that their results come back in time for fixes.

**Refs:** NFR-8, NFR-9, NFR-11 · AD-13, AD-15 · AR-5 · OQ-5 · PRD §7 gate 4

**Depends on** Stories 12.11 and 12.12 (Epic 9 seams and 2-look rules, `sprint-change-proposal-2026-11-10-two-look-rules-and-epic-9-seams.md`), and 12.13 (durable session end, `sprint-change-proposal-2026-11-10-session-end-deferrals.md`).

**Acceptance Criteria:**

**Given** a `vX.Y.Z` tag
**When** the release workflow runs
**Then** it builds a signed AAB with R8 and resource shrinking, uploads the mapping file, sets `versionCode` to the CI run number, runs instrumentation tests, and uploads to the internal track

**Given** the internal build
**When** it is manually promoted to closed testing
**Then** the ~8 pilot beginners from OQ-5 can install it, and a pilot session protocol records task completion, hint use and points of confusion outside the app (NFR-8)

**Given** the release AAB
**When** the size check runs
**Then** it reports against NFR-11's < 30 MB budget

*(SCP 2026-11-13) AC2's closed-testing review needs a privacy-policy URL in Play Console. That is Story 13.6; #127 § 2 waits for it.*

## Epic 8: Use my real cube

A Learner can follow Lessons and Solution Playback on a physical cube, with the app honestly tracking the Expected State and handling mismatches without a camera. The camera rungs arrive when their flags are raised (Story 10.11; Epics 10–11, flag-gated; SCP 2026-11-14). *(SCP 2026-11-25 retire-v1-1: v1.1 is retired; v1.0 scope.)*

### Story 8.1: Physical-mode transitions in the reducer

As a Learner using a real cube,
I want the app's picture of my cube to change only when I tell it something,
So that it never pretends to see my cube.

**Refs:** FR-3, FR-7, FR-8 · NFR-4 · AD-3, AD-5 · AR-7, AR-9

**Acceptance Criteria:**

**Given** the Sync State Machine (Virtual, PhysicalAwaitingConfirm, PhysicalPredicted, PhysicalVerified, PhysicalMismatch)
**When** `ModeSwitched`, `PhysicalConfirmed`, `MismatchReported`, `Undo`, `EditorEntered` events are reduced
**Then** `TRANSITIONS.md` rows for physical mode are asserted, `virtual` mirrors `expected`, and `ModeSwitched` is an undo barrier that clears redo

**Given** `Undo` of a `PhysicalConfirmed`
**When** reduced
**Then** `expected` reverts and the presentation says "Turn R' on your cube to undo."

**Given** a property test over random event sequences
**When** it runs
**Then** `expected` changes only on confirmation or an accepted Full observation, and View Rotation changes neither Notation nor `expected`

### Story 8.2: Follow a Lesson on my real cube

As a Learner with a cube in my hands,
I want to do each turn on my cube and tell the app when I'm done,
So that I learn on the real thing.

**Refs:** FR-7, FR-8 · AD-3, AD-5 · UX-DR4, UX-DR5, UX-DR12, UX-DR13, UX-DR25 · UX Flows 1, 4

**Acceptance Criteria:**

**Given** the *Physical mode* toggle in a Lesson
**When** switched on
**Then** a Confirm-your-cube card offers *It matches* / *Enter it* (*Scan it* hidden until its flag is raised (Story 10.11)), and the frame turns to dashed EXPECTED with no Certainty chip

**Given** a step
**When** the Learner taps **Done** (always primary) or *I did something else* then a Move key
**Then** the Expected State advances by the confirmed or entered Move

**Given** the Objective is met on the Expected State
**When** the panel updates
**Then** it reads "If your cube matches the picture, this step is done.", keeps EXPECTED, enables *Next*, and shows no celebration

*(SCP 2026-11-25 retire-v1-1: v1.1 is retired; v1.0 scope.)*

### Story 8.3: Report a mismatch honestly without a camera

As a Learner whose cube doesn't match,
I want the tutor to ask the right questions instead of guessing,
So that we find the problem together.

**Refs:** FR-9, FR-10, FR-12, FR-14 · AD-5 · AR-9 · UX-DR12, UX-DR13, UX-DR25 · UX Flow 1 (failure path)

**Acceptance Criteria:**

**Given** *My cube doesn't match* or *I'm not sure what I did*
**When** chosen
**Then** the chip shows ? *Unknown* and the tutor asks in FR-9 order, skipping the Quick Check rung while scanning is unavailable: "Which turn did you make?" with Move keys → a one-face picker ("Look at the front face — which corner is white?") → "Let's re-enter your cube" via the Manual Cube Editor

**Given** the Learner enters the Move they made
**When** `Assess` runs
**Then** the Diagnosis uses it as evidence, and Certainty is *verified* only with a unique candidate, otherwise ≈ *Likely* with the candidate shown

**Given** a re-entered cube (`EditorEntered`)
**When** accepted
**Then** it becomes the Full observation and a Guided recovery is offered

### Story 8.4: Leave and resume physical mode safely

As a Learner,
I want switching back to the screen cube, or reopening the app, to never lose track of my cube,
So that I can trust the app's picture.

**Refs:** FR-8, FR-19 · NFR-6 · AD-3, AD-10 · UX-DR25

**Acceptance Criteria:**

**Given** physical mode
**When** the Learner switches to virtual
**Then** `virtual := expected`, "The on-screen cube now matches what we expected on yours." shows, and Undo stops there

**Given** a physical Checkpoint
**When** restored after a kill or a suspended session
**Then** the session lands in PhysicalAwaitingConfirm with `observed` cleared and no effects resumed, and the Confirm-your-cube card shows

### Story 8.5: Follow a Solution on my real cube

As a Learner solving a real cube,
I want playback to advance as I confirm each turn,
So that I never lose my place.

**Refs:** FR-16, FR-7 · AD-3, AD-20 · UX-DR12, UX-DR14 · UX Flow 3

**Acceptance Criteria:**

**Given** Solution Playback
**When** the Learner taps *I'm following on my cube*
**Then** the session enters physical mode, each *Done* advances one step, and the MoveNote shows under each Move

**Given** the final step
**When** confirmed
**Then** the app says "Your cube should be solved now." and does not assert *solved* as observed

### Story 8.6: Confirm the first physical solve and prove no desync

As a Learner,
I want my first real solve recognized only when it's true,
So that the moment means something.

**Refs:** FR-7, FR-9 · NFR-4 · AD-3, AD-5 · UX-DR20, UX-DR25 · UX Flow 1 · PRD §7 gate 3

**Acceptance Criteria:**

**Given** the end of Stage 6 in physical mode
**When** the Objective is met on the Expected State
**Then** "Your cube should be solved now. Check it with the camera, or tell me if it is." shows, and the milestone plays only after *Yes, it's solved* (or a Quick Check, when its flag is raised (Story 10.11))

**Given** an end-to-end instrumented suite
**When** it runs virtual and physical flows with Moves, confirmations, undo, mode switches, pause, kill and resume
**Then** there is zero silent desync between `virtual`, `expected` and the rendered cube

*(SCP 2026-11-25 retire-v1-1: v1.1 is retired; v1.0 scope.)*

### Story 8.7: Camera-free Stage 6 prompt

As a Learner on a real cube without camera support,
I want the end-of-Stage-6 prompt to ask only for what the app can use,
So that it never promises a camera check that doesn't exist.

**Refs:** FR-7, FR-26 · NFR-8, NFR-10 · AD-6, AD-13, AD-18 · UX-DR19, UX-DR20, UX-DR25 · `sprint-change-proposal-2026-11-08-vision-deferral.md`

**Acceptance Criteria:**

**Given** `CAMERA_SCAN` is OFF (the v1.0 release level)
**When** Stage 6's Objective is met in Physical-cube Mode
**Then** the panel reads "Your cube should be solved now. Compare it with the picture, then tell me if it is." and offers *Yes, it's solved* and *I did something else*; no camera wording or Quick Check entry appears

**Given** `CAMERA_SCAN` is BETA or GA
**When** the same happens
**Then** the camera wording "Your cube should be solved now. Check it with the camera, or tell me if it is." shows

**Given** the two prompt variants
**When** content tests run
**Then** both live as content string keys (NFR-10), any duplicate in `strings.xml` is removed or aligned, `ShippedContentTest` covers both, and the 8.6 milestone gating is unchanged

**Given** the AD-13 manifest check in `build-logic` (`ManifestPolicy`)
**When** the release manifest declares any runtime permission, `CAMERA` included
**Then** CI fails; debug builds may still declare `CAMERA` for the opt-in spike source set, and the `AGENTS.md` Policy line on v1.0 runtime permissions is removed once this check is live

## Epic 9: Faster last layer and the Algorithm Library

A Learner can browse and watch all 78 verified OLL/PLL Algorithms, track their own Algorithm Status, and take the 2-look LL mini-Course to mastery.

### Story 9.1: Algorithm and Case content for all 78

As a Learner,
I want every OLL and PLL Algorithm available with its Case and family,
So that I can learn the full last layer in the app.

**Refs:** FR-18, FR-17 · AD-6 · AR-10, AR-22 · OQ-2, OQ-6

**Acceptance Criteria:**

**Given** JSON content for 57 OLL and 21 PLL Cases (`case-oll-45`) and one primary Algorithm each (`alg-oll-45-a` declaring its `caseId`), with set and family
**When** the content test runs
**Then** IDs are unique and stable, every Case has exactly one primary Algorithm, and Notation parses

**Given** OQ-6
**When** the data is authored
**Then** Algorithms and Case names come from common knowledge with no copied diagrams or prose, and the sources are noted in the PRD memlog

### Story 9.2: One LastLayerMatcher and build-failing Algorithm verification

As a Learner,
I want every Algorithm in the Library proven correct,
So that I never memorize a typo.

**Refs:** FR-18, FR-17, FR-28 · NFR-4 · AD-19 · AR-19

**Acceptance Criteria:**

**Given** `LastLayerMatcher` in `:core:domain` (canonical LL frame; OLL by orientation mask under pre-AUF `U^k`; PLL by permutation under pre/post-AUF and `y^k`; partial views → candidate set)
**When** each Algorithm's inverse is applied to solved
**Then** the resulting state matches its own Case and no other, and applying the Algorithm solves the LL up to AUF

**Given** a deliberately mistyped Algorithm fixture
**When** the verification test runs
**Then** the build fails

### Story 9.3: Recognition diagrams derived from state

As a Learner,
I want each Case's top-layer picture drawn from the actual cube state,
So that the diagram can never be wrong.

**Refs:** FR-18 · NFR-7 · AD-17, AD-19 · AR-23 · UX-DR17, UX-DR21

**Acceptance Criteria:**

**Given** a Case state derived via `LastLayerMatcher`
**When** the diagram component renders it
**Then** it shows the U face plus side bands using `StickerAppearance` (glyphs when on), with no hand-drawn assets

**Given** recognition diagrams are cut (cut order item 4)
**When** tiles render
**Then** they fall back to Case name and Notation with no placeholder art [ASSUMPTION: controlled by a build constant until a flag is added via `bmad-correct-course`, AR-23]

### Story 9.4: Browse and filter the Library

As a Learner,
I want to browse Algorithms by family and by my own status,
So that I can find what I'm working on.

**Refs:** FR-18 · NFR-13 · AD-17 · UX-DR17, UX-DR22, UX-DR23, UX-DR25 · UX Flows 2, 6

**Acceptance Criteria:**

**Given** the Library tab
**When** it opens
**Then** 78 Algorithm tiles show grouped by OLL and PLL family, in 4 columns at expanded width

**Given** neutral filter chips for family and Algorithm Status
**When** a filter matches nothing
**Then** "No algorithms marked Learning yet." shows with a link to 2-look

### Story 9.5: Algorithm detail with Demonstration

As a Learner,
I want to watch any Algorithm from any angle, step by step,
So that I can learn its finger flow.

**Refs:** FR-18, FR-2, FR-6 · AD-4, AD-20 · UX-DR14 · UX Flows 5, 6

**Acceptance Criteria:**

**Given** an Algorithm tile
**When** tapped
**Then** detail opens (bottom nav kept) with the Case diagram, Notation, and a Demonstration from the Case state using the shared transport (play, pause, step, speed, scrub) and free View Rotation

**Given** the Demonstration
**When** it is scrubbed back and forth
**Then** the rendered cube always matches the Move boundary it snapped to

*SCP 2026-11-20 v1.1 promotion (#208): Algorithm detail gains* I'm doing it on my cube *(Algorithm practice on a real cube), Story 14.2.*

### Story 9.6: Set my Algorithm Status

As a Learner,
I want to mark Algorithms as Learning or Learned myself,
So that the Library reflects my own judgment.

**Refs:** FR-18, FR-19 · AD-10 · AR-14, AR-21 · UX-DR17 · SM-C3

**Acceptance Criteria:**

**Given** an `algorithm_status_event` table keyed by `algorithmId`, written only by `SetAlgorithmStatus`
**When** the Learner sets *Not started*, *Learning* or *Learned* in detail
**Then** an event is appended, `Flow<Map<AlgorithmId, Status>>` updates every tile, and *Not started* reads "Not in your set yet."

**Given** *Reset progress*
**When** confirmed
**Then** the sheet now also lists Algorithm Statuses, and the reset is an appended event, never a delete

### Story 9.7: 2-look LL Lessons

As a Learner who can already solve,
I want to learn the last layer in four smaller steps,
So that I solve faster with only a few Algorithms.

**Refs:** FR-17, FR-5 · AD-3, AD-6, AD-19 · OQ-2 · UX-DR18 · UX Flow 2

**Acceptance Criteria:**

**Given** 2-look Lessons (≈ 3 + 7 OLL, 2 + 4 PLL [ASSUMPTION, OQ-2]) referencing Library Algorithm IDs, never copied Notation
**When** content tests run
**Then** each Lesson's predicate is an LL-step predicate built on `LastLayerMatcher` (e.g. all LL edges oriented) with a legal path test

**Given** the Course is completed or skipped
**When** the Learn tab renders
**Then** the *Faster last layer* rows unlock and run as a `TWO_LOOK` session with Teaching Modes, hints and recognition diagrams

**Given** a Lesson ends
**When** the Learner sees "Add these algorithms to Learning?"
**Then** accepting calls `SetAlgorithmStatus`; declining changes nothing

### Story 9.8: 2-look completion and mastery

As a Learner,
I want to know when I've truly got 2-look,
So that I know when to move to the full set.

**Refs:** FR-17, FR-20 · AD-10 · UX-DR18, UX-DR20 · UX Flow 2

**Acceptance Criteria:**

**Given** every 2-look Lesson predicate met
**When** progress is read
**Then** the mini-Course row shows *Completed*

**Given** LL practice on fresh Scrambles
**When** the Learner twice solves the LL using only 2-look Algorithms with no hints
**Then** the mini-Course becomes *Mastered*, the milestone plays; after the first such solve the row reads "Mastery: 1 of 2"

*Amended by SCP 2026-11-10: Mastered also requires Completed. Implemented in Story 12.12.*

## Epic 10: Scan my cube

A Learner can scan a cube face by face, correct and calibrate it, solve it from the scan, and verify physical Moves with a Quick Check. P0.4 exit: the Guided Scan meets its accuracy targets on the test matrix, or `CAMERA_SCAN` ships as BETA. *(SCP 2026-11-14: restored from `post-launch/v1.1-camera-recognition.md`. Every story merges to `main` with every camera flag OFF in release; Story 10.9 lands first and Story 10.10 second. Stories 10.9–10.11 are appended IDs that run earlier than their number, as the How-to-read note allows.)* *(SCP 2026-11-15: Stories 10.12 (recorder rework, session A2) and 10.13 (OQ-7 spike) run after 10.10 and before 10.1.)* *(SCP 2026-11-16: Story 10.14 (scan each face in any rotation) runs after 10.3.)* *(SCP 2026-11-16 main-app entry points: Story 10.5 is split. 10.5 keeps the scan entry from the Solve tab, and the appended Story 10.15 takes solve, playback and the BETA label; 10.15 runs right after 10.5.)* *(SCP 2026-11-19 review face retake, #235: Story 10.16 (retake faces from the scan review) runs after 10.14.)*

### Story 10.1: Classify stickers and assemble a Scan Result

As a Learner,
I want stickers read against my own cube's colors with honest uncertainty,
So that the app flags what it isn't sure of instead of guessing.

**Refs:** FR-21, FR-23, FR-24 · recognition NFRs, NFR-12 · AD-2, AD-5, AD-9 · AR-13

**Depends on** Story 10.10 (live fixtures) and Story 10.13 (OQ-7 approach). *(SCP 2026-11-14: the Story 1.9 matrix holds one synthetic sample and offline JPEG labels only, `Fixtures/README.md`.)* *(SCP 2026-11-15: the classifier follows the OQ-7 decision; its confidence threshold is also the prefill rule for Story 10.3 review.)*

**Acceptance Criteria:**

**Given** the live `FaceSample` fixtures recorded by Story 10.10 (and the existing synthetic `Fixtures/labeled/face-samples.json`)
**When** `StickerClassifier` (calibration as a parameter) and `ScanAssembler` run on the JVM
**Then** they produce `ScanResult.Full(Facelets + StickerConfidence)` or `Partial`, rotating samples into URFDLB order using center and up-neighbor colors, and extend the existing `dev.cubentia.domain.recognition` types (`FaceSample`, `CalibrationCenters`, `PhysicalObservation`) instead of duplicating them

**Given** the fixture suite
**When** accuracy is reported
**Then** per-sticker and per-scan accuracy and the confidently-wrong rate (target ≤ 0.1%) are printed per device (Lenovo Tab M10 Plus 3rd Gen, Galaxy S24 FE, Galaxy Tab S7 FE), and only categorical confidence leaves the module

### Story 10.2: Guided 6-face Scan viewfinder

As a Learner,
I want the camera to guide me face by face and capture on its own,
So that scanning is quick even for a beginner.

**Refs:** FR-21, FR-23 · NFR-5, NFR-8 · AD-9, AD-13, AD-18 · AR-13 · UX-DR15, UX-DR19, UX-DR21, UX-DR25 · UX Flows 4, 6

**Depends on** Stories 10.9 and 10.1. **Human gate:** acceptance on the Lenovo Tab M10 Plus 3rd Gen and the Galaxy S24 FE (device session B).

**Acceptance Criteria:**

**Given** the first scan
**When** the camera permission is requested
**Then** an explanation shows first, using the EXPERIENCE.md camera-rationale copy *(SCP 2026-11-14: copy to be added by the UX update)* from content strings (NFR-10); if denied, an explanation card with *Enter colors instead* (and a Settings deep-link when permanently denied) is shown

**Given** the CameraX adapter in the camera source set (`app/src/camera/kotlin/dev/cubentia/app/camera/`, Story 10.9) *(SCP 2026-11-14: was `:app/camera`)*
**When** the grid is aligned and steady for ~600 ms
**Then** the face is captured automatically (manual shutter also available), the prompt names the center and up colors with a glyph swatch, and the mini net advances *(SCP 2026-11-16: Story 10.14 drops the up color from the prompt; this prompt stays as its re-prompt.)*

**Given** the cube aligned in the grid at the distance that fills it *(SCP 2026-11-15: session A focus miss on the S24 FE)*
**When** alignment is first detected, or the face changes
**Then** focus and exposure are re-triggered on the grid region before auto-capture, and on a phone in portrait the viewfinder and the face prompt fit without scrolling (UX pass 2026-11-15)

**Given** a wrong orientation, glare, blur or low light
**When** detected
**Then** the Learner is re-prompted with specific coaching ("Too much glare — tilt the cube"), not an error

**Given** frames
**When** processed
**Then** they stay in memory, are never persisted or sent, and release logs contain no samples; a debug-only recorder exports `FaceSample` JSON with ground truth

**Given** a device with no camera, or camera hardware that cannot be bound *(SCP 2026-11-14)*
**When** any scan entry point would render
**Then** it is hidden as if its flag were OFF (UX-DR19), and the Play listing still installs on that device (`uses-feature android.hardware.camera.any` with `required="false"`)

**Given** the story closes *(SCP 2026-11-14)*
**When** the PR merges
**Then** the Story 1.9 prototype is deleted (`app/src/spikeScan/`, the `cubentia.spikeScan` switch in `app/build.gradle.kts`), CameraX is bound off the main thread, and a debug StrictMode run on the Lenovo adds no app-frame main-thread disk violation (#107 lists the platform ones already known)

*Amended by SCP 2026-11-24 github-issue-triage (#325, #313), built as issue fixes through `/bmad-orchestrate-issues`; v1.0 scope; acceptance on the Lenovo Tab M10 Plus 3rd Gen and the Galaxy S24 FE, main app build, from every caller of the Guided 6-face Scan viewfinder:*

**Given** the Guided 6-face Scan viewfinder with no face captured in this viewfinder
**When** the Learner taps *Close* or the system Back
**Then** the viewfinder leaves at once and returns `Cancelled`

**Given** one or more faces captured in this viewfinder
**When** the Learner taps *Close* or the system Back
**Then** "Discard the faces you captured?" shows with *Keep scanning* and *Discard*; *Keep scanning* closes the question and keeps every captured face, the mini net and the current prompt; *Discard* returns `Cancelled` and the Learner is back on the screen the scan was opened from; the copy comes from content strings and TalkBack reads the question, then the two actions

**Given** a retake viewfinder opened from review (Story 10.16) or a Quick Scan hand-over viewfinder (Stories 10.17, 10.24)
**When** the Learner backs out
**Then** their own back rules apply, unchanged

**Given** every caller of the Guided 6-face Scan viewfinder: Welcome → *Start from my cube*, Solve → *Scan my cube*, Learn → *Find my place* → *Scan my cube* (Re-placement), and the Quick Check full scan (*Scan all six faces*)
**When** the Learner closes or backs out of it
**Then** the same rule applies (0 faces: leave at once; 1 or more: the confirm), and *Discard* returns to that caller's previous screen

**Given** JVM or Robolectric tests on each of those callers
**When** they run
**Then** they cover leaving with 0 faces, *Keep scanning* and *Discard* with 1 or more faces, for both *Close* and system Back, and check the screen *Discard* returns to

**Given** the viewfinder in tablet landscape on the Lenovo's 600 dp width, and in portrait *(#313)*
**When** it renders
**Then** *Close* sits in the same control group as the shutter and the face prompt, TalkBack reaches it in order with those controls, and at 200% font nothing is cut off; the phone-portrait no-scroll rule still holds

### Story 10.3: Review and correct a Scan Result

As a Learner,
I want to fix any sticker the camera misread before it's used,
So that a bad read never reaches the Solver or the tutor.

**Refs:** FR-23, FR-14 · AD-3, AD-9 · AR-13 · UX-DR16, UX-DR22, UX-DR25

**Acceptance Criteria:**

**Given** a scan sub-flow returning `ScanOutcome {Accepted, Cancelled, FellBackToEditor}` through a typed navigation result
**When** review opens
**Then** only high-confidence stickers are prefilled, uncertain ones (including close calibrated pairs such as yellow vs white) are pre-selected and blank, corrections use the color-entry model from the UX pass (tap-sticker-then-color at most two touches per sticker), and invalid states name the likely bad stickers ("This corner shows two whites.") *(SCP 2026-11-15)* *(SCP 2026-11-24, #323: a sticker is named as a likely misread only under the proximity rule in the amendment below)*

**Given** any uncertain sticker unresolved or an invalid state
**When** the Learner looks at Accept
**Then** it is disabled

**Given** the scan flow
**When** it completes
**Then** only the caller dispatches the result, and the flow never touches a session

**Given** the review screen at 200% font on the Lenovo's 600 dp two-pane width *(SCP 2026-11-14: #137 found word breaks at this width)*
**When** it renders
**Then** no word breaks mid-word and nothing is cut off (`docs/accessibility-manual-check.md`)

*Amended by SCP 2026-11-20 v1.1 promotion (#249), built as an issue fix through `/bmad-orchestrate-issues`; acceptance on the Lenovo Tab M10 Plus 3rd Gen and the Galaxy S24 FE, main app build, from the Solve tab. The shared sticker review net carries it, so the Manual Cube Editor gets it too (PO approved 2026-11-20):*

**Given** the review (or Editor) palette
**When** the Learner long-presses a color, or uses its *Lock this color* accessibility action
**Then** paint mode starts: the swatch shows the locked outline and ✓, the instruction line reads "Painting {color}. Tap stickers to set them. Tap {color} again to stop.", TalkBack announces "Painting {color}", and auto-advance and the next-sticker pulse pause

**Given** paint mode
**When** the Learner taps stickers
**Then** each tap sets the locked color with the update cue and haptic tick, so 9 stickers of one color take 1 long-press + 9 taps

**Given** paint mode
**When** the Learner taps or long-presses the locked color, plain-taps another color, or enters retake mode
**Then** paint mode ends, TalkBack announces "Painting off", and tap-sticker-then-color behaves exactly as before

*Amended by SCP 2026-11-24 github-issue-triage (#323; settles action item `issue-174-deferred-suspect-distance-threshold`), built as an issue fix through `/bmad-orchestrate-issues`; v1.0 scope:*

**Given** a review whose color counts are off
**When** `SuspectRanking` ranks likely misreads
**Then** a scanned, unedited, non-center sticker of an over-counted color qualifies for an under-counted color when its measured Lab is at most `SUSPECT_MARGIN_DELTA_E` farther (CIE76) from that color's calibrated center than from the center of the color it was read as; `SUSPECT_MARGIN_DELTA_E` is 20 ΔE (PO 2026-11-24; twice `StickerClassifier.CLOSE_CALL_DELTA_E`) and is documented in code and in the spec; a qualifying sticker is suggested as the nearest under-counted color it qualifies for; up to 3 qualifying stickers are listed, ordered by margin (smallest first, then sticker index), and the list is never padded with stickers that do not qualify. The rule applies to Scan review only: the Editor has no measured colors and keeps its existing marker rule (Story 3.5, unchanged)

**Given** no candidate qualifies
**When** the review renders
**Then** no "This sticker may be {color} — check it." suggestion and no count-suspect marker shows; the color-count line and the piece problems (outlines, numbered piece markers, face-tab warning dots) still show, and *Accept* stays disabled

**Given** `SuspectRankingTest` and the review tests
**When** they run
**Then** they cover the release-session cases (Up 3 painted orange → blue, and Up 9 painted white → blue: no suggestion names the genuine blue Up 2, and the counts and the impossible corner still show), the #174 yellow-near-orange replay (still suggested "may be orange"), and a case with two under-counted colors (each qualifying sticker suggested as its nearest qualifying under-counted color, the list ordered by margin), and the spec records each case's margin

*Amended by SCP 2026-11-24 github-issue-triage (#321), built with the #321 issue fix; v1.0 scope:*

**Given** a scan review in which the Learner changed no sticker and completed no retake
**When** the Learner taps *Close*
**Then** the review returns `FellBackToEditor` carrying its stickers, and the caller decides what the Editor shows (the Solve tab seeds it, Story 10.15; every other caller opens it blank)

**Given** a scan review with a changed sticker or a completed retake
**When** the Learner taps *Close*
**Then** "Discard this scan?" shows; *Discard* returns `Cancelled`, and dismissing the question keeps the review exactly as it was

**Given** the review tests
**When** they run
**Then** they cover *Close* with no change (`FellBackToEditor` with the stickers) and *Close* with a change, both *Discard* (`Cancelled`) and dismissing

### Story 10.4: Calibrate colors for my cube

As a Learner with a stickerless or unusual cube,
I want to teach the app my cube's colors,
So that scans read it correctly.

**Refs:** FR-24, FR-19 · AD-9, AD-10 · AR-13, AR-21 · UX-DR25 · UX Flow 9

**Human gate:** the first-stickerless-scan prompt is accepted on a device with the stickerless test cube (device session B). *(SCP 2026-11-16 main-app entry points: the PO accepted a partial session B on 2026-11-16 (#166). The checks not done on a device (the review offer, logo-center and implausible re-prompt, rescan after Save, Lenovo) and the offer rule move to Story 10.5's device session B3, run on the main app.)* *(SCP 2026-11-22, #166: the PO's offer rule applies to this gate too: the prompt is offered only on uncertain reads; see the AC annotation below. Accepted at session B3.)*

**Acceptance Criteria:**

**Given** many uncertain stickers in a scan, a first stickerless scan, or Settings *(SCP 2026-11-17 annotation, PO decision of 2026-11-17 at device session B3: "a first stickerless scan" means a first scan that reads uncertain. The review offers calibration only on uncertain reads (NeedsCalibration, a guarded center with no saved calibration, or 6 or more blank stickers). A clean scan gets no offer, and Settings → Color calibration is always available. No scope change.)* *(SCP 2026-11-22: this annotation closes the 10.4 AC wording item in #166.)*
**When** "Colors look off — calibrate?" or Settings → Color Calibration is chosen
**Then** each of the six colors is shown in turn, sampled from a plain sticker of that color (a logo or printed center is rejected or excluded from the sample, per the UX pass), an implausible swatch is refused with a plain re-prompt, the Learner confirms each swatch, and *Save* calls `UpdateCalibration` *(SCP 2026-11-15: a logo white center gave a bad WHITE and a 53/54 read in session A)*

**Given** `CalibrationRepository` as the only owner
**When** a rescan runs
**Then** the classifier uses the saved calibration, which is never in `SessionState` or a Checkpoint

**Given** *Reset calibration* in Settings
**When** confirmed
**Then** `ResetCalibration` clears only calibration

**Given** the Room database at version 7 *(SCP 2026-11-14)*
**When** the calibration entity is added
**Then** the database moves to version 8 with an exported `8.json` schema, a `7→8` migration and a migration test that preserves every existing table's rows (AD-10), and the Settings calibration rows are hidden while `CAMERA_SCAN` is OFF

### Story 10.5: Scan my cube from the Solve tab

As a Learner,
I want to start a scan from the Solve tab and check the result before it is used,
So that I can put my real cube into the app without typing every color.

**Refs:** FR-21, FR-23, FR-24, FR-25 · AD-9, AD-15, AD-18 · UX-DR16, UX-DR19, UX-DR25 · UX Flows 3, 9 · Stories 10.2, 10.3, 10.4, 10.14, 11.2 · #158, #161, #164, #166

**Depends on** Stories 10.3, 10.4 and 10.14. **Human gate:** device session B3 on the main app build (debug), Lenovo Tab M10 Plus 3rd Gen and Galaxy S24 FE, stickered (stka, logo center) and stickerless (stla) cubes. *(SCP 2026-11-16 main-app entry points: split from the original Story 10.5 "Scan to solve"; solve, playback and the BETA label moved to Story 10.15.)*

**Acceptance Criteria:**

**Given** `MainActivity`
**When** the app starts with `CAMERA_SCAN` above OFF and a `ScanLauncher` bound
**Then** it registers `ScanFlowContract` for an Activity result, and the Solve tab shows *Scan my cube* next to *Enter colors*; with `CAMERA_SCAN` OFF, no binding or no camera, *Enter colors* is the only action, exactly as today (Story 3.7), and no dead or disabled scan button shows (UX-DR19)

**Given** *Scan my cube* on the Solve tab
**When** the scan returns `Accepted`
**Then** the validated Cube State is handed to the Solve flow without opening the Manual Cube Editor, and Story 10.15 owns what follows (solve and Solution Playback, or the already-solved state) *(SCP 2026-11-19 scan Accept skips the Editor, #203; until then `Accepted` opened the Editor for a second check)*; `FellBackToEditor` (*Enter colors instead*, or an invalid or incomplete scan) opens the Editor with a one-line note, seeded with the colours the scan read and the Learner's review fixes, unread stickers unset (SCP 2026-11-24, #321, Story 10.15; until then it opened empty); a denied permission or a scan with no captured face opens it blank, and `Cancelled` returns to the Solve tab unchanged; only the Solve flow dispatches the result (Story 10.3)

**Given** camera permission denied from the Solve tab
**When** the Learner taps *Scan my cube*
**Then** the Manual Cube Editor opens directly with a one-line note

**Given** one Activity-level test (Robolectric or instrumented) driving the real route *(closes the deferral in #158, #161, #164 and #166)*
**When** it runs
**Then** it covers Solve tab *Scan my cube* → `GuidedScanActivity` → review → "Colors look off — calibrate?" → calibration → *Save* → rescan with the saved calibration loaded and the preview rebound → review → *Accept* → solving and Solution Playback without the Manual Cube Editor *(SCP 2026-11-19, #203; until then → Manual Cube Editor)*, using a fake frame source, and the review edits made before calibration are kept

**Given** Color Calibration (from review or Settings)
**When** a swatch's cell is aligned and steady
**Then** the swatch is captured automatically with the same steady hold as the face capture (`GuidedScanController.onFrame`, Story 10.14), *Keep* still confirms it, and *Capture* stays available; logo and implausible swatches are still refused with the plain re-prompt (Story 10.4)

**Given** debug flag levels *(SCP 2026-11-16 main-app entry points, AD-18)*
**When** `FeatureFlagLevels.byBuildType["debug"]` is built
**Then** a flag is above OFF in debug only once its entry point is wired in the main app: `CAMERA_SCAN` stays GA; `QUICK_SCAN`, `PLACEMENT` and `CASE_RECOGNITION` go to OFF until Stories 11.1, 11.2 and 11.3 wire them, so Welcome's *Start from my cube* no longer shows as a dead button; `FeatureFlagLevelsTest` pins the debug map

**Given** Stage 6's first-solve prompt in physical mode with `CAMERA_SCAN` above OFF
**When** it renders before Story 10.7 adds the camera check
**Then** it shows the camera-free copy ("compare it with the picture"); the camera copy ("Check it with the camera") appears only when a camera check action is shown (Story 10.7)

**Given** the story closes
**When** the PR merges
**Then** the debug-only `GuidedScanDebugActivity`, its launcher entry in `app/src/camera/debug/AndroidManifest.xml` and `GuidedScanDebugSummaryTest` are deleted, and the fixture recorder (a developer tool) stays debug-only (AD-15)

**Given** device session B3 on the main app build
**When** the PO runs it
**Then** a full scan from the Solve tab reaches the Editor on both devices *(as accepted on 2026-11-18; since SCP 2026-11-19, #203, a full scan reaches Solution Playback directly)*, and the Story 10.4 checks not done on a device are run there (#166): the "Colors look off — calibrate?" offer on review, the logo-center (stka) and implausible-swatch re-prompts, a rescan after *Save* that reads better, swatch auto-capture, and the Lenovo; the PO records the calibration offer rule in the PRD memlog (today a stickerless cube that reads well gets no offer; Story 10.4's AC expects one on a first stickerless scan), and a change to the rule is made in this story

### Story 10.6: Quick Check evidence and Certainty

As a Learner with a real cube,
I want a quick photo to confirm what I actually did,
So that the tutor's verdict is based on evidence.

**Refs:** FR-26, FR-9, FR-10 · recognition NFRs · AD-3, AD-5, AD-9 · AR-9, AR-13

**Acceptance Criteria:**

**Given** a Quick Check of the faces the recent Moves touched (≤ 2 captures [ASSUMPTION])
**When** it returns a `Partial` observation
**Then** it is checked for internal consistency only and never completed from `expected`

**Given** `Assess`
**When** the observed faces cover every face any Move since the last Full verified observation could touch and exactly one candidate fits, and for a `Partial` that candidate is the empty sequence (a match)
**Then** Certainty is VERIFIED; otherwise LIKELY or UNKNOWN, and a scenario suite yields 0 false *verified*. A `Partial` mismatch with one consistent candidate is LIKELY: Sync State still moves to PHYSICAL_MISMATCH, that candidate stays the leading hypothesis, and a Guided 6-face Scan is offered. Only a Full observation verifies a mismatch *(SCP 2026-11-17: out-of-model errors made about 0.14% of Partial VERIFIED mismatches false)*

**Given** a stale result (the Learner confirmed a Move during the scan)
**When** it arrives
**Then** it is discarded, and if the Learner started it, "Your cube changed while scanning — scan again?" is offered

### Story 10.7: Quick Check in Physical-cube Mode and playback

As a Learner,
I want to check my cube at key moments and recover from what the scan shows,
So that I fix the real problem.

**Refs:** FR-26, FR-9, FR-12, FR-16, FR-8 · AD-3, AD-5 · UX-DR4, UX-DR12, UX-DR13, UX-DR20, UX-DR25 · UX Flows 1, 3, 4

**Acceptance Criteria:**

**Given** physical mode
**When** an Objective boundary, a Stage milestone or a mismatch report occurs
**Then** *Quick Check* is offered (never required; *Done* still works), and it is the first rung of the mismatch ladder, inserted above the Story 12.8 rungs without changing their tests *(SCP 2026-11-14)*

**Given** a matching result
**When** shown
**Then** a ✓ *Verified* chip and a VERIFIED frame appear until the next *Done* (on Solution Playback the chip only, with no frame label; SCP 2026-11-19); a mismatch shows the SEEN strip with differing stickers outlined, never a whole cube from a Partial

**Given** a Full observation in mismatch
**When** *Use my cube as it is now* is chosen
**Then** `AdoptObserved` replaces the Expected State and starts a Guided recovery; a Partial must first be completed by a Guided Scan or the Editor

**Given** physical Solution Playback or the Stage 6 end
**When** the Learner runs a Quick Check
**Then** it is available at any step, and a verified solved cube triggers the first-solve milestone (recorded only when true, Story 12.7); *Scan it* now appears on the Confirm-your-cube card next to the Story 8.7 camera wording, and Stage 6's first-solve prompt switches to its camera copy only together with the camera check action *(SCP 2026-11-16 main-app entry points; Story 10.5 keeps the camera-free copy until then)*

**Given** `CAMERA_SCAN` is OFF *(SCP 2026-11-14)*
**When** any physical-mode screen renders
**Then** no Quick Check entry, chip or rung appears, and the Story 8.7 / 12.10 tests pass unchanged

### Story 10.8: Recognition test matrix and GA/BETA decision

As the developer,
I want the scan features measured against their targets before release,
So that each ships with an honest GA or BETA label.

**Refs:** FR-21, FR-26 · recognition NFRs · AD-14, AD-18 · OQ-8 · PRD §7 gate 6

**Human gate:** physical devices, both test cubes and the four lighting conditions (device session C).

**Acceptance Criteria:**

**Given** the OQ-8 matrix (stickered and stickerless cubes × daylight, warm indoor, cool LED, low light × Lenovo Tab M10 Plus 3rd Gen (minimum, gating), Galaxy S24 FE (mid, gating), Galaxy Tab S7 FE (compatibility, reported)) *(SCP 2026-11-14: devices named)*
**When** Guided 6-face Scan, Quick Check, and each Epic 11 feature that is built are run with recorded ground truth
**Then** accuracy, corrections, time and confidently-wrong rates are reported against the §4.8 targets in `docs/benchmarks.md` (or `docs/spikes/oq-8-recognition-matrix.md`), and the report states the Story 10.6 known residual risk *(SCP 2026-11-17)*: a constructed face-preserving sequence (e.g. `R U2 R'` with plan faces {B, D}) can make a Partial match falsely VERIFIED (1 of 44 such sequences in 123,500 tried). The report gives its measured rate in the Quick Check scenario suite and a recommendation

**Given** the results
**When** a target is missed
**Then** the story records a per-feature recommendation (OFF, BETA or GA) for the PO in the PRD memlog, and every scan flow is verified to have a working manual fallback; the release level itself is set only by Story 10.11 *(SCP 2026-11-14: was "`CAMERA_SCAN` is set to BETA for release")*

### Story 10.9: Keep camera code out of a release whose camera flags are OFF

As the developer,
I want a release with every camera flag OFF to contain no camera code, dependency or permission,
So that camera work can merge to `main` while tester-fix releases stay exactly as camera-free as v1.0.

**Refs:** NFR-5, NFR-8, NFR-11 · AD-9, AD-13, AD-14, AD-15, AD-18 · Stories 12.10, 13.3 · `sprint-change-proposal-2026-11-14-v1-1-camera.md` §4.2

**Runs first** in Epic 10; no CameraX code merges to `main` before it.

**Acceptance Criteria:**

**Given** `FeatureFlagLevels.byBuildType` as the only source of flag levels
**When** a build type has at least one of `CAMERA_SCAN`, `QUICK_SCAN`, `PLACEMENT`, `CASE_RECOGNITION` above OFF
**Then** `build-logic` adds the camera source set (`app/src/camera/kotlin`, `app/src/camera/AndroidManifest.xml` with `CAMERA` and `uses-feature android.hardware.camera.any required="false"`) and the CameraX dependencies to that build type only; otherwise neither is added

**Given** today's levels (debug GA; release and benchmark OFF)
**When** `./gradlew spotlessCheck build` runs
**Then** the release and benchmark merged manifests declare no `CAMERA` and no camera `uses-feature`, `releaseRuntimeClasspath` contains no `androidx.camera:*` and no ML or CV runtime (`com.google.mlkit:*`, `com.google.ai.edge.litert*`, `org.tensorflow*`, `org.opencv*`), `docs/privacy-facts.json` is unchanged, and CI's "Verify build wires the gates" step lists the new check

**Given** `ManifestPolicy`
**When** a non-debuggable variant's build type has a camera flag above OFF
**Then** it allows `CAMERA` and nothing else; with every camera flag OFF it still allows nothing (Story 12.10 behavior unchanged), and the rule still keys on `variant.debuggable`

**Given** Gradle TestKit fixtures
**When** `./gradlew -p build-logic test` runs
**Then** (a) all camera flags OFF plus a `CAMERA` in the release manifest fails, (b) all OFF plus `androidx.camera` on the release classpath fails, (c) `CAMERA_SCAN=BETA` in release passes with `CAMERA` and produces privacy facts with `runtimePermissions: ["android.permission.CAMERA"]`, (d) `PLACEMENT` or `CASE_RECOGNITION` above OFF while `CAMERA_SCAN` is OFF fails with a message naming the flag (§6 Q6)

**Given** `:app` main code
**When** it needs a scan
**Then** it reaches the camera only through a port with an optional Hilt binding (`ScanLauncher` or equivalent, named in the AD-9 update) that the camera source set provides; with no binding, every scan entry point is hidden, and a unit test proves a flag above OFF without a binding fails at startup in debug

### Story 10.10: Live recognition baseline and fixture recorder

As the developer,
I want the live CameraX pipeline measured on the reference devices with recorded ground truth,
So that Epic 10 builds on real numbers, not the offline JPEG estimate.

**Refs:** FR-21, FR-24 · recognition NFRs, NFR-11 · AD-9, AD-14, AD-15 · OQ-7, OQ-8 · Story 1.9 · `docs/spikes/oq-4-guided-scan.md`

**Depends on** Story 10.9. **Human gate:** the Lenovo Tab M10 Plus 3rd Gen and the Galaxy S24 FE (Galaxy Tab S7 FE optional), the stickered and the stickerless test cube, normal indoor light, and the PO's go/no-go on the result (device session A).

**Acceptance Criteria:**

**Given** the Story 1.9 `GuidedScanActivity` moved into the debug-only camera source set as the fixture recorder (AD-9 "Frames", AD-15)
**When** a face is captured
**Then** it exports `FaceSample` JSON plus independently entered nine-sticker ground truth, device model, OS build, cube and light description into `Fixtures/live/<device>_<cube>_<light>/`, and no release variant contains the recorder

**Given** both cubes × normal indoor light × the two gating devices, six faces each
**When** the prototype nearest-calibrated-center CIELAB classifier runs on the recorded samples
**Then** per-sticker accuracy, per-face accuracy and confidently-wrong counts are written to `docs/spikes/oq-4-guided-scan.md` § Live baseline, and the Story 1.9 ≥ 99% live gate is recorded as met or missed

**Given** the result
**When** the story closes
**Then** the PO records go (continue with 10.1), go-as-BETA-only, or re-plan in the PRD memlog, and OQ-7's Kotlin-first Quick Scan recommendation is confirmed or revised with its size cost

### Story 10.11: Raise a camera feature above OFF for a release

As the product owner,
I want one checklist that takes a camera feature from OFF to BETA (or GA) for a release,
So that the app, the public privacy policy, Play Console and the store listing all say the same thing on the day it ships.

**Refs:** NFR-5, NFR-8, NFR-11 · AD-13, AD-15, AD-18 · PRD §7 gate 6 · Stories 10.8, 12.3, 12.4, 13.3, 13.4, 13.6 · `docs/release.md`, `docs/play-first-publish.md`

**Runs** once per release in which at least one camera flag leaves OFF; it is reopened (or copied as 10.11-n) for each such release. **Depends on** Story 10.8's recommendation, or a PO level decision recorded in the PRD memlog with its evidence, for every raised feature, and on Stories 12.3, 12.4 and 13.6 being done. *(SCP 2026-11-24 camera-release-timeline: 12.5 removed, superseding §6 Q5. A run may ship to the internal, closed and open testing tracks before 12.5. Production stays in 12.5, as a new `v1.0.0` tag cut from main with the same flag levels. A run that raises `QUICK_SCAN` also depends on: the new Quick Scan port stories being done with their BETA call recorded, the Lenovo TB128XU staying the gating minimum unless a separate PO decision changes it; the spike session's SCP amending the architecture spine and PRD (AD-9 reader of record, AD-18 composition, `ShadowReaderPolicy`, `CameraSizePolicy`, FR-22 and §4.8's out-of-scope "continuous video"); and `:app:checkCameraSize` and `:app:checkReleaseSize` headroom measured with the new models in (the research estimate is about 2.0–2.5 MB).)* *(SCP 2026-11-24 continuous-quick-scan: the spine/PRD/UX amendments are applied and the 3-device R8 benchmark is closed in PR #366 with Lenovo TB128XU kept as gating minimum (`33.74 ms`, ~30 FPS). Raising `QUICK_SCAN` now depends specifically on Stories **10.30–10.33** being done, including Story 10.31/10.33's minified `0.11.0`-profile size check (`< 30 MB`) and Story 10.33's PO BETA call recorded in the PRD memlog.)* **Human gates:** the PO's per-feature level decision; the site-repository PR; Play Console.

*(SCP 2026-11-21 quick-scan-side-face-accuracy: the first run raises `CAMERA_SCAN` and `PLACEMENT` only; `QUICK_SCAN` is raised by a later 10.11-n once Story 10.21's BETA call passes.)* *(SCP 2026-11-24 camera-release-timeline, superseding SCP 2026-11-21: the first run, release 0.11.0, raises `CAMERA_SCAN` to GA and `PLACEMENT` to BETA, following Story 10.8, and `QUICK_SCAN` to BETA only when the `QUICK_SCAN` preconditions under Depends hold (Stories 10.30–10.33 done and 10.33 BETA call recorded, SCP 2026-11-24 continuous-quick-scan). Otherwise it raises `CAMERA_SCAN` and `PLACEMENT` only, and `QUICK_SCAN` follows in a 10.11-n (0.11.x); the PO decides at 10.11 start. `CASE_RECOGNITION` stays OFF in 0.11.0.)*

**Acceptance Criteria:**

**Given** the PO's decision recorded in the PRD memlog
**When** the release-prep PR moves the features into `FeatureFlagLevels.releaseBeta` (or `releaseGa`)
**Then** the build composes the camera source set into release, `:app:writePrivacyFacts` regenerates `docs/privacy-facts.json` with `runtimePermissions: ["android.permission.CAMERA"]`, `FeatureFlagLevelsTest`'s release expectation is updated, `:app:checkReleaseSize` stays below 30,000,000 bytes, and the PR is the last one merged before the `vX.Y.Z` tag *(SCP 2026-11-24 camera-release-timeline: for the first run that tag is `v0.11.0`; the production `v1.0.0` tag is cut later from main by Story 12.5 with the same flag levels)*

**Given** the site repository `hebelmx/cubentia-site` (never renamed; publisher "Exxerpro Solutions, S.A. de C.V."; contact `exxerpro@exxerpro.com`)
**When** its privacy page is updated before the app PR merges or, while `APP_REF` is pinned to an earlier tag (`v0.10.1` as of 2026-11-24), before the `vX.Y.Z` tag; `APP_REF` moves to the new tag only after its release run passes (`docs/release.md` § Point the website at the release) *(SCP 2026-11-24 camera-release-timeline: replaces "right after the app PR merges", which fails the site check whenever `APP_REF` is `main`)*
**Then** `/privacy/` states, in plain language and with a new effective date: the app asks for one runtime permission, the camera, only when the Learner first opens a scan; camera frames are processed in memory on the device and are never stored, saved as photos or sent anywhere; the app still has no internet permission; every scan feature has a manual alternative; and the site's `check` job passes against the new `privacy-facts.json` before `deploy`

**Given** Play Console
**When** the release is prepared
**Then** the PO re-confirms Data safety as "No data collected" (camera frames are processed only on the device and never leave it), re-saves it if Console asks after the new permission, and records the date in the release issue; `docs/play-first-publish.md:29,49` and `docs/release.md` say "no runtime permission except the camera when a camera feature ships"

**Given** the release build
**When** Learners see a raised feature
**Then** the camera-rationale copy and the camera BETA tooltip come from content strings, the store listing's description names each raised feature, with "(beta)" only for a BETA feature (the GA Guided 6-face Scan carries no beta label), and says the camera reads colors on the device and nothing is saved or sent, and PRD §7 gate 6 is checked for each raised feature (manual fallback works; no unvalidated Scan Result is used), linking 10.8's evidence or, where 10.8 has none, the PO's recorded evidence; and `docs/pilot-session-protocol.md` adds a scan task for each raised feature (a Guided Scan, a Placement run, and for `QUICK_SCAN` a Quick Scan with the beta note read aloud), so the pilot records SM-8 *(SCP 2026-11-24 camera-release-timeline)*

**Given** a feature that stays OFF
**When** the release ships
**Then** it has no entry point, string or permission rationale visible to Learners (UX-DR19)

**Given** a feature being raised *(SCP 2026-11-16 main-app entry points)*
**When** the checklist runs
**Then** its entry point is wired in the main app and was accepted on the main app build, and no debug-only launcher or debug-only source set still holds part of the feature (developer tools such as the fixture recorder excepted, AD-15)

### Story 10.12: Fixture recorder a phone operator can finish quickly

As the developer recording fixtures,
I want the recorder to fit one phone screen and take few, safe touches,
So that a device session covers more cubes and lights with fewer input errors.

**Refs:** FR-21, FR-24 · AD-9, AD-15 · OQ-7, OQ-8 · Story 10.10 · SCP 2026-11-15 · UX pass 2026-11-15 (EXPERIENCE.md Scan rows)

**Depends on** Story 10.10 and the 2026-11-15 UX pass. **Human gate:** device session A2 (Lenovo Tab M10 Plus 3rd Gen and Galaxy S24 FE; cubes stla, stka, stlb, stkb; studio light), recorded with this recorder.

**Acceptance Criteria:**

**Given** a phone in portrait (Galaxy S24 FE)
**When** the recorder is on a face
**Then** the viewfinder, the 3×3 truth grid, the color picker and Capture are visible together without scrolling, following the UX pass layout

**Given** truth entry
**When** the operator labels a face
**Then** a color is chosen once and applied by tapping stickers (no tap-to-cycle). A high-confidence prediction may prefill a sticker; close-call stickers stay blank (two calibrated centers within the UX-pass threshold, e.g. yellow vs white). Each sticker's truth is stored as `entered` or `prefilled`

**Given** the evaluator
**When** it scores fixtures
**Then** it reports accuracy over all stickers and over `entered` stickers only

**Given** the capture-quality override
**When** the operator sets it
**Then** it sits apart from the truth grid and needs a confirm, and Capture shows the active flag

**Given** calibration of a center
**When** the metered swatch is implausible for its color (for WHITE, chroma or lightness outside the UX-pass bounds), or the center cell is not uniform (a logo)
**Then** the recorder warns and asks for a plain sticker of that color in the center cell, and the swatch is shown before it is saved

**Given** a face is selected or the cube is realigned
**When** the preview is shown
**Then** grid AF/AE/AWB re-meters automatically (Re-meter stays available)

**Given** a capture
**When** it is saved
**Then** a downscaled frame (JPEG, long edge ≤ 1024 px) is written next to the face JSON in `Fixtures/live/<device>_<cube>_<light>/`, and no release or benchmark build contains the recorder or writes frames

### Story 10.13: Choose the recognition approach (OQ-7)

As the developer,
I want Kotlin, OpenCV and an on-device ML model compared on the same live fixtures,
So that Epic 10 builds on the approach with the best accuracy, effort and size trade-off.

**Refs:** FR-21, FR-24, FR-27 · recognition NFRs, NFR-8, NFR-11 · AD-9, AD-14, AD-18 · OQ-7 · Stories 10.10, 10.12, 11.1

**Depends on** Story 10.12 (session A2 frames).

**Acceptance Criteria:**

**Given** the session A and A2 fixtures (samples and frames)
**When** the current Kotlin nearest-center classifier, an OpenCV pipeline and an on-device ML model (bundled LiteRT or equivalent; no ML Kit or Play-services ML, NFR-8) are run in a non-shipping evaluation source set
**Then** per-sticker accuracy, confidently-wrong rate, sensitivity to calibration error (the 2026-11-15 bad-white case), logo-center handling and added release size (checked against NFR-11's 30 MB) are reported in `docs/spikes/oq-7-recognition-approach.md`

**Given** the report
**When** the story closes
**Then** the PO records the OQ-7 decision in the PRD memlog, and the PRD OQ-7 line and the spine's Quick Scan detection-method line are updated to match. The release classpath gate (Story 10.9) is unchanged unless the decision adds a runtime, and then it is changed in this story.

### Story 10.14: Scan each face in any rotation

As a Learner,
I want to show each face however it happens to be turned,
So that I don't have to work out which side goes on top.

**Refs:** FR-21, FR-23 · recognition NFRs, NFR-10 · AD-2, AD-9 · UX-DR15 · Stories 10.2, 10.3 · #158

**Depends on** Stories 10.2 and 10.3. **Human gate:** device session B2 on the Lenovo Tab M10 Plus 3rd Gen and the Galaxy S24 FE, with a stickered and a stickerless cube. *(SCP 2026-11-16: PO idea from device session B. Per-face retake from review is a separate story: Story 10.16, SCP 2026-11-19, #235.)*

**Acceptance Criteria:**

**Given** the Guided 6-face Scan
**When** a face is prompted
**Then** the prompt names only the center color with its glyph swatch ("Show the **green** center"), the fixed face order and the live repeated-face rule of Story 10.2 (AC4) are unchanged, and the captured `FaceSample` carries no `upNeighborColor`

**Given** six captured faces with no up neighbor
**When** `ScanAssembler` assembles them
**Then** it tries the four quarter turns of every face (4⁶ assignments, centers fixed) on the classified colors and keeps the `Facelets` that `CubeValidator` accepts; assignments that give the same `Facelets` count as one; `ScanAssembler` stays the only place samples are rotated (AD-9)

**Given** the session A and A2 live fixtures, each face turned by a random quarter turn (fixed seed)
**When** the JVM suite assembles them
**Then** every scan whose prompted-pose assembly was valid yields the same `Facelets`, the share of scans needing a re-prompt is reported per device in `docs/scan-accuracy.md`, and one assembly takes under 50 ms on the JVM

**Given** no valid assignment, or two or more distinct valid states
**When** assembly ends
**Then** the scan never guesses: it re-prompts only the faces involved with the Story 10.2 prompt ("Show the **green** center, with **white** on top"), first any face whose center read disagrees with its prompt, then the faces whose turn differs across the valid states or that hold the illegal pieces of the assignment with the most legal pieces; assembly then uses the prompted turn for those faces, and if the state is still invalid the result goes to Story 10.3 review as today, with no further loop. *(SCP 2026-11-19 scan re-prompt cap, #164: PO decision at device session B2, 2026-11-16. When no assignment is valid, the scan re-prompts only when at most 2 faces are involved, counting faces whose center read disagrees; with more, it re-prompts nothing and goes straight to Story 10.3 review with the best-fitting turns (the assignment with the most legal pieces), the faces that differ across the tied best fits left blank. So many faces point to misread colors, which a named top can't fix. Two or more valid states still re-prompt the faces that differ, as built: `ScanAssembler.MAX_REPROMPT_FACES`.)*

**Given** the prompt and re-prompt copy
**When** they render
**Then** they come from content strings (NFR-10), and on a phone in portrait the viewfinder and prompt still fit without scrolling

**Given** the debug fixture recorder
**When** it records
**Then** it keeps the prompted top, so fixture ground truth keeps a known pose

### Story 10.15: Solve from a scan

As a Learner who just wants it solved,
I want to scan instead of typing colors and go straight to the solution,
So that I go from scrambled to solving in under a minute.

**Refs:** FR-25, FR-15, FR-16 · AD-8, AD-9, AD-18 · UX-DR19, UX-DR25 · UX Flow 3 · Story 10.5

**Depends on** Story 10.5. *(SCP 2026-11-16 main-app entry points: the remainder of the original Story 10.5 "Scan to solve".)*

**Acceptance Criteria:**

**Given** the Solve tab
**When** *Scan my cube* is chosen and the scan is accepted
**Then** scan → review (live validation) → *Accept* → solve → playback runs without leaving the flow, and *Enter colors instead* is always visible; validation gates the review's *Accept* (UX-DR16) and is not a separate step after it, and *Accept* is the only confirmation, so no Manual Cube Editor or second net opens between the review and Solution Playback *(SCP 2026-11-19, #203; until then the flow read "scan → review → validate → solve → playback")*

**Given** the Solve tab and an accepted scan that is already solved *(SCP 2026-11-19, #203)*
**When** the review's *Accept* is tapped
**Then** the existing already-solved state shows ("Your cube is already solved. Scramble it and try again, or learn to solve it yourself."), the same one the Editor's *Accept* shows, and the Solver is not called

**Given** *Enter colors instead*, an invalid or incomplete scan (`FellBackToEditor`), or denied camera permission *(SCP 2026-11-19, #203)*
**When** the scan flow returns to the Solve tab
**Then** the Manual Cube Editor opens as the fallback, as Story 10.5 says

*Amended by SCP 2026-11-24 github-issue-triage (#321), built as an issue fix through `/bmad-orchestrate-issues`; v1.0 scope (PO 2026-11-24: pulled into the current release, its v1.1 deferral removed); acceptance on the Lenovo Tab M10 Plus 3rd Gen and the Galaxy S24 FE, main app build, from the Solve tab:*

**Given** a Guided or Quick scan from the Solve tab that captured one or more faces (a reviewed scan that falls back, or a partially read scan, including *Enter colors instead* after faces were captured)
**When** it returns `FellBackToEditor`
**Then** the Manual Cube Editor opens seeded in the home frame with every sticker the scan read and every fix the Learner made in review, with the one-line note "Your scan is here. Fill in the stickers it couldn't read." (a new content string); stickers the scan did not read, and every sticker of a captured face whose rotation could not be worked out, are unset (not seeded), and a face holding one counts as not entered (Story 3.5's unset-sticker AC; EXPERIENCE.md *Invalid entered state*); the stickers travel in the typed `ScanOutcome` (Story 10.3), never filled from an expected state; *Accept* stays gated by the Editor's validation (Story 3.5); only an accepted scan counts as the Full verified baseline (Story 10.7 rule, unchanged)

**Given** denied camera permission, or a scan with no captured face
**When** the Editor opens
**Then** it opens blank with its one-line note "No scan this time. Enter your cube's colors here." (`msg.solve.scan-fell-back`), as today; that line is used only for the blank case

**Given** a scan fallback from any caller other than the Solve tab (Placement from Welcome → *Start from my cube* or Learn → *Find my place* → *Scan my cube*)
**When** it returns `FellBackToEditor`
**Then** its Editor opens blank, as today; seeding is the Solve tab's only

**Given** a scan accepted in review
**When** *Accept* is tapped
**Then** it still goes straight to solving and Solution Playback (#203, unchanged)

**Given** the Solve tab route tests
**When** they run
**Then** they cover review *Close* with no change (seeded Editor), review *Close* with a change and *Discard* (`Cancelled`, back on the Solve tab), a partially read scan, a captured face whose rotation is unresolved (left unset), a no-capture fallback and a denied permission (blank Editor with the old note), and a Placement fallback (blank)

**Given** a solve started from an accepted scan *(SCP 2026-11-19, #203)*
**When** the Learner cancels it, or the Solver times out or fails
**Then** the Manual Cube Editor opens with the scanned cube held in the home frame (`Orientation.HOME`, the same pieces), and its playback still counts as the Full verified baseline even if the Learner corrects colors there (the Story 10.7 rule); the scan stops counting only when the Editor is closed or the Learner leaves the Solve tab

**Given** `CAMERA_SCAN` is BETA
**When** the Solve tab renders
**Then** *Enter colors* is the primary action and scan carries the shared BETA label (the *Test me* component from #137, not a copy) and the camera tooltip "May misread colors in poor light. Manual entry always works." *(SCP 2026-11-14: names the shared component and the approved copy, EXPERIENCE.md:135)*

**Given** the Solve flow from a scan
**When** it is accepted on a device
**Then** it is run on the main app build from the Solve tab, never from a debug-only launcher

### Story 10.16: Retake faces from the scan review

As a Learner,
I want to scan again only the faces the camera misread,
So that I don't fix a whole face sticker by sticker or rescan all six faces.

**Refs:** FR-23, FR-14, FR-21 · recognition NFRs, NFR-10 · AD-3, AD-9, AD-13, AD-18 · UX-DR15, UX-DR16 · Stories 10.2, 10.3, 10.14, 10.15 · #235

**Depends on** Stories 10.3 and 10.14. **Human gate:** a device session on the Lenovo Tab M10 Plus 3rd Gen and the Galaxy S24 FE, on the main app build from the Solve tab. *(SCP 2026-11-19 review face retake, #235: PO decisions 2026-11-19. Guided 6-face Scan review only; retake in the Quick Scan review is a v1.1 follow-up noted with #198.)* *(SCP 2026-11-20: now v1.0, through Story 10.17, #198.)*

**Acceptance Criteria:**

**Given** the Guided 6-face Scan review (Story 10.3)
**When** the Learner taps *Retake faces*
**Then** the review net enters a retake mode: the instruction line reads "Tap each face to scan again.", tapping a face toggles it for retake (any 1 to 6 faces), a selected face is marked for sight and announced by TalkBack as "{Color} face, selected for retake" (for example "Green face, selected for retake"), sticker editing is off, and the primary action reads *Retake N faces* ("Retake 1 face" for one), enabled once at least one face is selected; *Cancel* leaves retake mode and clears the selection; outside retake mode, taps edit stickers as Story 10.3 says

**Given** faces selected for retake
**When** *Retake N faces* is tapped
**Then** the Guided Scan viewfinder asks for only those faces, in the fixed face order, each with the Story 10.14 any-rotation prompt ("Show the **green** center"), never the named-top re-prompt, and the mini net shows only those faces as left to capture

**Given** every selected face captured
**When** review reopens
**Then** every kept face shows exactly the stickers it had, including the Learner's corrections; each retaken face shows only its new capture, with any earlier edit on it dropped; prefill, blanks and pre-selection on the retaken faces follow the Story 10.3 close-call rule; and validation and *Accept* work as Story 10.3 says

**Given** the retaken faces
**When** they are assembled
**Then** `ScanAssembler` tries the four quarter turns of each retaken face with the kept faces held fixed, stays the only place samples are rotated (AD-9), and never reclassifies a kept face; when the turns are ambiguous (two or more valid states) or none is valid, review reopens with the best-fitting turns (the assignment with the most legal pieces, retaken faces that differ across tied best fits left blank, as the Story 10.14 cap rule does) and the usual validation messages, with no re-prompt

**Given** the retake viewfinder
**When** the Learner backs out before every selected face is captured
**Then** review reopens exactly as it was before the retake, with nothing replaced (all or nothing)

**Given** a completed retake
**When** the Learner taps *Close* on the review
**Then** it counts as a change, so *Close* asks "Discard this scan?"

**Given** a review reopened after a retake
**When** the Learner taps *Accept*
**Then** the result returns through the same `ScanOutcome` as any review, and the caller's path is unchanged (Story 10.15: solve and Solution Playback, the already-solved state, or the Editor fallback)

**Given** the retake copy
**When** it renders
**Then** it comes from content strings (NFR-10); at 200% font on the Lenovo's 600 dp width no word breaks mid-word and nothing is cut off; and on a phone in portrait the viewfinder and prompt still fit without scrolling

**Given** the story is accepted on a device
**When** it is run
**Then** it runs on the main app build from the Solve tab, never from a debug-only launcher, and no frame from the retake is persisted or sent (Story 10.2)

### Story 10.17: Switch from Quick Scan to the Guided Scan and keep the captured faces

As a Learner whose Quick Scan read some faces well,
I want to finish the rest face by face without starting over,
So that one bad corner photo doesn't cost me the faces that were already read.

**Refs:** FR-22, FR-21, FR-23 · recognition NFRs, NFR-10 · AD-3, AD-9, AD-13, AD-18 · UX-DR15, UX-DR16 · Stories 10.2, 10.14, 10.16, 11.1 · #198

**Depends on** Stories 11.1, 10.16 and 10.18 (Quick Scan finds the cube's real edges and corners, #195; added by this SCP and moved into v1.0 with it). **Human gate:** a device session on the Lenovo Tab M10 Plus 3rd Gen (TB128XU, the minimum camera spec) and the Galaxy S24 FE, on the main app build from the Solve tab, with `QUICK_SCAN` raised in debug. *(SCP 2026-11-20 v1.1 promotion, #198.)*

**Acceptance Criteria:**

**Given** `QUICK_SCAN` is BETA or GA and a Quick Scan in progress
**When** the Learner taps *Scan face by face* (visible before, between and after the corner captures)
**Then** every face already captured with all nine stickers read is kept, and the Guided 6-face Scan viewfinder asks for only the other faces, in the fixed face order, with the Story 10.14 any-rotation prompt; the mini net shows the kept faces as done

**Given** the kept faces and the newly captured faces
**When** they are assembled
**Then** `ScanAssembler` assembles all six together, tries the four quarter turns of each new face with the kept faces' poses held as captured, stays the only place samples are rotated (AD-9), and never reclassifies a kept face; ambiguity or no valid state goes to Story 10.3 review with the best fit, as Story 10.16 does, with no re-prompt loop

**Given** Quick Scan's own hand-over (uncertain faces after two corners, Story 11.1 AC2)
**When** it hands over
**Then** it uses this same path, so both entries behave alike

**Given** the Learner backs out of the Guided Scan before the remaining faces are captured
**When** they return
**Then** Quick Scan resumes with the kept faces intact, or *Close* asks "Discard this scan?" *(SCP 2026-11-24, #325: unchanged; the standalone Guided Scan viewfinder's own Close follows Story 10.2's discard rule)*

**Given** the combined review
**When** it renders
**Then** it is the Story 10.3 review (with retake, Story 10.16, now available in it), and *Accept* returns the same `ScanOutcome` to the caller

**Given** the copy
**When** it renders
**Then** it comes from content strings (NFR-10), fits a phone in portrait without scrolling, and no frame is persisted or sent

### Story 10.18: Quick Scan finds the cube's real edges and corners

As a Learner using Quick Scan,
I want the camera to find my cube however it appears in the frame, and a clear sign each corner photo was taken,
So that the two corner photos read my cube instead of sending faces to a rescan.

**Refs:** FR-22, FR-23 · recognition NFRs, NFR-7, NFR-11 · AD-9, AD-13, AD-18 · OQ-7 · UX-DR15 · Story 11.1 · #195, #196, #197

**Depends on** Story 11.1. **Runs before** Story 10.17 (an appended ID that runs earlier, as the How-to-read note allows). **Approach (amended PO 2026-11-20, SCP 2026-11-20 quick-scan-litert-escalation):** detect the cube's seven visible corners `[T, UL, LL, B, LR, UR, C]` in the camera frame using a tiny in-house quantized 7-keypoint `.tflite` model on LiteRT (`com.google.ai.edge.litert`, AD-9 pre-approved fallback, isolated in `app/src/camera/corner/` behind `CornerDetector`, gated on corner-model flags above `OFF`) and unwarp each face from those detected corners. Guided 6-face Scan stays pure Kotlin; OpenCV remains rejected on NFR-11; no perspective-correct hexagon guide sketch is drawn (minimal 3-line Y framing anchor + smoothed detected outline). **Human gate (acceptance):** on the Lenovo Tab M10 Plus 3rd Gen (TB128XU, the minimum camera spec): `LiteRtCornerDetectorDeviceTest` on the debug build (D1) and on the R8-minified `cornerR8` build (D3). *(SCP 2026-11-21 quick-scan-front-light: the BETA evaluation on both devices, real-capture model validation and the enforced side-face bar move to Story 10.19.)* *(SCP 2026-11-20 v1.1 promotion: moved into v1.0 as the dependency of #198, Story 10.17; ACs refined 2026-11-20 per PO review; LiteRT fallback activated 2026-11-20 after Lenovo `6cd18ab1` evidence.)*

**Acceptance Criteria:**

**Given** a cube held by a corner at 20–40 cm on the minimum device, roughly within the center framing zone
**When** a frame is analysed
**Then** the camera adapter detects the cube's real outer edges and its seven visible corners in the camera frame via the 7-keypoint LiteRT `CornerDetector` (with `CornerLocator.isValidCubeGeometry` + `CornerOverlaySmoother` guards), and unwarps each of the three visible faces from those detected corners, never from the fixed isometric `CornerGuide.quads`; the Learner never has to line the cube up with a drawn sketch, and no perspective-correct hexagon sketch is drawn (only the minimal 3-line Y anchor and detected cube outline)

**Given** the in-house 7-keypoint `.tflite` model and LiteRT runtime
**When** the app is built and checked in CI and on the minimum device
**Then** the model is trained from reproducible synthetic 3D cube renders (covering scrambled and solved stickered cubes, stickerless cubes, hands, cluttered desks, and dark glossy reflections for #196), with real Lenovo and S24 FE palettes and face crops (validation on real labelled corner captures moves to Story 10.19, SCP 2026-11-21); release installed size stays `< 30 MB` (NFR-11, single-ABI `arm64-v8a` accounting including transitive `litert` + `litert-api` + `.tflite`), `libLiteRt.so` passes 16 KB ELF page alignment (`targetSdk 36`), R8 release builds run inference cleanly, builds with corner-model flags `OFF` carry zero LiteRT classes/libs/assets, and on Lenovo TB128XU median inference stays `< 40 ms` per frame off the main thread while `CornerReader`/`unwarp`/geometry guards stay JVM-tested via `CornerDetector`

**Given** existing Quick Scan frames/evidence under `Fixtures/` and `docs/evidence/` (plus synthetic perspective-warped fixtures where real labeled frames are missing)
**When** the developer evaluates the available fixtures and runs the JVM detector suite
**Then** the detected corners are within a reported pixel tolerance of the labeled corners on synthetic fixtures, and a replay of real corner captures (`Fixtures/corner/`) through the production Quick Scan side-face readout reports, per cube, light and face, the stickers read correctly, left uncertain and read wrong, with Red↔Orange confusions counted separately; the baseline is recorded in `docs/evidence/10-18/` *(SCP 2026-11-21: the enforced side-face bar moves to Story 10.19)*

**Given** a dark or glossy background (#196)
**When** no corner is detected for about 3 s
**Then** inline coaching (from `en.json`) reads "Try a plain, light surface." alongside the existing manual/guided fallback (Story 10.17 later wires *Scan face by face* keeping captured faces)

**Given** each corner capture (#197)
**When** it is taken
**Then** a visible cue shows on both captures (a subtle highlight/flash avoiding harsh strobe, and a thumbnail of the captured corner, with the counter change enlarged), the same shutter sound plays on both, and nothing relies on haptics alone

### Story 10.19: Quick Scan reads the side faces in good light

As a Learner using Quick Scan,
I want the app to help me light my cube and read its shaded sides with my own colors,
So that both corner photos read all three faces instead of sending me face by face.

**Refs:** FR-22, FR-23, FR-24 · recognition NFRs (Quick Scan, Confidence calibration), NFR-10, NFR-11 · AD-9, AD-13, AD-18 · OQ-8 · UX-DR15 · Stories 10.4, 10.8, 10.17, 10.18, 11.1 · #195, #196

**Depends on** Stories 10.18 and 10.17 (*Scan face by face* is the exit from a held capture). **Human gate:** a device session on the main app build, `QUICK_SCAN` raised in debug, on the Lenovo Tab M10 Plus 3rd Gen (TB128XU, no flash) and the Galaxy S24 FE (flash), with a stickered, a stickerless and a glossy cube, light from the camera side and light behind the cube. *(SCP 2026-11-21 quick-scan-front-light. Out of scope by PO decision: a side-face calibration step, and turning uncertain side stickers into suggestions. Numeric thresholds below are proposed in the SCP and confirmed by the PO at approval.)*

*(SCP 2026-11-21 quick-scan-side-face-accuracy, #274: 10.19 merged as the Quick Scan lighting pipeline (PR #272). The AC2 counts, AC4 glossy-cube glare, AC6 side-face bar, AC7 held-out metrics and AC8 BETA call move to Stories 10.20 and 10.21; the provisional light cuts (27 L*, 1.34) are refitted in 10.21.)*

*(SCP 2026-11-22 quick-scan-reliability, PO decision: from Story 10.22 the light check of AC1 only coaches; it no longer blocks auto-capture or the manual shutter.)*

**Acceptance Criteria:**

**Given** the corners are detected and the side faces are much darker than the top face or the background (the light is behind the cube)
**When** frames are analysed in the corner capture view
**Then** the light check is part of "aligned and steady": while it fails, neither auto-capture (UX-DR15, about 600 ms aligned and steady) nor the manual shutter takes a photo; after about 1 s of failing, inline coaching from `en.json` reads "Turn so the light shines on the cube."; the hold clears once the side faces pass for about 0.5 s while steady, and auto-capture then restarts its normal count; *Scan face by face* (Story 10.17) stays visible and keeps any corner already captured; the threshold comes from the recorded captures

**Given** the main app on each device
**When** a tester starts a Quick Scan with the light behind the cube, then turns so the light falls on the cube
**Then** the capture is held with the coaching shown, and resumes and captures after the light is corrected; across at least 10 front-lit attempts per device in ordinary room light the light check never blocks (false blocks), and across at least 10 backlit attempts it always blocks (missed blocks); both counts are recorded in the device evidence

**Given** a detected cube outline
**When** frames are analysed
**Then** the camera adapter meters on the detected cube region, not the whole frame: auto-exposure and focus on both holds, and white balance on Hold 1 only (Hold 2's grid has no white reference, as in Story 10.18); before metering, the adapter checks that regional metering is supported (`CameraInfo.isFocusMeteringSupported`) and falls back to center metering when it is not; a capture waits until the camera has settled after a metering change (settle signal defined in the spec: exposure converged, or a fixed settle time measured on both devices); support and settle times on both devices are recorded; pixels and camera control stay in `app/src/camera/` (AD-9); no frame is stored

**Given** a device whose back camera has a flash unit
**When** the corner capture view is open
**Then** the torch is on by default during corner capture, with a visible control to turn it off; the Learner's choice carries over from Hold 1 to Hold 2; the torch turns off on *Scan face by face*, on leaving the capture view and when the app goes to the background; on a device without a flash (the Lenovo) no torch control or copy appears; on the S24 FE a glossy cube is captured in both holds with the torch on and off, and glare caused by the torch is detected and recoverable (turning the torch off clears it)

**Given** saved color calibration (Story 10.4)
**When** Quick Scan classifies the stickers
**Then** it uses the saved calibration when one exists, and self-calibrates from the face centers when none exists; because calibration is taken with each face flat to the camera while corner captures see the side faces at an angle and in shade, each captured face is also corrected against its own known center sticker before classification; the replay covers no saved calibration, a matching calibration, a frontal calibration applied to angled side faces, and calibrations made under different light and on a different cube, reports results per color (Yellow and Orange are the most affected, PO 2026-11-21), and in every mismatched case the result is uncertainty or fallback, never a confidently wrong sticker; the Guided 6-face Scan's behavior is unchanged

**Given** front-lit real corner captures under `Fixtures/corner/`, from the Lenovo and the S24 FE, with at least one stickered and one stickerless cube under at least two lights
**When** `QuickScanSideFaceReplayTest` runs in CI with its bar enforced
**Then** results are reported and enforced **per device and lighting condition**, never pooled: 0 confidently wrong side stickers (0 Red↔Orange confusions; Confidence calibration NFR), at least 90% of side stickers read correctly, and at least 3 of every 4 side faces read without being sent to the Guided grid for any reason; this replaces Story 10.18's original "no forced R/L fallback" wording, which demanded zero Red/Orange uncertainty; backlit captures are reported, not scored, and must trip the light check

**Given** the debug fixture recorder in corner mode
**When** an operator records corner captures
**Then** it shows the Y anchor and the live detected outline, lets the operator place the 7 corners on the frozen frame by hand (the detector's points are saved separately for diagnostics and are never used as labels), keeps positive frames even when the detector misses, records no-cube (negative) captures, starts the camera right after the permission is granted, and never silently overwrites a saved capture group; `evaluate_model.py --fixtures-corner` on held-out captures, with at least 20 positives and 10 negatives per device, meets recall ≥ 92%, rejection ≥ 96% and mean corner error ≤ 3% of the cube outline's diagonal (validation moved from Story 10.18)

**Given** a hands-on evaluation on both devices after this story
**When** the PO compares two-corner completion on the Lenovo before and after
**Then** the PO confirms whether Quick Scan is viable as `BETA` for v1.0 (moved from Story 10.18); the release level itself is decided by Story 10.8's full recognition matrix and raised by Story 10.11

### Story 10.20: Record 10.19-pipeline corner fixtures and check the corner model

As the developer tuning Quick Scan,
I want labelled corner captures recorded through the 10.19 pipeline on both reference devices,
So that the keep rule, the agreement gate and the light cuts are fitted and scored on real data, per device and light.

**Refs:** FR-22, FR-24 · recognition NFRs (Quick Scan, Confidence calibration) · AD-9, AD-15 · OQ-8 · Stories 10.4, 10.10, 10.12, 10.18, 10.19 · #274, #166

**Depends on** Stories 10.19 and 10.22 (fixtures are recorded under 10.22's exposure handling). **Human gate:** one recording session on the debug fixture recorder (corner mode), on the Lenovo Tab M10 Plus 3rd Gen (TB128XU, no flash) and the Galaxy S24 FE (flash). *(SCP 2026-11-21 quick-scan-side-face-accuracy; SCP 2026-11-22 quick-scan-reliability.)*

**Acceptance Criteria:**

**Given** the debug fixture recorder in corner mode
**When** an operator records a capture group
**Then** the group's `group.json` records `"pipeline": "10.22"`, the device model, the cube id (`stla`/`stlb`/`stka`/`stkb`, or a new id for a glossy cube), the light direction (`front`/`back`/`side`), the light name, the torch state per hold, and the recorder calibration it took. On a device with a flash, the recorder offers the same torch toggle as the Quick Scan corner view (on by default), and its calibration is taken with the torch state the operator chose. Each hold stores the 10.19 per-capture diagnostics (`light=`, `lightScore=`, `lightMode=`, metering settle) next to the frame. The recorder's burst (`RecorderBurst`, PR #289) stays a debug-only tool; each burst line also carries the light score and the three face levels, and `RecorderBurst.line` has a JVM test *(SCP 2026-11-22 quick-scan-reliability)*.

**Given** the recording session
**When** it ends
**Then** `Fixtures/corner/` holds, **per device**: a stickered, a stickerless and (on the S24 FE) a glossy cube; at least two front lights and one backlit group; on the S24 FE, groups with the torch on and with it off; and a held-out split, named in the group index and never used for tuning in Story 10.21, that on its own holds at least 20 positive and 10 negative (no-cube) captures per device and at least one group for every scored device, light and torch condition; the tuning split holds at least one other group for each of those conditions

**Given** the held-out split
**When** `uv run --project tools/corner-model python tools/corner-model/evaluate_model.py --fixtures-corner Fixtures/corner` runs
**Then** it reports, per device, recall ≥ 92%, rejection ≥ 96% and mean corner error ≤ 3% of the outline diagonal (10.19 AC7). A miss is recorded in the evidence and routed to the PO, and does not block Story 10.21 unless the PO says so.

**Given** a pastel stickerless cube (a new cube id) recorded in the same session, with its recorder calibration *(SCP 2026-11-22, #166: the pastel-cube check deferred from Story 10.4)*
**When** `ColorCalibrationTest`'s fixture-plausibility check runs on its swatches and stickers
**Then** they pass `ColorPlausibility`, or the chroma floors in `ColorCalibration.kt` are tuned so they pass while the standard-fixture checks still pass and implausible swatches are still refused; the result and any new floors are written to `docs/evidence/10-20/` (FR-24: pastel cubes work after calibration) *(SCP 2026-11-23 pastel-plausibility: the session's result is one Blue sticker refused by the fixed windows; the fix moves to Story 10.27, which judges stickers against the cube's own centres instead of tuning the floors.)*

**Given** `QuickScanSideFaceReplayTest`
**When** it runs in CI on the new groups
**Then** it reports, without enforcing the bar, the current (10.19) results per device, light and torch state: side stickers correct, uncertain and wrong, side faces kept, and the keep reason per face (`closeCall`/`disagree`). This is Story 10.21's baseline, written to `docs/evidence/10-20/`. The report also counts confidently wrong stickers among all CONFIDENT stickers, on kept and discarded faces alike, per group. Backlit groups are captured with the light check failing, and their light scores are recorded *(SCP 2026-11-22 quick-scan-reliability)*.

### Story 10.21: Quick Scan keeps the side faces it reads well

As a Learner using Quick Scan,
I want the faces the app read well to stay read, with at most one sticker left for me to fill,
So that two corner photos usually finish the scan instead of sending me face by face.

**Refs:** FR-22, FR-23, FR-24 · recognition NFRs (Quick Scan, Confidence calibration), NFR-10, NFR-11 · AD-9, AD-18 · OQ-8 · UX-DR15 · Stories 10.4, 10.17, 10.19, 10.20 · #274

**Depends on** Story 10.20 (fixtures and held-out split) and Story 10.22; it tunes on the labels corrected by the PO review of 2026-11-23 (all 23 corner groups count 9 of each colour), does not wait for Story 10.27, starts once `docs/device-session-2026-11` (which carries those labels) is merged, and, once Story 10.27's shared fixture filter exists, uses it to skip the stickers and groups 10.27 excludes; every tuning and held-out result records the label-set commit and the `Fixtures/corner/label-exclusions.json` hash, and the baseline is re-run when 10.27's exclusions land *(SCP 2026-11-23 pastel-plausibility, PO Q1, revision 4)*. **Human gate:** a device session on the main app build, `QUICK_SCAN` raised in debug, on the Lenovo TB128XU and the Galaxy S24 FE with a stickered, a stickerless and a glossy cube, front-lit and backlit. *(SCP 2026-11-21 quick-scan-side-face-accuracy; PO decisions 2026-11-21: keep rule ≤ 1 blank with a matching calibration; calibration torch with a torch tag; Story 10.11 does not wait for this story.)*

**Acceptance Criteria:**

**Given** a saved calibration that matches the scan: the same torch state (AC2), and every face centre captured so far reads CONFIDENT as its own colour against the saved calibration (a calibration from another cube or another light fails this check)
**When** a corner capture's face is classified
**Then** the face is kept when at most **1** of its stickers is uncertain and none disagrees beyond the agreement gate. The uncertain sticker is left blank and pre-selected in the Quick Scan review, as the sticker review net already does (EXPERIENCE.md). A face with 2 or more uncertain stickers goes to the Guided grid, as before. **Without** a matching calibration (none, a torch mismatch, or a centre that fails the check, logged as `calibration=mismatch centre`), the Story 10.17 rule is unchanged: a face is kept only when all 9 stickers are CONFIDENT. A kept face never carries a confidently wrong sticker (Confidence calibration NFR).

**Given** the calibration screen (Story 10.4) on a device whose back camera has a flash
**When** the Learner calibrates
**Then** the screen shows the same torch toggle as the Quick Scan corner view, on by default, and the saved calibration records the torch state it was taken under (`ColorCalibrationEntity` gets a nullable torch column through a Room migration with a migration test; rows saved before it count as torch-off). Quick Scan uses a saved calibration only when its torch state equals the current one. Otherwise it self-calibrates as when no calibration exists, logs `calibration=mismatch torch`, and shows the 10.19 *Calibrate colors* offer. On a device without a flash, no torch control appears and the torch state is always off. The Guided 6-face Scan's use of calibration is unchanged.

**Given** the agreement gate between a face's self-calibrated and calibrated reads
**When** it is tuned against the Story 10.20 tuning split
**Then** its rule and thresholds are chosen per colour where the data supports it (Yellow, Orange and Red against Blue on the shaded back face), and the spec records the before and after numbers. No change turns an uncertain sticker into a confident wrong one on any group, tuning or held-out.

**Given** a corner hold in which almost no sticker reads CONFIDENT
**When** auto-capture would fire
**Then** it waits while fewer than **N** of the hold's 27 stickers read CONFIDENT. N is fitted on the Story 10.20 tuning split so that no positive tuning capture is held back for more than 10 s (Story 10.22); a starting value of 6 is scored on that split, and the chosen N and its numbers are recorded in the spec. While it waits, coaching shows the hold's prompt again, the manual shutter still captures, and `CubentiaScanDiag` logs `poseRejected reason=few-confident` with the count. The held-out replay reports how many holds the wait delayed and for how long. *(#302; SCP 2026-11-22 quick-scan-corners-and-solver-copy, PO Q-b: this gate lives here, beside the agreement gate, and not in Story 10.25.)*

**Given** the light check (10.19 AC1)
**When** it is refitted on the Story 10.20 captures
**Then** both cuts (uncalibrated L* difference, calibrated top-over-sides ratio) are refitted per mode with **hysteresis** (a hold starts above the hold cut and clears only below a lower clear cut), so an ordinary torch-off S24 FE light no longer flips hold and release. The light check is skipped only while the torch is **actually lit** (`enableTorch` succeeded), not merely requested (action item `torch-requested-vs-lit`). The light-clear window's steadiness rule is settled with the PO and matches the AC (action item `light-clear-steadiness`). Returning from calibration opened from the corner view clears a stale light hold, so the offer does not reappear at once (action item `cal-offer-reappears`). *(SCP 2026-11-22 quick-scan-reliability: since Story 10.22 the light check only coaches. The refit uses captures made under 10.22's exposure handling and sets when the coaching line shows and hides; "hold" above means the line. It reports separately light checks that fail far above the cut, that flip at the cut, and a single face's level changing between frames (with the per-sticker nearest-color matches logged). From the backlit captures the PO decides whether the check goes back to blocking.)*

**Given** the Story 10.20 **held-out** groups
**When** `QuickScanSideFaceReplayTest` runs in CI with its bar enforced
**Then** per device, light and torch state (never pooled): 0 confidently wrong side stickers and 0 Red↔Orange confusions on kept faces, at least 90% of side stickers read correctly, and at least 3 of every 4 side faces kept (a face kept with one blank sticker counts as kept; its blank counts as not correct). Backlit groups are reported, not scored, and must trip the light check. The replay also runs the held-out groups with a calibration from another cube and from another light, and in those runs too no kept face carries a confidently wrong sticker. This is 10.19 AC6.

**Given** the main app on each device after the tuning
**When** a tester runs at least 10 front-lit and 10 backlit Quick Scans per device, including a solved cube without a saved calibration
**Then** false-block and missed-block counts per light mode are recorded from the diagnostics (10.19 AC2). On the S24 FE a glossy cube is captured at both holds with the torch on and off, and torch glare is coached and cleared by turning the torch off (10.19 AC4). Faces kept per scan are recorded per device.

**Given** the device evidence
**When** the PO compares two-corner completion before (Story 10.20 baseline) and after
**Then** the PO records whether Quick Scan is viable as `BETA` (10.19 AC8). If it is, a later Story 10.11 run (`10.11-n`) raises `QUICK_SCAN`; the level itself still follows the recognition matrix (Story 10.8). If it is not, Quick Scan stays OFF and the reasons go to a new issue. *(SCP 2026-11-24 camera-release-timeline: the PO's call on 2026-11-24 was "not viable", so `QUICK_SCAN` stays OFF on this pipeline (action item `epic-10-deferred-10-21-po-beta-quick-scan-off-pipeline-rethink`). Quick Scan returns through the continuous facelet scan approach (PR #363) and its own BETA call, not through 10.24–10.27.)*

**Dropped (SCP 2026-11-24, #368): Story 10.24 — Retake a corner in Quick Scan**

*(SCP 2026-11-24 camera-release-timeline / continuous-quick-scan: superseded and **dropped**; replaced by Stories 10.30–10.33. Do not start. For 10.27's non-corner-model `ColorCalibrationTest.KNOWN_PASTEL_PROBLEMS` `#166`, see tracked follow-up action item `scp-2026-11-24-pastel-plausibility-guided-followup`.)*

As a Learner using Quick Scan,
I want to take a corner again when I see the photo went wrong,
So that one bad photo doesn't make me restart the scan or go face by face.

**Refs:** FR-22, FR-23, NFR-10 · recognition NFRs (Quick Scan) · AD-9, AD-18 · UX-DR15 · Stories 10.16, 10.17, 10.21, 10.22 · #299

**Depends on** Story 10.21. **Human gate:** a device session on the main app build, `QUICK_SCAN` raised in debug, on the Lenovo TB128XU and the Galaxy S24 FE, with TalkBack on for part of it. *(SCP 2026-11-22 quick-scan-corners-and-solver-copy. PO decisions 2026-11-22: Q-d, retake corner 1 while corner 2 is awaited, by a visible control and by the system Back, and retake corner 1 or corner 2 from the Quick Scan review; Q-e, a retake keeps the better read face by face; Q-f, the retake is also offered from the face-by-face viewfinder.)*

**Acceptance Criteria:**

**Given** the Quick Scan corner view while corner 2 is awaited (corner 1 captured)
**When** it renders
**Then** an outlined *Retake corner 1* button (`en.json` `btn.quick-scan.retake-corner`, "Retake corner {n}") shows beside *Scan face by face* (DESIGN.md), the screen still fits a phone in portrait without scrolling, and TalkBack reads "Retake corner 1"; while corner 1 is awaited it does not show

**Given** corner 2 is awaited
**When** the Learner taps *Retake corner 1* or uses the system Back
**Then** both do the same: the corner view returns to corner 1 with its prompt and the counter "1 / 2", the turn-over state and the hold-1 centres are cleared as `backOut` does (Story 10.22), the torch state is kept, and TalkBack announces the corner-1 prompt. While corner 1 is awaited, the system Back leaves Quick Scan as before

**Given** a corner retake captures
**When** its faces are compared with the earlier reads of the same faces
**Then** the better read is kept face by face: a face that passes the keep rule (Story 10.21) beats one that does not; between two that both pass or both fail, the read with more CONFIDENT stickers wins; a tie takes the new read. This applies to faces kept by an earlier hand-over too, the one exception to Story 10.17's "a kept face never changes until review". After a retake of corner 1, hold 2 compares against the new hold-1 centres

**Given** the face-by-face viewfinder of a Quick Scan hand-over (Story 10.17)
**When** it renders
**Then** it offers *Retake corner 1*, and *Retake corner 2* once corner 2 was captured. Tapping one drops the faces captured in the hand-over (all or nothing, as `backOut` does) and opens the corner view at that corner; after the capture the better read is kept face by face, and faces that still need the grid are handed over again as usual. The system Back in that viewfinder stays `backOut` (Story 10.17), and *Scan face by face* still keeps every face that passes the keep rule

**Given** the Quick Scan review in retake mode (Stories 10.16, 10.17)
**When** it renders
**Then** outlined *Retake corner 1* and *Retake corner 2* buttons sit above the face net (each only when that corner was captured); one opens the corner view for that corner only ("1 / 2" or "2 / 2"), and after the capture review reopens with the better read kept face by face. A face that keeps its earlier read keeps the Learner's fixes; a face that takes the new read shows only its new capture, as in Story 10.16. If the faces can't make one valid cube, review shows the best fit with the usual messages and no re-prompt. Backing out before the capture reopens review as it was. *Close* asks "Discard this scan?" after a completed corner retake, as after a face retake

**Given** `QuickScanControllerTest`
**When** it runs
**Then** it covers: capture corner 1, retake it by the control and by Back, and capture corner 1 again with better and with worse readings, then corner 2, and the scan keeps the better read per face; a retake from the face-by-face viewfinder after a hand-over that kept a hold-1 face; a retake of each corner from review; and a back-out of each retake that changes nothing

**Dropped (SCP 2026-11-24, #368): Story 10.25 — Quick Scan starts from whichever corner it is shown**

*(SCP 2026-11-24 camera-release-timeline / continuous-quick-scan: superseded and **dropped**; replaced by Stories 10.30–10.33. Do not start. For 10.27's non-corner-model `ColorCalibrationTest.KNOWN_PASTEL_PROBLEMS` `#166`, see tracked follow-up action item `scp-2026-11-24-pastel-plausibility-guided-followup`.)*

As a Learner using Quick Scan,
I want the first photo to take whichever corner I show and the second to ask for the faces still missing,
So that a fast capture never mislabels my cube and I never get stuck at the second corner.

**Refs:** FR-22 · recognition NFRs (Quick Scan, Confidence calibration), NFR-10 · AD-9, AD-18 · UX-DR15 · Stories 10.17, 10.21, 10.22, 10.24, 11.1 · spec `_bmad-output/specs/spec-quick-scan-reliability/` (F15, F16, F17) · #302

**Depends on** Story 10.21 (calibration-match check and the confident-sticker wait) and Story 10.24 (the way back). **Human gate:** a device session on the main app build, `QUICK_SCAN` raised in debug, on the Lenovo TB128XU and the Galaxy S24 FE, with a saved calibration and without, starting from at least three different corners. Closes action item `epic-11-deferred-11.1-wrong-corner-self-calibration`. *(SCP 2026-11-22 quick-scan-corners-and-solver-copy. PO decision 2026-11-22, Q-a: accept whichever corner hold 1 shows and ask for the opposite corner at hold 2; no wrong-corner block. The requested corner was only the natural order. PO Q-h: Story 10.11-n does not wait for this story.)*

**Acceptance Criteria:**

**Given** the corner view while corner 1 is awaited
**When** it renders
**Then** the prompt reads "Hold the cube with a corner toward you, so three faces show." (`msg.quick-scan.hold-first`), and no check blocks a capture for showing another corner than a requested one; Story 10.22's 10 s bar applies

**Given** a hold-1 capture
**When** its faces are named
**Then** they are named from their three centres, never from the prompt: each centre is read against the saved calibration when it matches (Story 10.21), otherwise against fixed reference colours recorded in the spec; a logo centre is read from its ring or left out (Story 10.22). The white–yellow and green–blue centres are named by colour, and a red–orange centre takes the colour that the cube's colour scheme allows for that corner, so a red/orange close call never names it. When this gives exactly one corner, the samples carry that corner's faces and pose for `ScanAssembler`, and self-calibration labels its references from the named centres. When it gives none (two centres on one axis, a scheme conflict, or too few plain centres), the corner stays unnamed and the capture is kept. `CubentiaScanDiag` logs `corner=named <colours>` or `corner=unnamed <reason>` with the three centre reads

**Given** corner 1 is captured
**When** the corner view asks for corner 2
**Then** the prompt names the faces still missing, for example "Now turn the cube halfway, so yellow, blue and orange face you." (`msg.quick-scan.hold-second` with the colours as arguments); for an unnamed corner 1 it reads "Now turn the cube halfway, so the other three faces show." After hold 2 the faces are named from all six centres; a scan whose faces still can't be named goes to the Guided grid or the review as today, never to a guessed state (FR-22)

**Given** hold 2 blocked by the pose check because it still shows the hold-1 faces
**When** the block has lasted about 2 s, judged over the window (not frame by frame, so single-frame flaps neither show nor hide it)
**Then** inline coaching reads "That's the same corner as the first photo. Turn the cube halfway, or retake corner 1." (`msg.quick-scan.same-corner`), *Retake corner 1* (Story 10.24) takes its highlighted outline, and TalkBack announces the line once; the line hides once the turn is confirmed

**Given** the readings of the #302 session (committed as a domain fixture of per-frame centre and sticker reads, no frames)
**When** `QuickScanControllerTest` replays them with the session's calibration
**Then** a hold 1 on the yellow-top corner, once it captures, is named yellow, orange and blue, and hold 2 asks for white, green and red; replayed as hold 2 after that hold 1, the same-corner state is set and the retake then captures; no hold is left with no message and no way out

**Given** the Story 10.20 held-out groups, replayed as hold 1 from each corner they show, with a matching calibration, without one, and with a calibration from another cube and another light
**When** the replay runs
**Then** **0** corners are named wrongly (a wrongly named corner would be a wrong cube), and the share of corners left unnamed is reported per device, light and torch state

**Dropped (SCP 2026-11-24, #368): Story 10.26 — Quick Scan fills a few unread stickers from the cube's rules**

*(SCP 2026-11-24 camera-release-timeline / continuous-quick-scan: superseded and **dropped**; replaced by Stories 10.30–10.33. Do not start. For 10.27's non-corner-model `ColorCalibrationTest.KNOWN_PASTEL_PROBLEMS` `#166`, see tracked follow-up action item `scp-2026-11-24-pastel-plausibility-guided-followup`.)*

As a Learner using Quick Scan,
I want the few stickers the camera couldn't read to be worked out from the rest of my cube when that is certain,
So that a handful of doubtful stickers doesn't send whole faces to face-by-face capture.

**Refs:** FR-22, FR-23 · recognition NFRs (Quick Scan, Confidence calibration) · AD-9 · Stories 10.17, 10.20, 10.21 · spec `_bmad-output/specs/spec-quick-scan-reliability/fix-plan.md` ("Keep stickers, not faces", CAP-5) · #301

**Depends on** Stories 10.20 and 10.21. **Starts** only after the PO's 10.21 BETA call (Story 10.21's last AC), with the held-out report in hand; the safety assertion (first AC) is built and run before any search. *(SCP 2026-11-24 camera-release-timeline / continuous-quick-scan: dropped with 10.24, 10.25 and 10.27; replaced by Stories 10.30–10.33.)* **Human gate:** a device session on both reference devices. *(SCP 2026-11-22 quick-scan-corners-and-solver-copy. PO decision 2026-11-22, Q-c: plan it now in the backlog. SCP 2026-11-22 quick-scan-reliability did not propose it; the "Keep stickers, not faces" candidate in the spec and its constraint that cube rules cannot force an unsupported label (fix-plan.md) are carried into the ACs below. It does not change Story 10.21's keep rule.)*

**Acceptance Criteria:**

**Given** `QuickScanSideFaceReplayTest`
**When** a per-sticker safety assertion is added first (no sticker that would be accepted without Learner confirmation is wrong), and run on the existing and 10.20 groups before any search is built
**Then** its result is recorded in the spec and sizes the work

**Given** a two-corner scan whose faces carry per-sticker colour candidates (a CONFIDENT sticker carries one, an uncertain sticker its plausible readings from the image)
**When** `ScanAssembler` searches the combinations
**Then** the search prunes by cube rules (six known centres, nine of each colour, only legal edge and corner pieces), stops at a cap on combinations and unknown stickers, and cube rules only rank or reject candidates; they never give a sticker a colour that is not among its candidates

**Given** the search
**When** exactly one legal cube remains and every filled sticker's true colour lies within its candidates
**Then** the filled stickers arrive in review marked as filled and pre-selected for checking; otherwise (several cubes, none, over the cap, or too many unknowns) the review or the Guided grid asks the Learner, as today

**Given** the held-out replay
**When** it runs per device, light and torch state
**Then** 0 confidently wrong stickers are added, and faces kept and stickers filled are reported against the 10.21 baseline

**Dropped (SCP 2026-11-24, #368): Story 10.27 — Clean the corner fixture labels and judge pastel stickers against the cube's own colours**

*(SCP 2026-11-24 camera-release-timeline / continuous-quick-scan: superseded and **dropped**; replaced by Stories 10.30–10.33. Do not start. For 10.27's non-corner-model `ColorCalibrationTest.KNOWN_PASTEL_PROBLEMS` `#166`, see tracked follow-up action item `scp-2026-11-24-pastel-plausibility-guided-followup`.)*

As the developer tuning Quick Scan,
I want a repeatable label-cleansing pipeline for the corner fixtures, and a plausibility check that judges a sticker against its own cube's calibrated colours,
So that Story 10.21 tunes on labels that are right, and a pastel cube's fixtures pass without loosening the check for every cube.

**Refs:** FR-24 · recognition NFRs (Confidence calibration) · AD-9 · Stories 10.4, 10.20, 10.21, 10.23 · #166, #315, #327

*(SCP 2026-11-24 github-issue-triage, #337: this story audits the existing groups offline; Story 10.28 stops the recorder from saving a new group whose labels break the count rule. The piece check here still catches wrong labels that balance the counts. #338, corner placement, stays with AC2.)*

**Depends on** Story 10.20's fixtures and the PO label review of 2026-11-23 (branch `spike/label-cleanse`, commit `c9c9f5cc`: 20 labels corrected, all 23 corner groups count 9 of each colour; tools in `tools/label-audit/`). Device-free. **Human gate:** the PO decides the stickers the pipeline quarantines, on its review page. Until this story lands, `ColorCalibrationTest` lists hold 2 `L[8]` of `lenovo-tb128xu_stlb_bar-warm` in `KNOWN_PASTEL_PROBLEMS` (expected failure, strict both ways). *(SCP 2026-11-23 pastel-plausibility, revisions 3–4. PO decisions 2026-11-23: a new story; the calibration swatch gate keeps its fixed windows; reuse the earlier pastel runs; an automated cleansing pipeline instead of a manual list; Story 10.21 does not wait for the review.)*

**Acceptance Criteria:**

**Given** `Fixtures/corner` and its group index
**When** `uv run --project tools/sticker-model python tools/label-audit/audit.py` runs (Linux workstation, outside CI, as the other model tools)
**Then** for every capture group (two holds, six faces) it checks that the labels give exactly 9 stickers of each colour, centres included, and that every edge and corner piece the labels form is a real piece of the standard colour scheme (no piece with two stickers of one colour or of opposite colours); a group that fails names the stickers that can fix it, ranked by the photo's read. It writes `artifacts/label-audit/report.json`, a summary to `docs/evidence/10-27/label-audit.md`, and the committed, versioned `Fixtures/corner/label-exclusions.json` (excluded stickers with decision, reason and date; failing groups), and exits non-zero when a group fails either check. A Kotlin test in CI re-checks the count and piece rules from the labels alone (no photos), so a later label edit that breaks them fails CI

**Given** each corner photo
**When** the pipeline reads it
**Then** each face is located (corner model, or a corrected outline from `docs/evidence/10-27/label-review-decisions-2026-11-23.json` when one exists), the inner 60% of each cell is read as a median CIELAB, and the read is grouped per capture group (six clusters seeded from the group's own centres). Faces whose placement error is more than 10% of the face side use the corrected outline or are marked unreadable, and the pipeline does not rank fixes on an unreadable face. The face placement error of the corner model against the PO's corrected outlines (40 moved, after a PO pass that marks each as a correction or visibility-only) is reported (mean and maximum corner distance, in pixels and as a share of the face size) and routed to #315

**Given** a sticker whose label and photo read disagree, or that a failed group check names
**When** the pipeline finishes
**Then** it is quarantined, and a review page (generated by a script in `tools/label-audit/`, served on 127.0.0.1 only) shows per cube both corner photos, each face's crop beside its clickable label grid, the group's colour count, and the surplus-colour stickers ringed; decisions save to a JSON file that `apply_corrections.py` writes back to the label fields only, with `truthSources` = `reviewed` (a new `TruthSource` value) and a corrections log holding date and reviewer. Optional: a DINOv2 embedding flags crops that are off a sticker (body, logo, glare) for exclusion

**Given** a calibration and a sticker's Lab value with a claimed colour
**When** `ColorPlausibility.plausibleFor(calibration, color, lab)` is fitted on `Fixtures/live` and `lenovo-tb128xu_stlb_cool` (hue window = largest gap to the colour's own calibrated centre + 5°, chroma fraction = smallest ratio − 0.05; White: maximum chroma and minimum L relative to the calibrated White, + the same margins; the sticker that sets each bound is reported and confirmed by the PO; the constants are committed in their own commit before the validation run, whose evidence cites that SHA)
**Then** `ColorCalibrationTest`'s live and pastel checks use it and pass, on the whole `lenovo-tb128xu_stlb_bar-warm` group, held out from fitting, including hold 2 `L[8]` (Blue at Lab(56.2, -20.1, -13.4), hue 213.6°, its centre 235°), and `KNOWN_PASTEL_PROBLEMS` is removed. A validation sticker may be excluded only by a PO decision with photo evidence, at most 2 per group, never hold 2 `L[8]`, and the excluded count is reported beside the pass rate. The fixed-window `ColorPlausibility.plausible` stays as the calibration swatch gate, unchanged, and the pastel centres' margin to it is recorded

**Given** the substitution test (every non-centre sticker of the fitting and validation sets relabelled as each of the five other colours)
**When** `plausibleFor` runs
**Then** a substitution counts as refused when `plausibleFor` returns false, or the sticker is nearer its true colour's calibrated centre than the substituted one's by at least `StickerClassifier.CLOSE_CALL_DELTA_E`; the rate is reported per ordered pair, standard and pastel, on the fitting and the held-out data separately, and the `plausibleFor`-only rate is reported beside the combined one; bar ≥ 98%, and ≥ 90% for the hue-adjacent pairs (Red↔Orange, Yellow↔Green, Yellow↔Orange, colours→White); a pair below its bar fails the story unless the PO accepted that pair in the spec before the run

**Given** a shared corner-fixture filter over `Fixtures/corner/label-exclusions.json`
**When** `QuickScanSideFaceReplayTest` reads the corner fixtures through it
**Then** it skips stickers decided `exclude` and groups that fail the audit, and reports the counts (Story 10.21's runs adopt the same filter); no keep rule, agreement gate or classifier threshold changes in this story

### Story 10.28: The fixture recorder checks nine of each colour before it saves a corner group

As the developer recording corner fixtures,
I want the recorder to refuse a group whose labels cannot be a real cube, and to make me look at every side-face pre-fill,
So that the app's own confident misreads never become fixture labels.

**Refs:** FR-24 · recognition NFRs (Confidence calibration) · AD-9, AD-15 · Stories 10.12, 10.20, 10.21, 10.23, 10.27 · #337, #327, #338

**Depends on** Story 10.20's corner recorder. Device-free (JVM and UI rule tests). The recorder is a debug-only developer tool (AD-15), so this is not release scope. **Runs before** any new corner-fixture recording session; it blocks neither Story 10.21, Story 10.27 nor Story 10.11. *(SCP 2026-11-24 github-issue-triage, #337: the 2026-11-23 label review corrected 20 of 1,242 corner labels, 18 of them pre-filled, all 12 WHITE→BLUE on the side faces. #338, corner placement, is Story 10.27 AC2's and is not part of this story.)*

**Acceptance Criteria:**

**Given** a corner group whose two holds are both complete
**When** the operator finalizes it
**Then** `RecorderRules` counts the labels of both holds, centres included; if any colour is not exactly 9, the group is not saved, the recorder names each over- and under-counted colour with its count, and the pre-filled stickers of the over-counted colours are highlighted

**Given** a refused group
**When** the operator overrides
**Then** a non-empty reason is required and is written to the group's manifest (a new optional field; manifests without it still load), and an empty reason cannot override

**Given** a group saved with an override reason
**When** the group index and the fixture consumers read it
**Then** the index lists it separately with its reason, and Story 10.21 tuning and Story 10.23 scoring skip it unless the PO includes it by name; the skipped count is reported

**Given** `RecorderRulesTest`
**When** it runs
**Then** a group counting 10 WHITE / 8 BLUE is refused with both colours named, 9 of each is accepted, and an override saves its reason to the manifest

**Given** the pre-correction labels (`add0c79e`) of the 10 groups corrected on 2026-11-23 (`docs/evidence/10-27/label-corrections-2026-11-23.json`)
**When** they are replayed through the count check
**Then** all 10 are refused (a wrong label swapped with another wrong label of the same pair passes the count; Story 10.27's piece check covers it)

**Given** the corner-hold face review
**When** a face on either side face of the hold (face index 1 or 2) still has a pre-filled sticker the operator has not tapped (confirm or change)
**Then** that face cannot be confirmed, and a UI rule test pins it

**Given** an incomplete or retaken hold
**When** the group is finalized
**Then** the count check runs only once both holds are complete and always on the labels of the latest capture of each hold

### Story 10.29: Solution Playback shows the same Quick Check result as Lessons

As a Learner following a solution on my real cube,
I want a Quick Check in Solution Playback to show its result the way a Lesson does,
So that I understand what the check saw and how to recover, wherever I run it.

**Refs:** FR-26, FR-16, FR-9 · AD-3, AD-5, AD-20 · UX-DR12, UX-DR13, UX-DR20 · UX Flow 3 · Stories 10.6, 10.7, 14.3 · #326, #200

**Depends on** Story 10.7. Done in v1.0; its deferred device check is an open action item, not a v1.0.0 tag gate. **Human gate:** a device check on the Lenovo Tab M10 Plus 3rd Gen and the Galaxy S24 FE, main app build, Solve tab and a physical Lesson. The EXPERIENCE.md update for the shared result card is made when this story is created. *(SCP 2026-11-24 github-issue-triage, #326.)* *(SCP 2026-11-25 retire-v1-1: v1.1 is retired; v1.0 scope.)*

**Acceptance Criteria:**

**Given** a physical-mode Quick Check result in a Lesson or in Solution Playback
**When** it is shown
**Then** both hosts render one shared result component: the frame label, the Certainty chip from `tutor/Assess` (never a numeric confidence), "I can't tell what changed" for an unreadable result, and the recovery actions; the Certainty is announced to TalkBack once (#200)

**Given** the Lesson host
**When** a result action is tapped
**Then** it routes through the Lesson's step and recovery flow, unchanged

**Given** the Solution Playback host
**When** a result action is tapped
**Then** the equivalent recovery action routes through Solution Playback's existing `SessionEvent` → `reduce()` transitions; no ViewModel or composable mutates state

**Given** both hosts at a normal step and at completion
**When** a Quick Check returns a match, a probable mismatch and an unreadable result
**Then** tests cover each case on each host; the copy comes from content strings and fits at 200% font on the Lenovo's 600 dp width

### Story 10.30: Port continuous facelet lattice fitter and cube mapper to pure JVM

As the developer,
I want the continuous facelet lattice fitter and cube mapper ported to pure-JVM Kotlin in `:core:domain`,
So that Quick Scan's geometry stitching, color anchoring, invariants, and twist detection are 100% deterministic, Android-free, and verified to parity with the PC spike.

**Refs:** FR-22, FR-23, FR-24 · NFR-4, NFR-12 · AD-1, AD-2, AD-9, AD-12, AD-14 · PR #363 (`pc_spike/lattice_fit.py`, `pc_spike/mapper.py`)

**Depends on** Story 10.1 (domain `Facelets`, `CubeValidator`, `ScanAssembler`). Device-free (pure JVM). *(SCP 2026-11-24 continuous-quick-scan.)*

**Acceptance Criteria:**

**Given** unordered 2D sticker-center points `(x, y)` from 1 to 3 visible cube faces
**When** `FaceLatticeFitter.fit(centers)` runs in `:core:domain` (`dev.cubentia.domain.recognition.continuous`, zero Android/OpenCV imports)
**Then** it recovers up to 3 clockwise projective face quads using bimodal multi-scale neighborhood clustering, exact 4-point DLT homography solving, and tight geometry checks (`opp <= 1.55`, `mean_err <= 0.12 * d`), reproducing **90/90 (100%) faces and 90/90 (100%) shared edges** against a committed deterministic `Fixtures/corner/` peak parity JSON fixture under `core/domain/src/test/resources/fixtures/continuous/`

**Given** a stream of neutral per-frame quad observations (2D quad vertices + 9 sampled linear/sRGB patches + sharpness/glare quality scalars)
**When** `ContinuousFaceletMapper.processFrame(...)` runs
**Then** it maintains an intrinsics-free 2D shared-edge face graph with historical center-color tracking lock (`center_de <= 26`), 24-SO(3) regrip merger with symmetry + per-center (`<= 26`) + outlier (`< 2` cells `> 35`) guards, zero-mean bounded log-RGB AWB drift (`±0.36`), shading-invariant CIELAB + WHITE-referenced hue distance + exact 8-per-color balanced solver, local cubie invariants with evidence-weighted conflict reopening, and per-fragment mid-scan layer-twist detection (`de > 33` on an outer line for 5 frames on `master`), returning sealed domain snapshots and cue enums/keys (AD-12)

**Given** a completed or partial continuous scan (including starting on Yellow/Orange/Blue without White)
**When** the mapper exports its result
**Then** a completed 54-sticker state is canonicalized to `Orientation.HOME` (`WHITE` on U, `GREEN` on F, AD-2) so `ScanReview` never corrupts non-HOME scans; confirmed faces also export as a typed domain handoff payload (face colors + mutual orientation + self-calibrated center Labs / per-cell confidence) accepted by `ScanAssembler` for Story 10.17 Guided handover without reclassifying kept faces, by `ScanReview` for Story 10.16 *Retake faces* and `#323` suspect ranking, and by the Solve tab's `#321` seeded Editor fallback; saved Color Calibration (FR-24) is optional and never required because the mapper self-calibrates from the cube's own 6 centers

**Given** the 7 end-to-end stress scenarios ported as JVM parity tests (regrip, pastel + AWB drift, logo center + overhead glare, mid-solve F2L symmetric regrip, finger occlusion + clutter, starting without White, mid-scan layer twist)
**When** `./gradlew :core:domain:test :core:domain:koverVerify :core:domain:checkModuleBoundaries` runs
**Then** **7/7** pass (**54/54**, `0` wrong, `0` transient wrong on Scenarios 1–6; twist caught cleanly on Scenario 7), `:core:domain` stays ≥ 80% Kover line coverage, and module boundaries pass

### Story 10.31: Wire LiteRT v2 INT8 sticker-center detector, split `continuous` source set, and retire shadow reader

As the developer,
I want Quick Scan's clean-room LiteRT v2 INT8 detector bundled in its own build-time source set with reproducible provenance and the failed debug shadow reader retired,
So that release `0.11.0` ships only the 369.4 KB INT8 model within NFR-11 while leaving `CASE_RECOGNITION` untouched and OFF.

**Refs:** FR-22 · NFR-5, NFR-8, NFR-11 · AD-9, AD-13, AD-14, AD-15, AD-18 · OQ-7 · PR #363, PR #366

**Depends on** Story 10.30. **Human gate:** R8 `cornerR8` device benchmark on **Lenovo TB128XU** (minimum gating) and **Galaxy S24 FE** (mid gating). *(SCP 2026-11-24 continuous-quick-scan.)*

**Acceptance Criteria:**

**Given** `build-logic` (`FeatureFlagLevels`, `ReleaseConventionPlugin`, `CameraSizePolicy`, `CheckCameraSizeTask`, `CameraClasspathPolicy`, `ShadowReaderPolicy`, `GenerateKeepRulesTask`)
**When** build variants are configured
**Then** `FeatureFlagLevels` splits `isContinuousModelEnabled` (`QUICK_SCAN`), `isCornerModelEnabled` (`CASE_RECOGNITION`), and `isLiteRtEnabled` (`QUICK_SCAN || CASE_RECOGNITION`); `QUICK_SCAN` above `OFF` composes `app/src/camera/continuous/` (`kotlin/`, `assets/sticker_centers_v2_int8.tflite` `378,288 B` SHA-256 `d18fb2c2fe2bb975cf646877f811718c16775ce6bcde907f12547d143174be7c`, manifest) + `litert:2.3.2` (no `ai-delivery`, `noCompress += "tflite"`); `CASE_RECOGNITION` above `OFF` composes legacy `app/src/camera/corner/` (`corner_keypoints.tflite`); `CheckCameraSizeTask` projects the `0.11.0` `CASE_RECOGNITION=OFF` camera release without counting `dev/cubentia/app/camera/corner/**` classes or `src/camera/corner/assets` (and avoids double-counting once release carries camera flags in Story 10.11); and `./gradlew -p build-logic test` verifies all flag combinations including `QUICK_SCAN=BETA, CASE_RECOGNITION=OFF` shipping zero `corner_keypoints.tflite` and zero `sticker_reader*.tflite` bytes

**Given** Story 10.23's debug shadow sticker reader
**When** this story merges
**Then** `app/src/camera/debug/sticker/` (`sticker_reader.tflite`, `ShadowReaderToggle`, `ShadowStickerReader`), `ReleaseConventionPlugin`'s `ShadowReaderPolicy.composes(...)` hooks & manifest inclusion, `CapturedFaceObserver` / `CapturedFaceObserverOptionalModule`, the `lastCornerCrops`/`shadowScanId` plumbing in `GuidedScanActivity` and `ScanFrameSource`, and the 4 `app/src/testDebug/kotlin/dev/cubentia/app/camera/sticker/*` tests are cleanly removed; `ShadowReaderPolicy` and `ShadowReaderCompositionFunctionalTest` keep enforcing the non-debuggable release guard against any `sticker_reader*.tflite` asset or `dev/cubentia/app/camera/sticker/` class; `tools/sticker-model/` stays archived as historical Story 10.23 evidence; and `docs/ARCHITECTURE.md` is updated to match AD-9/AD-18

**Given** clean-room provenance for `sticker_centers_v2_int8.tflite`
**When** the model is committed into `app/src/camera/continuous/assets/`
**Then** the reproducible training and evaluation recipe is promoted into `tools/center-model/` (`pyproject.toml`, `README.md`, `model-card.json` with full 64-hex SHA-256, tool versions, dataset/holdout manifest, and a holdout-disjointness test)

**Given** `LiteRtStickerCenterDetector` inside `app/src/camera/continuous/` behind an optional `@BindsOptionalOf` `StickerCenterDetector` seam (keeping `QUICK_SCAN = BETA` in `debug`)
**When** it analyses a `YuvFrame` off the main thread (`analyzerExecutor`, XNNPACK CPU **2 threads**, direct YUV→`UINT8[1,256,256,3]` downsampling honoring `rotationDegrees` & `cropRect`, `FLOAT32[1,64,64,3]` peak decode, inverse mapping back to `PreviewView` coordinates via `ViewfinderGrid.frameToViewFraction`, all `Interpreter` calls inside `app/src/camera/`)
**Then** it feeds `FaceLatticeFitter` + `ContinuousFaceletMapper` without persisting or transmitting any frame (AD-13)

**Given** `cornerR8` (kept composing both model sets so `LiteRtCornerDetectorDeviceTest` and `CiWorkflowTest` stay green, plus new `LiteRtStickerCenterDetectorDeviceTest`) and size gates
**When** `./gradlew spotlessCheck build`, `:app:checkCameraSize`, a minified `0.11.0`-profile size check (`CAMERA_SCAN=GA, PLACEMENT=BETA, QUICK_SCAN=BETA, CASE_RECOGNITION=OFF`), and `-Pcubentia.cornerR8=true :app:connectedCornerR8AndroidTest` run
**Then** `:app:checkCameraSize` and the real minified `0.11.0`-profile build both stay `< 30,000,000 B` (NFR-11), 16 KB ELF alignment passes, and on-device R8 benchmarks across both `640×480` and `1280×960` inputs achieve exact PC-to-Android peak parity on the 3 tuning `Fixtures/corner/` stills and full per-frame end-to-end latency (`YUV→RGB + LiteRT + decode + FaceLatticeFitter + ContinuousFaceletMapper`) `≤ 50 ms` `p50` on Lenovo TB128XU and `≤ 20 ms` `p50` on Galaxy S24 FE

### Story 10.32: Continuous Quick Scan live viewfinder, growing net, Guided fallback, and Review hand-off

As a Learner using Quick Scan,
I want to turn my cube in front of the camera and watch the 6-face map fill in with clear guidance,
So that scanning feels effortless and I can fall back to face-by-face capture at any moment without losing progress.

**Refs:** FR-22, FR-23, FR-25 · NFR-7, NFR-10, NFR-11 · AD-3, AD-9, AD-13, AD-17, AD-18 · UX-DR15, UX-DR16, UX-DR19 · Stories 10.3, 10.15, 10.16, 10.17

**Depends on** Story 10.31. **Human gate:** hands-on main-app sanity pass from the Solve tab on Lenovo TB128XU and Galaxy S24 FE (full matrix & BETA call in Story 10.33). *(SCP 2026-11-24 continuous-quick-scan.)*

**Acceptance Criteria:**

**Given** `QUICK_SCAN` is `BETA` or `GA` (wired in `debug` at `BETA`; `OFF` in `release` until Story 10.11)
**When** the Learner opens Quick Scan from the Solve tab (`SolveScreen` → `onQuickScan`)
**Then** `ContinuousQuickScanScreen` shows the live CameraX preview with smoothed detected face quads (correctly mapped across portrait phones and landscape tablets), a compact live growing 6-face `URFDLB` net (using shared `StickerAppearance` color-blind glyphs, AD-17), `Sides mapped: N/6`, and one inline cue line from `en.json` covering all mapper states (bring into view, turn to show remaining N sides, tilt for adjacent face, tilt away from light, conflict face, layer twist detected, all 54 stickers verified)

**Given** a scan in progress
**When** the Learner taps *Reset*, *Scan face by face*, *Enter colors instead*, *Torch* (OFF by default; shown only on flash-equipped devices and turned off on handover/leave/background), or *Close* / system Back
**Then** *Reset* clears the mapper state in place; *Scan face by face* keeps every confirmed face and opens Guided 6-face Scan for only the missing faces assembled via `ScanAssembler`, with Back returning to Quick Scan with confirmed faces intact (Story 10.17); *Enter colors instead* seeds the Manual Cube Editor on the Solve tab with confirmed stickers when ≥ 1 face was mapped (Story 10.15 `#321`); *Close* / Back exits immediately at 0 mapped faces and asks "Discard this scan?" at ≥ 1 mapped face; zero frames are persisted or sent

**Given** all 54 stickers verified (`0` conflicts, `Orientation.HOME`, `CubeValidator` valid)
**When** the mapper completes
**Then** Quick Scan opens Story 10.3 Scan Review (with Story 10.16 per-face retake, `#323` suspect ranking, and Paint mode) and returns standard `ScanOutcome` to the Solve tab (Solution Playback or seeded Editor fallback)

**Given** obsolete 2-corner Quick Scan code no longer used by `CASE_RECOGNITION` or `FixtureRecorderActivity`
**When** this story merges
**Then** Quick-Scan-only 2-corner domain/UI code and tests (`QuickScan.kt`, `QuickScanClassification.kt`, `CornerHold.kt`, `QuickScanDiagnosis.kt`, `SideLight.kt`, and obsolete 2-corner branches in `GuidedScanActivity`/`GuidedScanScreen`) are cleanly removed while keeping `CornerLocator`/`CornerReader`/`CornerGuide` intact for `CASE_RECOGNITION` and `FixtureRecorderActivity`, and `:app:checkCameraSize` confirms reclaimed class-byte headroom

**Given** Robolectric Compose UI, route, accessibility, and layout tests
**When** `./gradlew spotlessCheck build` runs
**Then** all copy comes from `en.json` (`checkLearnerText` passes), the screen fits a phone in portrait without scrolling and at 200% font on Lenovo 600 dp, TalkBack announces side milestones/cues/conflict/completion, and `QUICK_SCAN = OFF` hides all Quick Scan UI cleanly

### Story 10.33: Continuous Quick Scan physical-device matrix and PO BETA call

As the product owner,
I want continuous Quick Scan measured on our physical reference devices across cubes and lighting conditions,
So that I can make an informed BETA call for Story 10.11's `0.11.0` camera release.

**Refs:** FR-22 · §4.8 recognition NFRs, NFR-11 · AD-18 · PRD §7 gate 6 · Stories 10.8, 10.11, 10.32

**Depends on** Story 10.32. **Human gate:** physical-device session on the main app build (from the Solve tab) across **Lenovo Tab M10 Plus 3rd Gen (`TB128XU`, gating minimum)**, **Galaxy S24 FE (`SM-S721U1`, mid gating)**, and **Galaxy Tab S7 FE 5G (`SM-T738U`, compatibility)**. *(SCP 2026-11-24 continuous-quick-scan.)*

**Acceptance Criteria:**

**Given** the main app build with `QUICK_SCAN` wired and stickered (`stka`), stickerless (`stla`), and pastel (`stlb`) cubes across warm, cool, low indoor, and backlit/glare lighting (noting daylight explicitly if excluded from the indoor BETA call, mirroring Story 10.8)
**When** the PO runs live scans from the Solve tab on Lenovo TB128XU, Galaxy S24 FE, and Galaxy Tab S7 FE 5G — at least **20 full scans (`≥ 1,080` evaluated stickers)** across the matrix covering scrambled, checkerboard, near-solved/F2L, regrip recovery, mid-scan layer twist rejection, *Scan face by face* handover keeping faces, and manual Editor fallback
**Then** `docs/evidence/10-33/continuous-quick-scan-beta.md` records per device, cube, and light: completion rate, time to 54/54, sticker corrections in review, confidently-wrong rate (target `≤ 0.1%`, zero silent wrong commits), sustained frame latency (`p50`/`p95`), thermal stability, and live `:app:checkCameraSize` + minified `0.11.0`-profile size measurements (`< 30 MB`)

**Given** the main app Solve tab with `CAMERA_SCAN = GA` and `QUICK_SCAN = BETA`
**When** verified during the device session
**Then** Quick Scan renders the shared BETA badge and tooltip ("May misread colors in poor light. Manual entry always works.") while Guided 6-face Scan carries no beta label (matching Story 10.11 AC4)

**Given** the evidence report (with Lenovo TB128XU as gating minimum)
**When** the PO makes the release-level decision
**Then** the PO records the `QUICK_SCAN` decision (`BETA` for Story 10.11's `0.11.0` release, or `OFF` with routed issues) in the PRD `.memlog.md`, closing `scp-2026-11-24-quick-scan-raise-preconditions`

## Epic 11: Scan shortcuts: Quick Scan, Placement and Case recognition

These are first on the PRD cut order (Quick Scan → beta, then Case recognition, then Placement). Each ships behind its AD-18 flag and can be turned OFF without dead UI. *(SCP 2026-11-14: restored; each merges with its flag OFF in release, and Story 10.11 raises it.)* *(SCP 2026-11-16 main-app entry points: each story also raises its own flag in debug (`FeatureFlagLevels.byBuildType["debug"]`) in the PR that wires its entry point in the main app, and is accepted on the main app build; Story 10.5 sets these flags OFF in debug until then.)*

### Story 10.22: Quick Scan captures promptly once the cube is in view

As a Learner using Quick Scan,
I want each corner captured within a few seconds of holding my cube in view,
So that Quick Scan is faster than scanning face by face.

**Refs:** FR-22 · recognition NFRs (Quick Scan) · AD-9, AD-18 · Stories 10.17, 10.18, 10.19 · spec `_bmad-output/specs/spec-quick-scan-reliability/` (CAP-2, CAP-4; failure modes F1, F2, F4, F6, F11, F13, F14, F15)

**Depends on** Story 10.19. **Human gate:** a device session on the main app build, `QUICK_SCAN` raised in debug, on the Lenovo TB128XU and the Galaxy S24 FE. *(SCP 2026-11-22 quick-scan-reliability. Sticker reading, classification and gates stay in Kotlin in this story; Story 10.23 adds a LiteRT shadow reader that decides nothing, SCP 2026-11-22 quick-scan-sticker-reader. PO decisions 2026-11-22: the light check becomes advisory in this story; every hold captures within 10 s. The keep rule and the agreement gate are not changed here; they stay with Story 10.21.)*

**Acceptance Criteria:**

**Given** a Quick Scan corner hold in a debug build
**When** it runs
**Then** `CubentiaScanDiag` logs every state change as it happens, with milliseconds since the hold began: viewfinder entry, first frame, cube lost and found, pose rejected, metering requested and settled, light hold start and clear, steady restart, and capture with `trigger=auto|manual`. Each capture line carries the hold's total time and its time with the cube undetected. Steady-restart lines are no longer rate-limited. CameraX's own logging is limited so a three-minute session fits the device's default log buffer.

**Given** metering on the detected cube has converged
**When** the hold continues
**Then** exposure stays locked until the capture, or until a re-meter trigger: the cube lost for `CornerHold.LOST_MS`, a torch change, the outline leaving the metered region, or the faces' mean brightness changing beyond a set bound since metering. Focus stays continuous (PO 2026-11-16). A cleared light hold no longer re-meters. White balance at hold 2 is carried from hold 1. The settle signal is true only once exposure time × ISO has stayed within a tolerance for a set window; the AE state alone never settles it. `CameraXFrameSourceTest` replays the exposure series of `docs/evidence/quick-scan-2026-11-22/bursts/20261122-114155-still.jsonl` and reports "not settled" throughout its swing.

**Given** a settled hold
**When** the steady window runs
**Then** it restarts when the detected outline moves more than a threshold from the reference frame, measured as the largest corner displacement divided by the outline's bounding-box diagonal. A color comparison remains only to detect that a different face is shown. The threshold is derived from the three recorded bursts and at least one more hand-held burst, and is written to `docs/evidence/10-22/` with the replay: no restart on either still burst, windows completing on every hand-held burst, and a restart on a burst in which the cube is moved quickly.

**Given** the light check fails during a hold (10.19 AC1)
**When** frames are analysed
**Then** the "Turn so the light shines on the cube." coaching still shows after about 1 s of failing and hides once the check passes, but the check no longer blocks: auto-capture and the manual shutter work as when it passes, and the calibration offer keeps its 10.19 timing. Each capture line logs the light score and `light=pass|fail` at the moment of capture. The keep rule is unchanged, so a face read in poor light is still sent to the Guided grid unless every sticker is CONFIDENT.

**Given** hold 2
**When** the turn and pose checks run
**Then** they do not rely on a centre that is not plain: a logo centre is read from its ring or left out, and the previous hold is not cleared by a single frame. A test replays the logo-centre readings of the still burst as hold 2 with the cube unturned and no capture happens.

**Given** a back-out from the face-by-face hand-over
**When** the corner view resumes
**Then** stale turn-over state is cleared (`QuickScanController.backOut`), with a test that captures again after the back-out.

**Given** the main app on each device after these changes
**When** a tester runs at least 10 holds with everything still and 10 hand-held per device
**Then** the time from cube detected to automatic capture, the time undetected and the blocking gate are recorded per hold from the diagnostics, with the median and the worst case per device, next to the 2026-11-22 baseline in `docs/evidence/10-22/`. Every hold auto-captures within **10 s** of the cube being detected, still and hand-held (PO 2026-11-22); a hold that misses is recorded with its blocking gate and routed to the PO. *(SCP 2026-11-22 quick-scan-corners-and-solver-copy, PO Q-a: from Story 10.25, hold 1 captures whichever corner is shown and hold 2 asks for the other three faces, so no hold waits for showing "the wrong corner". A hold 2 that still shows the hold-1 faces is not a miss: it is told so and offered the way back to corner 1 (Stories 10.24, 10.25). Story 10.21's confident-sticker wait counts toward the 10 s.)* A recorder burst on each device shows one exposure level per hold.

### Story 10.23: A shadow sticker reader runs beside the Kotlin reads

As the developer tuning Quick Scan,
I want a small on-device model to read every captured face next to the Kotlin reads without deciding anything,
So that we learn on real scans whether a model reads shaded and glossy faces better, at no risk to the scan.

**Refs:** FR-22, FR-24 · recognition NFRs (Quick Scan, Confidence calibration), NFR-8, NFR-11 · AD-9, AD-13, AD-18 · OQ-7 · Stories 10.13, 10.18, 10.19, 10.22 · spec `_bmad-output/specs/spec-quick-scan-reliability/` (CAP-3; failure modes F7, F8, F9, F10)

**Depends on** Story 10.22 (per-hold diagnostics and the capture pipeline the reader hooks into). May run alongside Story 10.20. **Human gate:** a device session on the main app debug build, `QUICK_SCAN` raised, on the Lenovo TB128XU and the Galaxy S24 FE. *(SCP 2026-11-22 quick-scan-sticker-reader. PO decisions 2026-11-22: LiteRT is allowed for per-face sticker color classification; this story is shadow mode only, with a runtime debug toggle; the first model trains on existing fixtures and earlier capture evidence, extra real captures where coverage is thin, and synthetic data; whole capture groups are held out. The Kotlin classifier, the agreement gate and the keep rule are not changed here.)*

**Acceptance Criteria:**

**Given** the existing `Fixtures/` tree and the capture evidence committed earlier (`docs/evidence/`, including git history)
**When** the training set is assembled by a seeded, reproducible pipeline under `tools/sticker-model/`
**Then** an inventory in `docs/evidence/10-23/` lists every source, what it holds (face images or cell measurements only), its labels, and its coverage per device, cube and light. The set combines those real captures with synthetic faces that target shaded side faces, glare, red against orange and white against yellow. Where the inventory shows a device, cube or light with too few real faces, additional captures are recorded with the debug fixture recorder and added. Synthetic faces are used for training only.

**Given** the assembled set
**When** it is split
**Then** whole capture groups are held out: no group, and no frame of a held-out group, appears in training or tuning. The held-out groups are named in a committed file and cover both devices, a stickered and a stickerless cube, and at least one shaded or backlit group. A test fails when a group appears on both sides.

**Given** the sticker model
**When** it is exported
**Then** it takes one unwarped face image and returns, for each of the nine stickers, a color and a certainty that maps to `StickerConfidence` (AD-9: categorical only across the port). It reads the image only and takes no Kotlin reading as input, so its read is independent of both Kotlin reads. The `.tflite` file is under 500 KB, and its hash, recipe and tool versions are recorded as for the corner model (Story 10.18).

**Given** the held-out groups
**When** the evaluation runs
**Then** `docs/evidence/10-23/` reports, per device, cube and light, for the model and for the current Kotlin reads: stickers correct, uncertain and confidently wrong; where the two agree and disagree; the red/orange and white/yellow confusions; and results as the real training data grows (a learning curve). No bar is enforced; the report is routed to the PO.

**Given** a Quick Scan capture in a debug build with the reader on
**When** a corner is captured
**Then** the three unwarped face crops are read once by the model off the main thread, and `CubentiaScanDiag` logs per face the model's nine colors and certainties beside both Kotlin reads, with the inference time. The crops stay in memory and are discarded when the scan ends (AD-9, AD-13). The reader has no input to `QuickScanClassification`, the keep rule, `ScanAssembler` or the review: a test replays recorded captures with the reader on, off and failing (model missing, inference error) and gets the same `ScanResult` each time.

**Given** a debug build on a device
**When** the developer uses the debug-only reader control
**Then** the reader is switched on or off without reinstalling, the state is logged at the start of each scan, and it takes effect from the next capture.

**Given** the release and benchmark builds
**When** the build gates run
**Then** they contain no sticker model asset and no shadow-reader class: the reader and its model live only in the debug camera source set, a `build-logic` check fails otherwise, and the release size report is unchanged (NFR-11).

**Given** both reference devices
**When** a tester runs at least 10 Quick Scans per device with the reader on and 10 with it off
**Then** `docs/evidence/10-23/` records the inference time per capture (median and worst) and the per-hold capture times from the Story 10.22 diagnostics for both settings, showing the reader does not slow capture.

**Given** the Story 10.20 held-out corner groups, once they exist
**When** the evaluation is run on them
**Then** the same report is added to `docs/evidence/10-23/`. Letting the model take part in a result (as the second read in the agreement gate, or deciding stickers Kotlin left UNCERTAIN) is not part of this story: it needs that report, a per-sticker safety result and a further sprint change proposal.

### Story 11.1: Quick Scan from two corners

As a Learner,
I want to scan my cube in two photos,
So that scanning takes seconds.

**Refs:** FR-22, FR-23 · recognition NFRs, NFR-11 · AD-9, AD-18 · OQ-7 · UX-DR15, UX-DR19 · UX Flow 3

**Depends on** Stories 10.2 and 10.3. **Human gate:** devices and both test cubes (device session C).

**Acceptance Criteria:**

**Given** `QUICK_SCAN` is BETA or GA
**When** the Learner holds the cube by a corner and captures twice ("1 / 2")
**Then** the adapter's corner detection and perspective unwarp (method from OQ-7, as confirmed by Story 10.10) emit `FaceSample`s, and two opposite-corner captures yield 54 stickers

**Given** faces that are uncertain or an invalid state
**When** assembly finishes
**Then** the Guided Scan grid asks for only those faces, never a guessed state

**Given** the test matrix
**When** Quick Scan is measured
**Then** it is recommended GA only at ≥ 95% valid after ≤ 3 corrections and < 15 s, otherwise BETA; its size cost fits NFR-11, and any OpenCV or LiteRT runtime is added only in the camera source set *(SCP 2026-11-14)*

### Story 11.2: Start the Course from my cube (Placement)

As a Learner who already knows some steps,
I want the app to look at my cube and suggest where to start,
So that I continue from where I really am.

**Refs:** FR-27, FR-4 · AD-6, AD-10, AD-18 · AR-21 · UX-DR19, UX-DR25 · UX Flow 4

**Acceptance Criteria:**

**Given** `PLACEMENT` is BETA or GA
**When** Welcome → *Start from my cube* (or Recognize's link) scans an accepted Full state
**Then** `PlaceFromScan` evaluates each Stage Predicate and suggests the first unmet Stage, with *Choose another Stage*; the existing `PlaceFromScan` writer (`core/application/.../usecase/PlaceFromScan.kt`) is reused *(SCP 2026-11-14)*

**Given** acceptance
**When** recorded
**Then** that Stage unlocks and earlier Stages get `StageSkippedByPlacement` (◇ *Skipped by placement*, never mastered, always revisitable)

**Given** edge outcomes
**When** the cube is unsolved, has a non-white cross, is at the LL, or is solved
**Then** the EXPERIENCE.md Placement-outcome copy and options show

**Given** `PLACEMENT` is OFF
**When** Welcome and Recognize render
**Then** *Start from my cube* is absent everywhere, and the persisted onboarding-complete behavior (#137) is unchanged *(SCP 2026-11-14)*

**Given** this story wires Welcome's *Start from my cube* (`AppShell` passes `onStartFromCube`) *(SCP 2026-11-16 main-app entry points)*
**When** the PR merges
**Then** `PLACEMENT` goes back to GA in debug in the same PR, and an AppShell test taps the button and reaches the scan

*Amended by SCP 2026-11-20 v1.1 promotion (#251, #252), built as issue fixes through `/bmad-orchestrate-issues`; acceptance on the Lenovo Tab M10 Plus 3rd Gen and the Galaxy S24 FE, main app build, with `PLACEMENT` raised in debug:*

**Given** an accepted cube (scan or Editor)
**When** Placement shows its result
**Then** it shows the cube reading, the reason and the suggestion in that order (EXPERIENCE.md *Placement outcomes*), all from content strings, read by TalkBack in that order, with the line "The lesson sets up its own cube, so you can put yours aside."

**Given** a cube 1 or 2 face turns from solved (including a last-layer or first-layer turn)
**When** Placement evaluates it, before any Stage Predicate
**Then** a dedicated outcome names the turn(s) in words, found by a bounded enumeration of at most 2 Moves (never the Solver, AD-8)

**Given** a cross or first two layers solved on a color other than white
**When** shown
**Then** the reading credits the solve and the reason explains why the Course starts from white, suggesting *First cross* (PO approved 2026-11-20), with *Show me the last layer anyway* added when the first two layers are solved

**Given** a partly solved cube
**When** shown
**Then** the reading names what is done and the next Stage's progress from its Objective progress measure (AD-6), e.g. "2 of 4 first-layer corners are in place", and an at-last-layer cube names its Case when exactly one fits (AD-19)

**Given** an onboarded Learner and `PLACEMENT` above OFF
**When** they open Learn → *Find my place* → *Scan my cube*
**Then** "This keeps your progress. Stages you've finished stay as they are." shows, then the usual scan, review and Placement; accepting only adds progress (AD-10: earlier `LOCKED`/`IN_PROGRESS` Stages become *Skipped by placement*, the target unlocks, nothing is re-locked or erased); a target already reached shows "You've already reached *Stage*." with *Open Stage* and writes nothing

**Given** `PLACEMENT` is OFF
**When** Learn renders
**Then** *Find my place* offers only *Choose my level* (Story 14.1), with no dead control

*Amended by SCP 2026-11-24 github-issue-triage (#324), built as an issue fix through `/bmad-orchestrate-issues`; v1.0 scope; acceptance on the Lenovo Tab M10 Plus 3rd Gen and the Galaxy S24 FE, main app build, with `PLACEMENT` raised in debug:*

**Given** a solved cube accepted into Placement
**When** the outcome shows
**Then** it offers *Scan again*, *Start Stage 1* and *I can already solve it*, in that order; the reading and reason lines are unchanged (EXPERIENCE.md *Placement outcomes*)

**Given** *I can already solve it*
**When** it is tapped
**Then** the existing `EntryLevelScreen` (Story 14.1) opens; nothing is written until a level is chosen there (then `ChooseEntryLevel`, as Story 14.1 says), and Back returns to the solved outcome

**Given** the Placement tests
**When** they run
**Then** they cover all three actions and the Back navigation from the picker; the new label comes from content strings and fits at 200% font on the Lenovo's 600 dp width

### Story 11.3: Recognize an OLL/PLL Case

As a Learner practicing the last layer,
I want to photograph my top layer and be told which Case it is,
So that I can look up the right Algorithm.

**Refs:** FR-28, FR-18, FR-27 · recognition NFRs · AD-9, AD-18, AD-19 · UX-DR19, UX-DR25 · UX Flow 5

**Human gate:** capture acceptance on a device with the stickered cube (device session C); matching is JVM-tested.

**Acceptance Criteria:**

**Given** `CASE_RECOGNITION` is BETA or GA
**When** Library → *Recognize case* captures two opposite top-corner views, with yellow on top (U + two sides each; "1 / 2") *(SCP 2026-11-18)*
**Then** `RecognizeCase` matches the visible stickers through `LastLayerMatcher` under AUF, intersects the two captures' candidates, and returns the Case within 1 s

**Given** a unique match
**When** shown
**Then** "OLL 45 · T-shape" opens the Algorithm detail with its Demonstration playing, and *Mark as Learning* is offered, never automatic

**Given** several candidate Cases
**When** shown
**Then** "This could be one of 3 cases" lists candidate tiles with *Scan another angle*, never a silent pick

**Given** the first two layers are unsolved
**When** recognized
**Then** "The first two layers aren't solved yet." shows with *Start from my cube* when `PLACEMENT` is on

**Given** no Case fits, or the last layer is already solved *(SCP 2026-11-18)*
**When** recognized
**Then** "I couldn't match this to a case. Try another angle or better light." shows with *Scan another angle* and *Browse the Library*, or "Your last layer is already solved." shows with *Back to Library*

**Given** no saved Color Calibration, a camera denial, or *Enter colors instead* *(SCP 2026-11-18)*
**When** Recognize opens
**Then** Color Calibration runs once and returns to the capture (closing it unsaved returns to the Library), or the Manual Cube Editor opens and its accepted cube goes through the same matching

## Epic 12: Release-ready for learners and reviewers

The app meets every release gate in PRD §7, and a hiring manager's five-minute evaluation (UJ-6, Flow 6) goes cleanly.

### Story 12.1: Accessibility audit and fixes

As a Learner with a disability,
I want every feature usable with TalkBack, large text and without color,
So that the app works for me too.

**Refs:** NFR-7 · AD-14, AD-17 · UX-DR1, UX-DR21, UX-DR28, UX-DR29 · PRD §7 gate 5

**Acceptance Criteria:**

**Given** the token contrast test
**When** CI runs
**Then** every text/status/highlight pair meets the DESIGN.md ratios in both themes

**Given** dynamic type at 200% on compact and expanded layouts
**When** every screen is checked
**Then** the lesson panel reflows and scrolls, the cube stage keeps its minimum size, and no control truncates

**Given** a TalkBack and switch-access pass over every Key Flow
**When** it completes
**Then** every essential action has a non-gesture route and a content label (Android lint a11y checks green), and no banned pattern (UX-DR28) is present

*Device follow-up (`sprint-change-proposal-2026-11-10-session-end-deferrals.md`): the physical pass also checks, on the reference phone at 200% type, that a vertical drag on the Lesson cube turns or rotates the cube and does not scroll the Lesson stage (the stage became scrollable in this story). A conflict is fixed as a Story 12.1 follow-up before it leaves `review`.*

### Story 12.2: Performance and compatibility on the reference devices

As a Learner on an older phone or a tablet,
I want the app smooth and correctly laid out,
So that it works on the device I have.

**Refs:** NFR-1, NFR-2, NFR-3, NFR-13 · AD-14, AD-17 · UX-DR23

**Acceptance Criteria:**

**Given** Macrobenchmarks on the minimum and mid reference devices
**When** face turns and View Rotation run
**Then** ≥ 30 FPS on minimum and 60 FPS on mid-range, with input acknowledged within 100 ms

**Given** the production solve corpus on the minimum device, with Story 3.8 calibration and calibrated corpus evidence linked
**When** run
**Then** 95% solve within 3 s, with median, p95, memory and failure rate reported

**Given** phone and tablet in portrait and landscape on API 29 through targetSdk 36
**When** every Key Flow runs
**Then** layouts follow their window class and rotation never changes session state

*Annotated by SCP 2026-11-12: the minimum reference device is now the Lenovo Tab M10 Plus 3rd Gen. This story's results on the Galaxy Tab S7 FE 5G stay as dated evidence. The Lenovo meets AC1 (61.96 FPS face turns, 59.69 FPS View Rotation, input → frame p90 53/21 ms) and AC2 (10,101/10,101 within 3 s, 0 failures, p95 139 ms, max 1.56 s). For AC3 on this device the PO accepted (2026-11-12) running the Key Flow suite with `wm set-ignore-orientation-request false`, restored afterwards: 7/7 flows and 43/43 surfaces pass. Rotation under the tablet's default settings is a harness follow-up in #119, not a v1.0 gate.*

### Story 12.3: Stability, size and privacy hardening

As a Learner,
I want an app that doesn't crash, stays small and keeps my data on my phone,
So that I can trust it.

**Refs:** NFR-5, NFR-8, NFR-9, NFR-11 · AD-13, AD-15, AD-16 · AR-4, AR-5

**Depends on** Story 13.6 (privacy-policy URL registered; the data-safety form needs it).

**Acceptance Criteria:**

**Given** the release AAB
**When** CI checks run
**Then** size is < 30 MB installed, the merged manifest has no `INTERNET` and no runtime permissions (v1.0 is camera-free) *(SCP 2026-11-24 camera-release-timeline: this held for the camera-OFF builds through 0.10.x, where 12.3 was accepted; from 0.11.0, and in v1.0.0, the one runtime permission is `CAMERA`, checked by Story 10.11 AC1 and gate 6)*, and no denylisted SDK is present

**Given** a fresh install in airplane mode
**When** the Course, Library, cube entry and solve are exercised
**Then** all work (NFR-5)

**Given** the closed-testing build's Android vitals
**When** reviewed before production
**Then** crash-free sessions are ≥ 99.5% and ANR is below the bad-behavior threshold, and the Play data-safety form declares "no data collected"

### Story 12.4: Pilot findings fixed and release gates passed

As the developer,
I want the pilot's severity-1 and severity-2 issues fixed and every gate checked,
So that production ships with evidence, not hope.

**Refs:** PRD §7 gates 1–6, §8 SM-1–SM-9 · AD-14 · OQ-5

**Acceptance Criteria:**

**Given** the pilot record from Story 7.8 (task completion, hint use, points of confusion, recovery from deliberate mistakes)
**When** issues are triaged
**Then** every severity-1 and severity-2 usability issue is fixed or explicitly resolved, and SM-1–SM-9 baselines are written down

**Given** the release-gate checklist
**When** it is run
**Then** gates 1–5 each link to their passing evidence (CI runs, scenario suites, e2e suite, pilot notes, offline/a11y checks), and gate 6 is recorded N/A for v1.0 (camera flags OFF in release, no `CAMERA` permission) *(SCP 2026-11-24 camera-release-timeline: N/A held for the camera-OFF 0.10.x builds on which 12.4 was accepted. v1.0.0 raises camera flags, so gate 6 is checked per feature by Story 10.11 and re-run on the 1.0.0 tag, which Story 12.5 records)*

### Story 12.5: Store listing and public portfolio repo

As a hiring manager,
I want a polished listing and a public repo I can judge in five minutes,
So that I can shortlist the developer with confidence.

**Refs:** PRD §7, UJ-6, SM-13 · AD-15 · OQ-1 · UX Flow 6 · Epic 13

**Depends on** Stories 13.4 and 13.5 (the privacy-policy and support pages, `sprint-change-proposal-2026-11-13-cubentia-site.md`).

**Acceptance Criteria:**

**Given** OQ-1
**When** the trademark search is done
**Then** the final app name and icon contain no "Rubik's" mark, and the codename is replaced in the listing

**Given** the Play listing
**When** it is submitted
**Then** it has screenshots, a short promo video recorded from the app, the privacy-policy URL registered in Story 13.6, the website from Epic 13 as the listing's website, and a completed content-rating questionnaire; the store screenshots are also added to the website's landing page

**Given** the repo goes public after the app works end to end
**When** a reviewer opens it
**Then** the README shows screenshots, an architecture overview linking `docs/ARCHITECTURE.md`, green CI and coverage badges, and a Conventional Commits history, and the GitHub repository and local working folder are renamed to Cubentia (deferred here from Story 12.6); the site repository's `APP_REPO` variable and its source-repository links are updated to the new name, and the site's `check` job passes afterwards. The site repository itself is never renamed, so the registered privacy URL does not change.

**Given** all gates pass on the `v1.0.0` build, including PRD §7 gate 6 for every camera feature it raises (Story 10.11)
**When** the build is promoted
**Then** it moves closed testing → production manually in Play Console *(SCP 2026-11-24 camera-release-timeline: production is a new `v1.0.0` tag cut from main with the same flag levels as 0.11.0; gates 3 (a green Release pilot run) and 6 are re-run on that tag and recorded here, and gate 4 is cleared by the 0.11.0 pilot record)*

### Story 12.6: Rebrand to Cubentia

As the developer,
I want the app, its application ID and its code named Cubentia before the first Play upload,
So that the store identity is permanent and free of the "Rubik's" trademark.

**Refs:** PRD OQ-1, §7 store readiness · NFR-8, NFR-10 · AD-13, AD-15 · `sprint-change-proposal-2026-11-08-cubentia-and-pre-pilot.md`

**Acceptance Criteria:**

**Given** no rename has been applied
**When** the story starts
**Then** a written risk analysis in the spec covers at least: application-ID permanence after the first upload; the Room DB file rename (no installed users yet; developer devices lose local data); package and namespace rename effects (R class, Hilt/KSP/Room generated code, Room schema export paths, R8/ProGuard keep rules, Macrobenchmark target package, `--tests` filters in CI and docs); the excluded paths; and rollback (one revertable commit range)

**Given** the corrected pattern `rubikxplorer` (case-insensitive: `rubikxplorer`, `RubikXplorer`, `RUBIKXPLORER`)
**When** the rename script runs in dry-run mode
**Then** it lists every file whose contents change and every directory to `git mv`, with the exclusions applied (`_bmad/`, `.claude/skills/`, `.git/`, build outputs, and historical `_bmad-output` records: `sprint-change-proposal-*`, `*retro*`, `.memlog.md`, `spec-*`, planning folder names), and the listing is recorded in the spec before any write

**Given** the dry run
**When** the rename runs
**Then** `applicationId = "com.exxerpro.cubentia"`; packages and folders become `dev.cubentia.*` (`git mv`, history preserved); the Android namespaces become `dev.cubentia.app` and `dev.cubentia.macrobenchmark`; `rootProject.name = "Cubentia"`; `app_name` is "Cubentia"; the DB file is `cubentia.db`; `RubikXplorer*` identifiers become `Cubentia*`; README, docs, `.github/` and `Fixtures/` say Cubentia; and `rg -i rubikxplorer` matches only excluded paths. Other `Rubik*` code identifiers (`RubikTheme`, `RubikDatabase`, …) stay and are listed in the spec for a Story 12.5 decision

**Given** all Learner-facing text (content strings and `strings.xml`)
**When** content tests run
**Then** no string contains "Rubik" (a test enforces it), and the two current strings read "…the cube…"

**Given** the renamed tree
**When** `./gradlew spotlessCheck build`, `./gradlew -p build-logic test` and `./gradlew compileDebugAndroidTestKotlin` run
**Then** all pass, CI is green, and the merged release manifest's package is `com.exxerpro.cubentia`

### Story 12.7: Physical progress is recorded only when true

As a Learner on a real cube,
I want my progress, undo and resume to reflect what I actually confirmed,
So that the app never records or restores something my cube didn't do.

**Refs:** FR-3, FR-7, FR-8, FR-19 · NFR-4, NFR-6 · AD-3, AD-10 · UX-DR20 · Epic 8 retro items 1–3

**Acceptance Criteria:**

**Given** Stage 6 in Physical-cube Mode with the Objective met
**When** the Learner has not tapped *Yes, it's solved*
**Then** no `LessonCompleted` is written: a `PhysicalSolveConfirmed` SessionEvent (through `reduce`) gates `completeLesson` in physical sessions, the confirmed and unconfirmed flags live in `SessionState` and the snapshot (not `rememberSaveable`), the `pendingPhysicalSolve` workaround is removed, and the `"lesson-06-03"` literals become one source; tests cover re-entry and process death before and after confirming

**Given** physical mode
**When** the Learner taps Undo
**Then** the Instruct text ("Turn {move} on your cube to undo.") is shown and announced in a polite live region instead of being mapped to an animation, and Redo is disabled in physical mode (or emits its own Instruct)

**Given** a mode switch to physical
**When** it is reduced
**Then** a physical Checkpoint is persisted without moving the recovery boundary, and a `SessionHostCheckpointTest` covers switch → `SolvePlaybackStarted` → `endSolve` → `PHYSICAL_AWAITING_CONFIRM`

### Story 12.8: The mismatch ladder resolves a mismatch

As a Learner whose cube doesn't match,
I want the answers I give the tutor to actually fix the tracked state,
So that I can continue instead of being stuck.

**Refs:** FR-9, FR-10, FR-12 · AD-3, AD-5 · Epic 8 retro item 4 · PO contract decision 2026-11-08

**Acceptance Criteria:**

**Given** `PHYSICAL_MISMATCH`
**When** the Learner enters the move they made (rung 1)
**Then** it is committed as their real move: Expected updates, the state returns to `PHYSICAL_PREDICTED`, and Certainty is the diagnosed one (never a false VERIFIED)

**Given** `PHYSICAL_MISMATCH`
**When** the Learner taps *It matches after all*
**Then** the state returns to `PHYSICAL_PREDICTED` with Expected unchanged

**Given** rung 2 (the one-face question)
**When** it is answered
**Then** it guides to re-entry (rung 3), and no `PhysicalObservation.Partial` is produced while every camera flag is OFF (partial observations arrive when a camera flag is raised (Story 10.11))

**Given** a full state entered in the Manual Cube Editor from physical mode
**When** it is accepted
**Then** the state is `PHYSICAL_VERIFIED`, and an offered Guided recovery survives the physical confirms that follow

**Given** the cases above
**When** reducer tests run
**Then** each case has a test, and `TRANSITIONS.md` matches the reducer for every case

*(SCP 2026-11-25 retire-v1-1: v1.1 is retired; v1.0 scope.)*

### Story 12.9: One source for physical copy; skip-check needs the virtual cube

As a Learner,
I want consistent physical-mode wording and a skip-ahead check that proves I can do it,
So that the app speaks with one voice and never skips a Stage on my word alone.

**Refs:** FR-4, NFR-10 · AD-6 · UX-DR24 · Epic 8 retro items 6, 7 · PO decision 2026-11-08

**Acceptance Criteria:**

**Given** the mismatch ladder and other physical-mode copy
**When** it is rendered
**Then** the text resolves from `en.json` content keys (the existing `msg.physical.mismatch.*`), and the unreferenced `strings.xml` duplicates are deleted

**Given** the skip-ahead check
**When** it is open
**Then** the Physical mode switch is hidden, so the check can be passed only on the virtual cube; Independent practice keeps Physical mode, and its self-reported nature is recorded as an accepted v1.0 limitation

### Story 12.10: Prove the release-permission gate end to end

As the developer,
I want the build to prove that a release can never ship a runtime permission,
So that the camera-free v1.0 promise is enforced, not assumed.

**Refs:** NFR-8 · AD-13, AD-14 · Epic 8 retro item 10 · epic-2-retro-item-4

**Acceptance Criteria:**

**Given** a Gradle TestKit fixture
**When** `checkReleaseMergedManifest` runs on a manifest that declares `CAMERA`
**Then** it fails, and the debug variant passes (shares setup with epic-2-retro-item-4)

**Given** `CAMERA_SCAN` at BETA
**When** `AppShell` opens the Stage 6 lesson in physical mode
**Then** the camera wording appears, proving the flag reaches `LessonScreen`

**Given** an unknown or null build type
**When** the manifest policy runs
**Then** it keys on `variant.debuggable` (or fails), so no build type can bypass the release rule

### Story 12.11: Sessions end when practice closes or progress resets

As a Learner,
I want closing practice and resetting progress to really end what I was doing,
So that Lessons teach me properly and locked content stays locked.

**Refs:** FR-17, FR-19, FR-4 · AD-3, AD-10 · Epic 9 retro items 1–3 · SCP 2026-11-10

**Acceptance Criteria:**

**Given** LL practice (live, or restored on cold start) or any other practice
**When** the Learner closes it and opens a Course Lesson
**Then** the Lesson runs as `LESSON` with its Teaching Modes, and meeting its predicate writes `LessonCompleted`

**Given** a live Lesson, 2-look or practice session
**When** the Learner confirms *Reset progress*
**Then** the session ends, its snapshot is deleted, the Continue card offers nothing locked by the reset, and a cold start restores nothing

**Given** Algorithm Statuses not yet loaded, or the status stream failed
**When** a 2-look Lesson ends
**Then** the "Add these algorithms to Learning?" offer is not shown, and accepting it never changes an Algorithm whose current status is not *Not started*

**Given** the status repository's upstream fails
**When** the Library is open
**Then** the failure is surfaced or retried; the stream does not silently end as "all *Not started*"

### Story 12.12: 2-look rules reconciled

As a Learner,
I want *Mastered* to mean I've finished and proven 2-look,
So that the mini-Course status is honest.

**Refs:** FR-17 · AD-19, AD-6 · Epic 9 retro items 4–7 · epic-2-retro-item-3 · SCP 2026-11-10

**Acceptance Criteria:**

**Given** two qualifying LL practice solves but not every 2-look Lesson completed
**When** progress is read
**Then** the row shows *Completed* or *In progress* with "Mastery: 2 of 2", not *Mastered*, and no milestone plays

**Given** that state
**When** the last 2-look Lesson is completed
**Then** the row becomes ◆ *Mastered* and the milestone plays once

**Given** every 2-look Lesson's Demonstration
**When** content tests run
**Then** `TwoLookSolve` scores it 0 off-path Moves, and its end state satisfies the Lesson's `LastLayerMatcher`-built predicate

**Given** the shell picks each 2-look Lesson's recognition diagram
**When** shell tests run
**Then** for at least one OLL and one PLL Lesson it equals `RecognitionDiagram.derive` of the Lesson's first referenced Algorithm

**Given** 2-look Learner copy
**When** the Learner-text gate runs
**Then** the 2-look strings come from `en.json` keys, and a new Learner string in `strings.xml` fails the gate

**Given** `TRANSITIONS.md`
**When** it is read
**Then** it describes the LL-practice `practiceEntry` snapshot field and restore

### Story 12.13: Closing a session is durable when a write fails

As a Learner,
I want a practice I closed to stay closed,
So that the app never brings back something I already ended.

**Refs:** FR-4, FR-12 · AD-10, AD-16 · Story 12.11 deferrals D1 and D2 · `sprint-change-proposal-2026-11-10-session-end-deferrals.md`

**Acceptance Criteria:**

**Given** a live Lesson, practice or 2-look session
**When** it ends normally (`SessionEnded`: close or Back)
**Then** the host writes an *ended* snapshot for that session through the revision-conditional snapshot write, together with any pending attempt rows, and deletes no snapshot row (AD-10)

**Given** an *ended* snapshot is the latest row
**When** the app cold starts
**Then** no session is restored from snapshots, and the existing Checkpoint resume behaves as before

**Given** the *ended* snapshot write fails
**When** the host's writer runs its next operation, and when the app goes to the background
**Then** the pending end write is retried until it is saved, and a cold start after a successful retry restores nothing

**Given** a pending end write and a newer session that saves its own snapshot first
**When** the retry would run
**Then** the pending end write is dropped, so it never hides or replaces the newer session's snapshot

**Given** snapshot payloads written before this story
**When** they are decoded
**Then** they still restore as before; the *ended* marker ships with a decoder for the previous payload format and a round-trip test

**Given** *Reset progress*
**When** it runs
**Then** its behavior from Story 12.11 is unchanged: it deletes the snapshot and Checkpoint in the same writer step as the reset write and fails safely, with the dialog offering a retry

**Given** `AppShellLibraryTest > last two look Lesson milestone practices a fresh LL scramble` (failed once in CI on PR #101, passed on rerun)
**When** the story closes
**Then** the cause of the flake is found and fixed (or the test is made deterministic), and the test passes in at least 20 consecutive local runs

### Story 12.14: Solution Playback in 3D or on the 2D net

As a Learner following a Solution,
I want to watch it on the 3D cube, and switch to the flat net when I need all 54 stickers,
So that I can follow each turn the way that suits me.

**Refs:** FR-16, FR-19 · NFR-1, NFR-2, NFR-7 · AD-4, AD-17, AD-20 · UX-DR4, UX-DR6, UX-DR29 · Stories 5.4, 5.5, 12.2 · GitHub #175 · `sprint-change-proposal-2026-11-17-playback-view-and-move-glyphs.md`

**Runs before** Story 12.15, Story 12.3 and the store screenshots of Story 12.5.

**Acceptance Criteria:**

**Given** Solution Playback and no saved playback view
**When** it opens
**Then** it shows the 3D cube (`Cube3DRenderer`) in virtual playback, and the 2D net (`Net2DRenderer`) in Physical-cube Mode, where all 54 stickers can be compared with EXPECTED (PO decision 1)

**Given** the view switch icon on the cube stage toolbar
**When** the Learner taps it
**Then** the stage swaps between the 3D cube and the 2D net, the choice is saved through `UpdatePreference`, and from then on the saved choice wins in virtual and Physical-cube Mode, across restarts; *Reset preferences* clears it so the default of the first AC applies again

**Given** playback is mid-turn (an animation in flight, or several queued)
**When** the view is switched
**Then** the new view shows the committed state of the current step at once (`Snap`, AD-4), the playback position is unchanged, no `SessionEvent` is dispatched, and the certainty chip and the SEEN strip are unchanged (Solution Playback has no frame label; SCP 2026-11-19)

**Given** the 3D view during playback
**When** a step is current
**Then** the Story 5.4 Move arrow shows on the current Move (static under reduce motion)

**Given** TalkBack
**When** it focuses the stage and the toolbar
**Then** the 3D view reads the Story 5.5 cube description and offers *Read faces*, and the switch is labeled with the view it switches to ("Show the 2D net" / "Show the 3D cube"); the label comes from `en.json`

**Given** the 3D render sync suite (`Cube3DRenderSyncTest`, epic-5-retro-item-21)
**When** it runs on Solution Playback
**Then** `renderedFacelets()` of the 3D view equals the committed state after forward, back, restart and speed changes; tests also cover a mid-turn switch in both directions and the switch in Physical-cube Mode (Done, Undo, Quick Check result still shown)

**Given** the Story 12.2 Macrobenchmarks on the minimum and mid reference devices
**When** Solution Playback runs in the 3D view
**Then** it meets the Story 12.2 baseline (≥ 30 FPS minimum, 60 FPS mid, input within 100 ms; see #106), and `Net2DFrameTimingBenchmark` selects the 2D net explicitly through the view switch's exact test tag, replacing the broad `2D|Net` text fallback in `requireNet()`

### Story 12.15: Move pictures and the Move strip

As a Learner who reads notation slowly,
I want a small picture of each turn next to its notation, and a strip of all the Moves,
So that I can see what to do at a glance and where I am in the Solution.

**Refs:** FR-16, FR-19 · NFR-7 · AD-17, AD-20 · UX-DR14, UX-DR21, UX-DR29, UX-DR32 · Story 3.6 deferral (`epic-3-deferred-3-6-ux-dr14-move-strip`) · GitHub #176 · `sprint-change-proposal-2026-11-17-playback-view-and-move-glyphs.md`

**Depends on** Story 12.14 (the preference schema and the playback stage settle first). **Runs before** Story 12.3 and the store screenshots of Story 12.5.

**Acceptance Criteria:**

**Given** a pure move-to-picture function (no Android imports; `:app` only draws its output)
**When** it is called for every Move type (R L U D F B, M E S, wide Moves, x y z; plain, prime and half turn)
**Then** it returns the DESIGN.md *Move picture*: an ink-only 3×3 face with the moving layer shaded; a curved arrow for R/L/U/D, a round arrow for F/B and a straight arrow for M/E/S; a superscript 2 for half turns; and the wide and x/y/z designs. A test covers every Move

**Given** a prime Move
**When** its picture is built
**Then** it is its plain Move's picture with the arrow reversed (a mirroring test over every face, slice, wide Move and rotation)

**Given** F and B
**When** their pictures are built
**Then** both are drawn as seen from the front: F as the front grid with a clockwise round arrow, B as a round arrow behind the grid that reads counter-clockwise from the front, while B's MoveNote still says "Turn the Back face clockwise" (PO decision 4)

**Given** the shared playback transport (AD-20)
**When** it shows
**Then** it has the UX-DR14 Move strip (one chip per Move, current Move outlined in `ink-primary`, kept in view) and a visible scrubber that snaps to Move boundaries; a tap on a chip or a scrub jumps there with no animation (AD-4); in Physical-cube Mode the strip is read-only and the scrubber hidden, since only *Done* advances (FR-16). This closes `epic-3-deferred-3-6-ux-dr14-move-strip`

**Given** *Move pictures* is on
**When** a Move shows in the transport
**Then** its picture sits next to the current Move label and on every Move strip chip, and TalkBack skips the picture (no content description; the MoveNote is spoken)

**Given** Settings
**When** the Learner opens it
**Then** a *Move pictures* toggle sits next to color-blind glyphs, defaults to on (PO decision 2), persists through `UpdatePreference`, applies at once, and returns to on with *Reset preferences*; its label comes from `en.json`

**Given** screenshot tests
**When** they run
**Then** samples cover at least R, R', R2, F, B, M, Rw and x in light and dark themes, and the transport with the Move strip and pictures on and off

### Story 12.16: See the turn while dragging, commit on release

As a Learner turning the cube by touch,
I want to see which layer will turn, and which way, while I drag,
So that I only make the Move I meant, and can back out before it counts.

**Refs:** FR-1, FR-2 · NFR-1, NFR-2, NFR-4, NFR-7 · AD-4, AD-11, AD-17 · AR-8 · UX-DR5, UX-DR6, UX-DR26, UX-DR27, UX-DR29, UX-DR33 · GitHub #206 · action item `epic-5-retro-item-19-split-cube3drenderer-kt-551-lines-gestur` (gesture host part) · `sprint-change-proposal-2026-11-18-drag-preview-and-view-rotated.md`

**Runs before** Story 12.17 and Story 12.3. Needs a device check on the Lenovo Tab M10 Plus 3rd Gen and the Galaxy S24 FE (batched with 12.17).

**Acceptance Criteria:**

**Given** a pure `GestureInterpreter.preview(input: GestureInput, pose: ViewPose) → DragPreview?` in `:core:domain`, where `GestureInput` carries one touch path or two (one per finger), each read whole, not only its first and last points
**When** a one-finger drag starts on a sticker and has moved past the lock distance
**Then** it locks the drag axis and layer for the rest of the gesture and returns `DragPreview.Active(move, degrees)`: the Move in the cube frame (whatever the view pose) and a signed angle from the finger's travel along the locked tangent, clamped to [−90°, +90°]; a wobbly or curved drag along a row keeps its lock; before the lock it returns null; JVM fixtures cover pose × start sticker × direction, including curved paths and over-drag past 90°

**Given** a locked drag
**When** the finger comes back within the lock distance of where the drag began
**Then** `preview` returns `DragPreview.CancelZone` (never null once locked), the ghost layer eases back to 0°, and leaving the zone on the other side flips the Move (R ↔ R'), with the angle starting again from 0°

**Given** a locked one-finger drag
**When** the finger moves
**Then** that layer turns on screen with the finger (the ghost turn); all 9 cubies of the layer, centre included, get the `sticker-highlight` outline through a preview flag that is OR-ed with any Lesson `NetEmphasis` (never replacing it, and never taking part in the `uPulse` fill tint); a ghost arrow with the Move in `notation` (for example "R'") follows the finger; a teaching arrow (Demonstration, hint rung 2, playback) is hidden while the ghost is shown and comes back after; the preview is exposed to UI tests under its own semantics key (`PreviewedMoveKey`), separate from `RenderedArrowKey`; nothing is committed and `renderedFacelets()` still returns the committed state

**Given** a drag on a middle row or column
**When** it locks
**Then** it previews and commits the slice (M, E or S) exactly like an outer layer, so a slice is never a surprise (PO decision 2)

**Given** a two-finger drag that starts with at least one finger on the cube and both fingers moving in parallel
**When** it locks
**Then** it previews and commits the wide Move of the outer layer (±1 along the locked axis) that either finger touches, for example `r` when one finger is on the R layer and the other on M; if both fingers are on the middle layer, or on opposite outer layers, there is no preview and no Move; a two-finger twist still commits z on release, with no ghost turn; a two-finger drag that starts off the cube is still a View Rotation; the parallel / twist / pinch classifier is pure and has JVM fixtures (PO decision 3). With TalkBack on, a two-finger drag reaches the app as a one-finger drag, so wide Moves come from "more" on the Move key bar; that is expected, and NFR-7 is still met

**Given** a gesture in progress
**When** a second finger lands after a one-finger drag has locked, or one finger lifts during a locked two-finger drag
**Then** the late finger is ignored and the locked preview is kept; in a two-finger drag the preview freezes when the first finger lifts and resolves (commit or cancel) when the last finger lifts

**Given** a previewed Move (`Active`)
**When** the finger lifts, on or off the stage
**Then** the Move commits through the same `MoveIntent` → `MoveCommitted` path as a Move key (AD-4); the renderer holds the ghost layer at its angle until the matching `Animate(move)` arrives, then continues the turn from that angle over the remaining duration, with no frame back at 0° (§5.3 A2); the input is acknowledged within 100 ms, and the light haptic tick fires (switchable off)

**Given** a locked drag in `CancelZone`
**When** the finger lifts
**Then** the layer snaps back, nothing commits, no tick fires, and the Cube State is unchanged; the same happens if the system cancels or consumes the pointer stream mid-preview

**Given** a drag that never locks (below the confidence threshold)
**When** it ends
**Then** no preview was shown, no intent is returned, nothing is announced, and the Cube State is unchanged

**Given** a stage where Moves are blocked (no Move input is wired, for example a Lesson in Physical-cube Mode before *I did something else*)
**When** the Learner drags on the cube
**Then** no ghost turn is shown

**Given** a presentation queue that is still animating, or a Lesson reorientation or Demonstration job that is still running
**When** a drag starts on the cube
**Then** the job is cancelled and the queue finishes every outstanding item at once in order (`PresentationQueue.finishAll()`: commit the active `Animate`, then apply each waiting `Animate` and `Snap` without animating, closing any open `Cube3D.turn` trace section), so `renderedFacelets()` equals the last committed state before the preview is built; no committed Move is dropped

**Given** TalkBack is on
**When** a preview becomes `Active` or changes Move
**Then** the Move's plain name (from the existing plain-name helper) is announced politely, for example "R prime: right face counter-clockwise"; "Turn cancelled" is announced only when a locked drag ends in `CancelZone`

**Given** a Lesson in *Test me* or *Let me try*
**When** the Learner drags
**Then** the preview names the Move the Learner is making, never the expected one (the Tutor still judges only the committed Move)

**Given** new learner-facing copy ("Turn cancelled")
**When** it is added
**Then** it is a content key in `core/domain/src/main/resources/content/strings/en.json` read through `ContentStrings`, and `app/src/main/res/values/strings.xml` gains no learner text (the `checkLearnerText` gate stays green)

**Given** the gesture code
**When** the story is done
**Then** touch handling lives in its own gesture host component, not in `Cube3DRenderer.kt`; the renderer only draws committed state, the presentation queue and the renderer-local ghost turn

**Given** the Lenovo Tab M10 Plus 3rd Gen and the Galaxy S24 FE
**When** the PO plays Stage 1 and Stage 2 Lessons by gesture
**Then** they complete them without an unintended Move, cancel works, and frame time during a drag stays at the Story 12.2 baseline (≥ 30 FPS on the Lenovo, NFR-2)

### Story 12.17: Know when the view is rotated, and reset it in one tap

As a Learner who rotated the cube to look for a sticker,
I want to see that my view is turned, which face is which, and a clear way back,
So that I can still predict what R or U will do.

**Refs:** FR-2 · NFR-7 · AD-4, AD-17 · UX-DR4, UX-DR5, UX-DR29, UX-DR34 · GitHub #207 · action item `epic-5-retro-item-18-double-tap-reset-also-checks-tap-distanc` · `sprint-change-proposal-2026-11-18-drag-preview-and-view-rotated.md`

**Depends on** Story 12.16 (the gesture host owns double-tap). **Runs before** Story 12.3.

**Acceptance Criteria:**

**Given** `ViewOrbit` with an "at home" test (within a small tolerance of `ViewOrbit.DEFAULT`)
**When** the view leaves home by a drag, the overflow arrow buttons or any other View Rotation
**Then** a **View rotated** badge shows in the stage's top-left overlay, and it hides again once the view is back home

**Given** the five 3D stages (Lesson, free cube, Demonstration, Solution Playback in 3D, Library detail)
**When** the badge shows
**Then** it sits right after the frame label where one is shown, and first in the top-left overlay where none is (Solution Playback, virtual or physical, and a Lesson *Show me* Demonstration have no frame label; SCP 2026-11-19); the overlay wraps (a flow row) instead of clipping at 200% font scale; the badge's touch target is at least 48dp; TalkBack focus reaches it right after the frame label, or first on the stage when there is none; the 2D net has no badge

**Given** the view is rotated
**When** the Learner taps the badge's **Reset view** action or double-taps the stage
**Then** the view animates home by the shortest path in about 200 ms (it jumps when reduce motion is on), the badge hides, and the Cube State, Notation and Move key labels are unchanged; a drag that starts during the reset animation cancels it and starts from the current view; the free cube stage receives the reduce-motion setting like the other stages

**Given** the toolbar
**When** any 3D stage shows
**Then** there is no separate reset-view button at any width (Reset view lives on the badge); the ⋮ view menu with the View Rotation arrow buttons stays, so every rotation and its reset have a non-gesture route (NFR-7)

**Given** the existing screen tests that use `stage:resetView` (`FreeCubeScreenTest`, `LessonScreenTest`, `SolutionPlaybackTest`, `DemonstrationPlayerTest`, `AlgorithmDetailScreenTest`, `ScreenAudit`)
**When** the toolbar button is removed
**Then** they rotate the view first (for example through `stage:viewMenu` → `viewRotate:left`) and then use the badge's tag; `ScreenAudit` checks the badge's route and 48dp target while rotated, and checks that no badge is shown at home

**Given** double-tap on the stage
**When** two taps are close in time **and** in distance (`doubleTapSlop`)
**Then** the view resets; two far-apart taps don't reset it (a test covers far-apart taps; closes `epic-5-retro-item-18-…`)

**Given** a pure face-label helper in `:core:domain` (pose + stage size → label anchors)
**When** the view is rotated
**Then** a face gets a letter (U, D, F, B, R, L) in `notation`, in the cube frame, only when it faces the camera by more than a minimum angle (no labels on near edge-on faces); each letter sits outside the cube's projected silhouette, offset from its face centre away from the cube, and clamped inside the stage, so it never overlaps a sticker or the color-blind glyphs; JVM tests over a sweep of orbits and stage sizes (down to the smallest compact stage) assert no overlap with any sticker quad; the letters hide at home (PO decision 6)

**Given** TalkBack is on
**When** the view leaves or returns home
**Then** "View rotated" or "View reset" is announced once, and the badge and its Reset view action have content labels

**Given** new learner-facing copy ("View rotated", "Reset view" on the badge, "View reset")
**When** it is added
**Then** it is a content key in `en.json` read through `ContentStrings`, which the shared stage component receives on every stage, free cube included; `strings.xml` gains no learner text (the `checkLearnerText` gate stays green)

**Given** screenshot tests
**When** they run
**Then** they cover the home view (no badge, no letters) and a rotated view (badge and letters) in light and dark themes, on compact and expanded widths

## Epic 13: Cubentia on the web, with a privacy policy

Anyone can open Cubentia's public website, read what the app is, read a privacy policy that states exactly what the app does with data, and find how to get support. Play Console has a privacy-policy URL that never changes. The site lives in its own public repository (`hebelmx/cubentia-site`), so the app repository's rename in Story 12.5 does not affect it (`sprint-change-proposal-2026-11-13-cubentia-site.md`).

**Runs before** Stories 12.3, 12.4 and 12.5, and before the closed-testing review in #127 (Story 7.8 AC2). Critical path: 13.1 → 13.3 → 13.4 → 13.6. Story 13.3 lands in this repository; Stories 13.1, 13.2, 13.4 and 13.5 land in the site repository.

### Story 13.1: Create the site repository and publish it with GitHub Pages

As the developer,
I want a separate public repository that GitHub Pages publishes on every merge,
So that Cubentia's web pages have a home and a URL that never changes.

**Refs:** PRD §7 store readiness · NFR-8 · AD-13 · `docs/play-first-publish.md` § 2

**Human gate (before start):** the PO creates the public repository `hebelmx/cubentia-site`, sets **Settings → Pages → Source: GitHub Actions**, protects `main` (PRs only), and sets the repository variable `APP_REPO=hebelmx/RubikXplorer`.

**Acceptance Criteria:**

**Given** the new repository
**When** the story is done
**Then** it has a README (purpose, the never-rename rule, the no-personal-data rule for a public repo, how to preview locally), a licence chosen by the PO, `site/index.html` with a minimal Cubentia page, `site/404.html`, and `.github/workflows/pages.yml`

**Given** a push to `main`
**When** `pages.yml` runs
**Then** a `check` job (HTML validation, internal link check, no-third-party-resource check, no "Rubik" in page text) passes before a `deploy` job publishes `site/` through the GitHub Pages actions, and a pull request runs `check` only

**Given** the deployed site
**When** `https://hebelmx.github.io/cubentia-site/` is opened in a private browser window with no Google or GitHub sign-in
**Then** it loads over HTTPS, and the repository README records the final URL and whether the account's user site sets a custom domain

**Given** the workflow
**When** it is reviewed
**Then** it uses least-privilege permissions (`contents: read` for check; `pages: write`, `id-token: write` only for deploy), the `github-pages` environment, and action versions verified against GitHub's current documentation

### Story 13.2: Introduce Cubentia on a landing page

As a Learner or a hiring manager,
I want a short page that says what Cubentia is,
So that I know what I am installing or reviewing before I open the app.

**Refs:** PRD §1, §7, UJ-6 · NFR-7, NFR-8, NFR-10 · DESIGN.md color tokens · EXPERIENCE.md Voice and Tone

**Depends on** Story 13.1.

**Acceptance Criteria:**

**Given** the landing page
**When** it is read
**Then** it says in plain, calm sentences that Cubentia is an offline Android tutor that teaches a beginner to solve a 3×3 cube on a virtual or real cube, works without an account or internet connection, and links to the privacy policy and support pages; it never uses "Rubik" or "Rubik's", and it has no screenshot placeholders (screenshots are added from the Story 12.5 store assets)

**Given** any page on the site
**When** it is loaded
**Then** it sets no cookies, runs no script, and requests no resource from another origin (fonts self-hosted or a system stack), and the `check` job enforces this

**Given** the page in light and dark system themes, at 200% zoom and at 320 CSS px wide
**When** it is checked with the keyboard only and with an automated accessibility checker
**Then** text meets WCAG 2.2 AA contrast with the DESIGN.md tokens, nothing is cut off or scrolls sideways, focus is visible and in reading order, there is a skip link, `lang="en"`, landmarks and one `h1`, and the result is recorded in the PR

### Story 13.3: Export the app's privacy facts from the build

As the developer,
I want the build to state the app's privacy facts in one checked file,
So that the public privacy policy can be held to what the app really does.

**Refs:** NFR-5, NFR-8 · AD-13, AD-14 · AR-4 · `sprint-change-proposal-2026-11-13-cubentia-site.md` §3.3

**Acceptance Criteria:**

**Given** the release merged manifest and the release SDK-denylist result
**When** `:app:writePrivacyFacts` runs
**Then** it writes `docs/privacy-facts.json` with `schemaVersion`, `applicationId`, `internetPermission`, `backupAllowed`, `runtimePermissions` and `denylistedSdks` derived from the build, and the fixed NFR-8 facts `account`, `ads`, `analytics`, `crashReporting`, `dataLeavesDevice` and `storage`; for v1.0 the derived values are `false`, `false`, `[]` and `[]`

**Given** the committed `docs/privacy-facts.json`
**When** `./gradlew build` runs
**Then** `:app:checkPrivacyFacts` passes when the file matches the build, fails with a message naming `:app:writePrivacyFacts` and the site repository's privacy page when it does not, and CI's "Verify build wires the gates" step lists `:app:checkPrivacyFacts`

**Given** Gradle TestKit fixtures
**When** `./gradlew -p build-logic test` runs
**Then** a manifest that adds `INTERNET`, `allowBackup="true"` or a runtime permission produces facts that differ from the v1.0 file, and the check fails on them

### Story 13.4: Publish a privacy policy that matches the app

As a Learner,
I want a plain privacy policy I can read before I install,
So that I know Cubentia collects nothing and keeps my progress on my device.

**Refs:** NFR-5, NFR-8 · AD-13 · PRD §7 · `docs/play-first-publish.md` § 2

**Depends on** Stories 13.1 and 13.3.

**Human gate (before merge):** the PO confirms the publisher name ("Exxerpro Solutions, S.A. de C.V.") exactly as on the Play developer account, and the effective date.

**Acceptance Criteria:**

**Given** `/privacy/`
**When** it is read
**Then** it states, in plain language: Cubentia collects no personal data and shares none; it has no account, ads, analytics or crash reporting; it has no internet permission and does not back up data; it asks for no runtime permission; progress is stored only on the device and is removed when the app is uninstalled; it names the publisher ("Exxerpro Solutions, S.A. de C.V.") exactly as on the Play developer account, the contact email `exxerpro@exxerpro.com` and an effective date; it applies to Learners of any age; and it says the website sets no cookies and is hosted on GitHub Pages, which may log visitor IP addresses for security, with a link to GitHub's privacy statement

**Given** `docs/privacy-facts.json` on the app repository's `main` (`vars.APP_REPO`)
**When** the site's `check` job runs on a pull request, on push, daily and on manual dispatch
**Then** it fails if any fact lacks a `data-fact` statement with an equal `data-value` on the page, if the page states a fact the file lacks, if the publisher, contact email or effective date is missing, or if the policy text changed without a new effective date; and `deploy` runs only after `check` passes

**Given** the deployed page
**When** it is opened in a private window from a network outside exxerpro.com, with no sign-in
**Then** it loads as HTML (not a PDF) over HTTPS, and it meets the Story 13.2 accessibility and no-tracking criteria

### Story 13.5: Give Learners a support and contact page

As a Learner,
I want to know how to ask for help or report a problem,
So that I am not stuck when something goes wrong.

**Refs:** PRD §7 store readiness · NFR-8 · `docs/play-first-publish.md` § 3 (Store settings contact)

**Depends on** Story 13.1; lands before Story 12.5.

**Acceptance Criteria:**

**Given** `/support/`
**When** it is read
**Then** it gives the same contact email as the privacy policy (`exxerpro@exxerpro.com`), says what to include in a report (device, Android version, app version from Settings → Apps → Cubentia, what happened), says that no personal data is needed, links to the privacy policy, and points developers to the public source repository's issues without asking Learners to post personal details there

**Given** the landing page and the privacy page
**When** they are opened
**Then** each links to `/support/`, and the site's link check passes

### Story 13.6: Register the privacy policy in Play Console

As the developer,
I want the privacy-policy URL registered in Play Console and the runbook updated,
So that the closed-testing review can go ahead and nobody has to work out the URL again.

**Refs:** PRD §7 · NFR-8 · AD-15 · #127 · `docs/play-first-publish.md` § 2

**Depends on** Story 13.4.

**Human gate (before start):** the PO enters the URL in Play Console.

**Acceptance Criteria:**

**Given** the deployed privacy page
**When** the PO pastes `https://hebelmx.github.io/cubentia-site/privacy/` under **Policy and programs → App content → Privacy policy**
**Then** Play Console saves it, and the Data safety form can then be saved with "no data collected" (the answers in `docs/play-first-publish.md` § 2)

**Given** this repository
**When** the story's PR merges
**Then** `docs/play-first-publish.md` § 2 step 1 names the site repository and the registered URL instead of `docs/privacy.md`, the README links the website and the privacy policy, and #127 records the date the URL was registered (no tester data)

## Epic 14: Practice on my real cube and start at my level

A Learner who can already solve picks their entry level instead of proving every Stage, and any Algorithm or Lesson step can be practiced on a real cube with *Done*, with a Quick Check at any step. *(SCP 2026-11-20 v1.1 promotion, #208, #209.)* Camera parts are behind `CAMERA_SCAN` (AD-18); everything else works with every camera flag OFF.

### Story 14.1: Choose my entry level when I can already solve it

As a Learner who already solves with the beginner method or 2-look,
I want to say where I am instead of passing every Stage check,
So that I go straight to what I want to learn next.

**Refs:** FR-4, FR-17, FR-19 · AD-6, AD-10, AD-18 · UX-DR18, UX-DR22, UX-DR25 · UX Flow 8 · Story 7.7 · #209

**Depends on** Story 7.7. **Human gate:** a device check on the Lenovo Tab M10 Plus 3rd Gen and the Galaxy S24 FE, on the main app build.

**Acceptance Criteria:**

**Given** Welcome → *I can already solve it*, Learn → *Find my place* → *Choose my level*, or Placement's solved outcome → *I can already solve it* (SCP 2026-11-24, #324; Story 11.2)
**When** the picker opens
**Then** "Where do you want to start?" lists *I can solve the first two layers*, *I solve with the beginner method*, *I know 2-look OLL and PLL* and *Check me with a scramble instead*, with "Skipped Stages stay open, and nothing is marked mastered." and, when `PLACEMENT` is on, *Not sure? Scan my cube*

**Given** a level is chosen
**When** `ChooseEntryLevel` records it
**Then** it appends `StageSkippedByChoice` for each earlier Stage that is `LOCKED` or `IN_PROGRESS` and `StageUnlocked` for the target (Stages 1–4 → *Last-layer orientation*; Stages 1–7 → *Faster last layer*; Stages 1–7 and both 2-look Stages → the Library filtered to the full OLL set), each skipped Stage shows ◇ *Skipped*, never mastered, and stays revisitable

**Given** existing progress (a Stage completed, mastered or skipped)
**When** a level is chosen
**Then** nothing is re-locked, downgraded or erased, and no `ProgressReset` is written (AD-10, only add)

**Given** *Check me with a scramble instead*
**When** chosen
**Then** the Story 7.7 whole-Course skip-ahead check runs unchanged

**Given** the progress log
**When** the new event type is added
**Then** `StageSkippedByChoice` round-trips through Room (with a migration and migration test if the stored type is a closed set), `StageProgress.status` treats it like `StageSkippedByCheck`, and `ChooseEntryLevel` is its only writer (Ownership Matrix)

**Given** the copy
**When** it renders
**Then** it comes from content strings, reads correctly with TalkBack, and fits at 200% font on the Lenovo's 600 dp width

### Story 14.2: Practice an Algorithm on my real cube

As a Learner building muscle memory,
I want to do any Algorithm on my own cube one turn at a time,
So that I learn it with my hands, not only on screen.

**Refs:** FR-18, FR-16, FR-26, FR-7 · AD-3, AD-5, AD-18, AD-19, AD-20 · UX-DR12, UX-DR14 · UX Flow 11 · Stories 8.5, 9.5, 10.7 · #208

**Depends on** Stories 9.5 and 8.5; the Quick Check AC also on Story 10.7. **Human gate:** a device check on the Lenovo Tab M10 Plus 3rd Gen and the Galaxy S24 FE, on the main app build, from Library → an Algorithm.

**Acceptance Criteria:**

**Given** any of the 78 Algorithm details
**When** the Learner taps *I'm doing it on my cube*
**Then** they choose *Set it up from solved* or *My cube already shows this case*, and a `SOLVE_PLAYBACK` session opens in Physical-cube Mode on the 2D net whose Move list is the set-up Moves (the Algorithm's inverse, AD-19) then the Algorithm, labeled as *Set-up* and *Algorithm* segments (AD-20), or the Algorithm only

**Given** the practice session
**When** the Learner taps *Done*
**Then** it advances one Move with the Expected State, `MoveNote` and mismatch ladder exactly as Solution Playback in Physical-cube Mode (Story 8.5), and the last *Done* offers *Again*

**Given** `CAMERA_SCAN` above OFF
**When** the Learner is at any step, including mid-Algorithm
**Then** *Quick Check* is available and behaves as Story 10.7 (a match verifies; a mismatch is *likely* and offers a Guided 6-face Scan)

**Given** `CAMERA_SCAN` OFF
**When** practice runs
**Then** it works end to end with *Done* only and no camera control is shown

**Given** a Learner who knows the Case
**When** they choose *My cube already shows this case*
**Then** practice starts at the Algorithm with no Recognize or Quick Scan step

### Story 14.3: Practice every Lesson step on my real cube, with a Quick Check at any step

As a Learner in any Lesson, including 2-look,
I want Physical-cube Mode and a Quick Check wherever I am,
So that I can confirm my cube mid-Algorithm instead of only at the end of a step.

**Refs:** FR-8, FR-17, FR-26, FR-7 · AD-3, AD-5, AD-18 · UX-DR12 · Stories 8.2, 9.7, 10.7 · #208

**Depends on** Stories 8.2, 9.7 and 10.7. **Human gate:** a device check on the Lenovo Tab M10 Plus 3rd Gen and the Galaxy S24 FE, on the main app build.

**Acceptance Criteria:**

**Given** any Lesson, including every 2-look Lesson (`TWO_LOOK`)
**When** it opens
**Then** the Physical-mode switch is offered and works as Story 8.2 (*Done*, Expected State, mismatch ladder)

**Given** Physical-cube Mode and `CAMERA_SCAN` above OFF
**When** the Learner is at any step, including partway through a step's Moves
**Then** the *Quick Check* icon is available (highlighted only at Objective boundaries, milestones and after mismatches), never required, and its result follows FR-26 (never a false *verified*)

**Given** `CAMERA_SCAN` OFF
**When** the Lesson renders
**Then** no camera control shows and Physical-cube Mode still works

## Epic 15: Method-aware solutions

A Learner can see a solution for any cube shaped by the method they are learning (beginner layer-by-layer, 2-look OLL/PLL, or full OLL + PLL) instead of the shortest one, each part tagged with the Stage or Case it practices. The Solve tab keeps the optimal solution as its default. *(SCP 2026-11-20 v1.1 promotion, #210; FR-30, AD-21. v1.0 scope by PO decision 2026-11-20; sits next to the P1 FR-29 drills.)*

### Story 15.1: Build a beginner-method solution from the Course's Stages

As a Learner,
I want a solution for my cube that follows the beginner method I'm learning,
So that each part matches a Stage I know.

**Refs:** FR-30, FR-15, FR-4 · AD-6, AD-8, AD-19, AD-21 · NFR-3, NFR-12

**Acceptance Criteria:**

**Given** any valid `CubeState`
**When** `MethodSolve(BEGINNER)` runs as an Effect with a timeout
**Then** it returns segments tagged with Course Stage IDs in order (cross, first-layer corners, middle layer, last-layer orientation, last-layer completion), never by calling `CubeSolver` (AD-8, AD-21): the first-two-layer segments (Stages 2–4) are each found by the bounded `preservedPieces` search (the `RecoveryPlanner` search) against that Stage's completion predicate, and the last-layer segments (Stages 5–6) use `LastLayerMatcher` (AD-19) with the Stages 5–6 Lessons' Algorithms *(SCP 2026-11-22 epic-15-ac-wording: aligned with AD-21, PO decision 2026-11-22)*

**Given** the FR-15 corpus (solved plus ≥ 10,000 random legal states)
**When** the JVM suite applies each result to its input
**Then** every result solves its input, no segment breaks a piece placed by an earlier segment, and median and p95 time on the JVM are reported

### Story 15.2: Last-layer segments for 2-look and full OLL + PLL, preferring my Algorithms

As a Learner going faster,
I want the last layer solved with the Algorithm set I'm learning,
So that the solution drills the Algorithms I chose.

**Refs:** FR-30, FR-17, FR-18 · AD-19, AD-21 · Story 15.1

**Acceptance Criteria:**

**Given** a cube after the first two layers
**When** `MethodSolve` runs with `TWO_LOOK` or `FULL_OLL_PLL`
**Then** the last-layer segments use only Library Algorithms matched through `LastLayerMatcher` (2-look: the 2-look Lessons' Algorithms; full: one per Case), tagged with Case IDs, and when more than one fits, an Algorithm with status *Learning* or *Learned* wins; the first last-layer segment starts with one Cube Rotation that brings yellow to U (`z2` for the usual hold; none when yellow is already on U), then each Algorithm plays exactly as written, with U adjustments; the final AUF joins the last Case segment, and a lone AUF (no Case) is one segment tagged with the method's last-layer Stage *(SCP 2026-11-22 epic-15-ac-wording: 15.2 PO clarifications 1–2, 2026-11-22)*

**Given** the corpus
**When** verified
**Then** every result solves its input and uses only Algorithms of the chosen set plus U adjustments and the one leading Cube Rotation

### Story 15.3: Choose a solution style in Solution Playback

As a Learner,
I want to switch between the shortest solution and one in my method,
So that I can watch how I would solve this cube.

**Refs:** FR-30, FR-16 · AD-3, AD-20, AD-21 · UX-DR14 · Stories 15.1, 15.2

**Acceptance Criteria:**

**Given** Solution Playback from the Solve tab
**When** it opens
**Then** *Shortest* (the optimal solution) is selected and plays by default, with a *Solution style* control offering *Beginner*, *2-look* and *Full OLL + PLL*; the method suggested from progress is not preselected: its chip carries a "Suggested" badge, and one tap starts it *(SCP 2026-11-22 epic-15-ac-wording: PO decision 2026-11-22)*

**Given** a method style
**When** chosen
**Then** playback restarts at step 1 with segment labels (the Stage or Case name from content, AD-20), per-Move notes only from `MoveNote`, and works in Physical-cube Mode with *Done*

**Given** the method solution's Effect is pending or times out
**When** shown
**Then** "Building a step-by-step solution…" shows, and on timeout "Couldn't build that solution in time. The shortest one is still here." keeps *Shortest*
