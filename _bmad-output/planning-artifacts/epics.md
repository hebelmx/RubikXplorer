---
status: final
stepsCompleted: [step-01-validate-prerequisites, step-02-design-epics, step-03-create-stories, step-04-final-validation]
mode: fast-path
created: 2026-10-29
updated: 2026-10-29
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
- Stories never depend on a later story in the same epic. Across epics, an epic may use only the epics numbered before it, except where the roadmap's parallel track is noted (Epic 3 stories 3.1–3.4 have no dependency on Epic 2).

## Requirements Inventory

### Functional Requirements

FR-1: Render a 3×3 cube (6 × 9 stickers) and apply Moves. Gestures cover face turns and Cube Rotations; buttons cover every Move type, including wide and slice turns (wide/slice by button only in MVP [ASSUMPTION]). After every committed Move the rendered cube equals the Virtual State sticker by sticker; input during animation is handled deterministically; a quarter turn animates in ≤ 250 ms on the reference device.
FR-2: Rotate the view freely without changing the Cube State. No sequence of View Rotations changes the Virtual State; Notation follows the cube's orientation, not the camera [ASSUMPTION].
FR-3: Undo repeatedly, redo, and reset. Undo restores the exact previous state; redo restores an undone state and any new Move clears redo; reset asks for confirmation if it would discard unsaved Lesson progress.
FR-4: Stage-gated Course (7 Stages). A Stage unlocks by prerequisites, a skip-ahead check, or Placement. Every Lesson declares prerequisites, Objective, Demonstration, learner action, Completion Predicate and Recovery Path (schema test fails the build otherwise); every Lesson has an automated legal path to its predicate; completion is by predicate, never Move count; "I already know this" runs a check from a fresh Scramble and marks *skipped*, not *mastered*.
FR-5: Four Teaching Modes (*Show me*, *Guide me*, *Test me*, *Let me try*) all evaluate the same Completion Predicate; *Guide me* states the Objective before each Move; *Test me* / *Let me try* reveal the next Move only after a hint request.
FR-6: Explained Demonstrations with Notation step by step, highlighted pieces and direction arrows; play, pause, step forward/back, replay, speed; every step states goal, preserve, watch and (after) notice, each a Lesson-schema field; all text outside UI code.
FR-7: Physical-cube Mode: perform the Move on a real cube, then tap *Done* or enter the Move actually made. The Expected State changes only after confirmation or a validated Scan; it always carries an "Expected" label; View Rotation changes neither Notation nor Expected State.
FR-8: Switch between virtual and Physical-cube Mode mid-Lesson without restarting; switching to physical asks the Learner to confirm their cube matches, scan it, or enter it.
FR-9: Report "my cube doesn't match" / "I'm not sure what I did" and enter a Recovery Path. A physical error is *detected* only with a Learner-entered Move or validated Scan as evidence; otherwise Certainty is *unknown* and the tutor asks, in order: Quick Check → which Move → one face → full re-entry.
FR-10: Diagnose the supported Error Categories from Cube State, Move history and Scan evidence. Each maps to a deterministic rule or explicit *unknown*, with scenario tests; each has a tested Recovery Path; several possible causes → *likely* or *unknown*, never false *verified*; never silently replace the Learner's state.
FR-11: Hint Ladder: each request moves up exactly one rung (clue → highlighted target or Move → full Demonstration); resets when the Objective changes; hint use recorded per attempt; full Solution never shown by default.
FR-12: Recover from mistakes: retry, undo, or Guided recovery to the shortest correction that restores the current Objective's preserved pieces; never restarts the Lesson unless chosen. Disturbed-cross scenario: say so, offer undo/Guided recovery, keep the earlier Checkpoint, don't unlock the next Stage.
FR-13: Rule-based adaptive assistance (first mistake → explain + highlight; repeated → demonstrate; continued difficulty → guide step by step; repeated success → less help). Thresholds are configuration data with tests; tuning from pilot data is P1.
FR-14: Manual Cube Editor for 54 sticker colors with validation that rejects wrong color counts, impossible pieces, a twisted corner, a flipped edge and a parity violation, each with a plain-language reason; 54 colors entered never counts as valid by itself; same validation applies to every Scan Result; fallback whenever the camera is unavailable.
FR-15: Solve any valid Cube State offline. Solution always solves its input (verified over solved + ≥ 10,000 random legal states, incl. deep Scrambles); invalid input rejected before the Solver; timeout/failure → explicit error with recovery prompt, never a made-up Solution; never blocks the UI thread.
FR-16: Solution Playback: step forward/back, pause, replay, with a templated note per Move (face, direction, piece group); notes never claim strategic purpose; in Physical-cube Mode each *Done* advances one step and a Quick Check is available at any step.
FR-17: 2-look LL mini-Course (2-look OLL + 2-look PLL) unlocked after the Course or a skip-ahead check; Lesson predicates are LL-step predicates on Cube State; Case Lessons reference Library entries; *completed* when all predicates met, *mastered* after two hint-free LL solves of fresh Scrambles using only 2-look Algorithms [ASSUMPTION]; offers (never sets) Algorithm Status *Learning*.
FR-18: Algorithm Library of all 78 (57 OLL + 21 PLL) Algorithms grouped by family; watch Demonstration (play, pause, step, speed, View Rotation), see recognition diagram and Notation, set Algorithm Status; build-failing verification test per Algorithm (inverse from solved matches Case; Algorithm solves LL up to AUF); filter by status and family; diagrams derived from Case state; one primary Algorithm per Case [ASSUMPTION].
FR-19: Persist across restarts and forced process death: Stage completed/skipped/mastered, current Checkpoint, attempt outcomes (errors, hints), Algorithm Statuses, Color Calibration, preferences (animation speed, color-blind display, last Teaching Mode). Latest committed Checkpoint restored; physical Checkpoint asks to confirm/scan/re-enter; repeats append, never corrupt; reset progress + Algorithm Statuses with confirmation, separate resets for calibration and preferences.
FR-20: Independent practice on a fresh Scramble with reduced help; a Stage is *mastered* only by its Mastery Condition (initially 2 successful completions, ≥ 1 with reduced assistance, configurable [ASSUMPTION]); transitions follow configuration with tests; neither Lesson completion nor *skipped* marks *mastered*; the Learner can see their hint and error trend.
FR-21: Guided 6-face Scan: live viewfinder, orientation prompts, automatic capture when aligned and stable plus a manual shutter; wrong orientation detected from center/adjacency checks and re-prompted; every sticker has a Sticker Confidence, uncertain ones highlighted for correction before acceptance.
FR-22: Quick Scan: 2–3 corner-angle captures, each showing three faces; two opposite-corner captures yield 54 stickers; uncertain/invalid → ask only for missing faces via Guided 6-face Scan, never a guessed state; "beta" unless it meets its targets.
FR-23: Review a Scan Result on a net (or 3D cube), correct any sticker, accept only once valid; invalid states name likely bad stickers; 54 recognized colors never valid by themselves; low light/glare/blur/incomplete capture → specific guidance and recapture.
FR-24: Color Calibration from the six centers; stickers classified against the cube's own centers (stickerless/pastel work after calibration); persists per device, resettable; standard color scheme only [ASSUMPTION].
FR-25: Scan to solve in *Solve my cube*: scan → correct → validate → solve → playback without leaving the flow; Solver never called on an unvalidated Scan Result; camera permission denied → Manual Cube Editor.
FR-26: Quick Check in Physical-cube Mode (Guided 6-face Scan or only affected faces, ≤ 2 captures [ASSUMPTION]): match → recorded *verified*; mismatch → observed faces become Diagnosis evidence; *verified* only when exactly one candidate Move sequence fits, otherwise *likely* + offer a Guided 6-face Scan; Learner can adopt the observed state for Guided recovery; offered at milestones and after mismatches, never required.
FR-27: Placement: scan a cube, evaluate every Stage Predicate, suggest the first unmet Stage (white cross only [ASSUMPTION]); Learner accepts or picks another; earlier Stages marked *skipped by placement*, never *mastered*, always revisitable.
FR-28: Recognize an OLL/PLL Case from one corner capture (U + two sides): compare against every Case's derived pattern under AUF; ambiguous → list candidates or ask for another angle; F2L not solved → say so and offer Placement; offer (never force) an Algorithm Status change.
FR-29 (P1, out of MVP scope): recognition and execution drills with spaced repetition and a per-Algorithm mastery rule separate from Algorithm Status.

### NonFunctional Requirements

NFR-1: Responsiveness — face-turn input acknowledged within 100 ms.
NFR-2: Rendering — ≥ 30 FPS during face turns and View Rotation on the minimum reference device; 60 FPS target on mid-range.
NFR-3: Solver performance — 95% of a representative random-state corpus solved within 3 s on the reference device; median, p95, memory and failure rate reported.
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
- AR-22 (Deferred / open, carried into stories): reference devices ARCH-OQ-1 and OQ-9 by end of week 2; OQ-3 renderer spike; OQ-7 Quick Scan method; OQ-8 test matrix; OQ-2 2-look set + primary Algorithms; OQ-6 Algorithm data licensing; OQ-1 store name; `RecoveryPlanner` search (bounded IDA*) decided in Epic 4; tutor config values set during curriculum authoring.
- AR-23 (leftover from UX reconcile L14): cut-order item 4 (Library recognition diagrams) has no flag in AD-18's closed enum. [ASSUMPTION: if that cut fires, raise it through `bmad-correct-course` to add a flag; the tile fallback is designed (UX-DR17).]

### UX Design Requirements

UX-DR1: Implement the DESIGN.md color tokens as a Compose Material3 theme: chrome (`surface-base/raised/stage`, `ink-primary/secondary/disabled`, `hairline`, `accent`, `on-accent`) in light and dark; status tokens (`verified`, `likely`, `unknown`, `invalid`) per theme; sticker tokens, `cubie-body`, `sticker-highlight`, `scan-grid`, `sticker-dimmed-overlay` theme-invariant. A contrast test asserts text ≥ 4.5:1 on every surface token, status ≥ 3:1 as icons and ≥ 4.5:1 as chip text, highlight ≈ 7:1 on `cubie-body`. [ASSUMPTION: hex values pending confirmation.]
UX-DR2: Bundle IBM Plex Sans and IBM Plex Mono (no network) and map the roles `display`, `title`, `body`, `label`, `meta`, `notation`, `data` to the M3 type scale; `notation` one step above surrounding text; `data` uses tabular figures; at most one `display` per screen; no all-caps except short status chips. [ASSUMPTION: font pairing pending confirmation.]
UX-DR3: Spacing scale 4/8/12/16/24/32/48dp, `touch-min` 48dp, gutters 16dp compact / 24dp medium+expanded; shapes `xs` 2dp (stickers), `sm` 4dp (keys, inputs), `md` 8dp (cards, buttons, tiles), `lg` 12dp (stage well, sheets), `full` only for certainty chips; no pill buttons, no card shadows.
UX-DR4: Cube stage component: `surface-stage` well; top toolbar Undo · Redo · Reset (icon buttons, TalkBack labels, `ink-disabled` when disabled); frame label VIRTUAL / EXPECTED (dashed `expected-badge`) / VERIFIED (status icon); reset-view affordance top-right and double-tap reset; dimming of off-step pieces with Keep pieces lit; SEEN strip of observed face thumbnails with differing stickers outlined during a mismatch; a Partial observation is never drawn as a whole cube.
UX-DR5: Move key bar: `move-key` buttons labeled in `notation` (`U`, `U'`, `U2`…), press inversion, ⟳ x/y/z group, "more" for wide and slice turns, long-press plain-language name, light haptic tick on commit, labels never change with View Rotation; horizontal scrollable on compact, vertical grid on expanded; in Physical-cube Mode shown only after *I did something else*.
UX-DR6: Move arrow overlay and piece highlight: curved `sticker-highlight` arrow following the turning layer with the Move in `notation` at its tip (static when reduce motion is on); highlight = 3dp `sticker-highlight` outline with 1dp `cubie-body` inner stroke; stickers never recolored.
UX-DR7: Lesson panel in this order: Objective (`title`) → step card → Teaching Mode switcher → Hint (secondary) + **Next** (`button-primary`; disabled = `hairline` fill + `ink-disabled` text). Step card shows Goal, Keep, Watch before the Move and Notice after it, with Moves as `notation-chip`s; the UI label *Keep* renders schema field `preserve`.
UX-DR8: Teaching Mode switcher: segmented control (*Show me*, *Guide me*, *Test me*, *Let me try*), active segment `accent`; switching keeps state and progress; renders `TutorDecision.reveal` only; *Test me* hidden when `TEST_ME` is OFF; last mode remembered.
UX-DR9: Hint button labeled **Hint (n/3)**; each tap moves one rung (clue → highlighted target or Move arrow → full Demonstration); resets on a new Objective.
UX-DR10: Assistance line: one `meta` line under the step card explaining why help changed, with *Back to my usual help*; never switches Teaching Mode without a tap; absent when `ADAPTIVE_ASSISTANCE` is OFF.
UX-DR11: Recovery prompt: inline in the lesson panel, never a dialog; *Undo*, *Guide me back*, *Try again* in that order; pending copy "Finding the shortest way back…", plan copy ("3 moves — your cross stays") with preserved pieces highlighted, no-plan copy "I can't find a short way back. Undo, or start this step again."
UX-DR12: Physical controls: **Done** stays primary (even under a recovery prompt); secondary *I did something else*; overflow with *My cube doesn't match* and *I'm not sure what I did*; *Quick Check* camera icon at Objective boundaries, Stage milestones and after mismatch reports, never required.
UX-DR13: Certainty chip (`rounded.full`, icon + label + status color: ✓ *Verified*, ≈ *Likely*, ? *Unknown*), tappable to explain its evidence, never a percentage; Expected badge (dashed outline, `EXPECTED`) on every rendering of a predicted physical state; no chip while Expected-only.
UX-DR14: Playback transport shared by Demonstrations, Solution Playback and Algorithm detail: |◀ ◀ ▶/❚❚ ▶ ▶|, speed 0.5×/1×/2×, step counter in `data`, current Move in large `notation` with its MoveNote line in `meta`, current Move in the strip marked by an `ink-primary` outline, scrubbing snaps to Move boundaries.
UX-DR15: Scan viewfinder: full-bleed preview, 3×3 grid in `scan-grid` at 70% with 1dp dark halo, auto-capture after ~600 ms aligned and steady, manual shutter, face prompt naming center and up colors with a glyph swatch, mini net progress, inline quality coaching, wrong orientation → re-prompt not error; Quick Scan variant with a Y-shaped corner guide and "1 / 2" counter, unreadable faces hand off to the per-face grid; portrait camera with rotating overlays in landscape.
UX-DR16: Sticker review net (Scan review and Editor): uncertain stickers pre-selected with a dashed `accent` ring and "?" badge; tap a sticker, then a color; invalid pieces outlined in `status-invalid` with labels; non-local errors on their own lines (twisted corner, flipped edge, swapped pieces); **Accept** disabled until every uncertain sticker is resolved and the state is valid.
UX-DR17: Algorithm tile (diagram of U face + side bands derived from state, Case name in `label`, Algorithm in `notation`, status label *Learning*/*Learned*, nothing for *Not started*); fallback tile without a diagram; neutral M3 filter chips (selected = `surface-raised` + ✓, never violet); no stars, scores or badges.
UX-DR18: Stage row (number in `data`, name in `title`, state text + icon: Locked, In progress, Completed, ◇ Skipped, ◇ Skipped by placement, ◆ Mastered in `accent`), reused for the 2-look mini-Course; Mastery progress line ("Mastery: 1 of 2 solves · 1 with reduced help") with a two-segment indicator; Trend row (dot sparkline of hints per attempt in date order, latest emphasized, attempt count in `data`, no totals, no red).
UX-DR19: Beta label (tiny outlined `BETA` tag) shown from the flag, with per-feature tooltip copy; flag OFF hides the entry point and every cross-link, with no dead buttons; the manual route always one tap away.
UX-DR20: Milestone moment only for the first solve and each Stage mastered: ≤ 1.2 s burst of the six sticker colors, then a `display` line, no loops or sound; in Physical-cube Mode only after a Quick Check or explicit *Yes, it's solved*; static frame under reduce motion.
UX-DR21: Color-blind glyphs W Y R O G B on every sticker surface from `StickerAppearance` (glyph in `cubie-body` on light stickers incl. orange, `sticker-white` on dark ones, legible at 16dp); a Settings toggle offered at first launch and first scan; patterns deferred (field empty).
UX-DR22: Information architecture: Welcome (first launch only) and a 4-tab bottom bar (Learn, Solve, Library, You) that becomes a navigation rail at medium/expanded widths; Lesson, Scan and Playback are full-screen task flows with a top app bar; never more than one modal level; the Scan sub-flow returns to its caller. [ASSUMPTION: 4-tab IA pending confirmation.]
UX-DR23: Responsive Lesson layout per window class: compact stacked (stage ≈ 45% height, min 280dp, key bar on stage bottom edge); medium side by side (stage ≈ 55% left, vertical key grid); expanded side by side with the panel capped at 560dp; Library 4 columns at expanded; orientation change never changes session state.
UX-DR24: Voice and copy: all strings as content keys; Goal/Keep/Watch/Notice; fixed Certainty wording each with its next action; the Do/Don't table and UI-label↔Glossary map from EXPERIENCE.md; no exclamation marks except milestones; mistakes in neutral ink, never red.
UX-DR25: State-pattern copy and treatments from EXPERIENCE.md State Patterns, each implemented where its feature lands (resume notes, session paused, content changed, Objective met variants, mismatch ladder, Test me outcomes, error-category copy, stale scan, solver working/timeout/tables building, invalid entry, permission denied, Placement outcomes, Recognize not-at-LL/ambiguous, filter empty, progress reset).
UX-DR26: Gestures: one-finger drag starting on a sticker along a row/column turns that layer; drag starting off the cube or two-finger drag = View Rotation; below threshold nothing happens; two-finger twist = z; x/y via ⟳ keys [ASSUMPTION]; Cube Rotation animates 200 ms and briefly labels the new front color; View Rotation has no label. [ASSUMPTION: gesture mapping pending confirmation.]
UX-DR27: Haptics: light tick on each committed Move, soft confirm on Objective met, nothing on a mistake; all switchable off in Settings.
UX-DR28: Banned patterns enforced in review: shake on error, red flash for a Learner mistake, countdown timers (outside P1 drills), streaks/points/push notifications/badges, auto-advance past a Notice, carousels.
UX-DR29: Accessibility floor: cube semantics summary ("White cross solved; 2 of 4 first-layer corners placed") plus a *Read faces* action; every Certainty change announced; Move keys announce plain names; View Rotation arrow buttons in the stage overflow; dynamic type to 200% with panel reflow and no truncation; focus order frame label → cube description → Move key bar → lesson panel → mode switcher; reduce motion makes Demonstrations step, milestones static, camera transitions snap.
UX-DR30: Themes: light and dark designed equally, follow the system, with a Settings override.
UX-DR31: Back: leaving a Lesson is immediate with no confirmation (everything is committed and checkpointed first).

### FR Coverage Map

FR-1: Epic 1 (buttons, 2D net, all Move types) · Epic 5 (3D, gestures, 250 ms animation)
FR-2: Epic 5 — View Rotation independent of Cube State
FR-3: Epic 1 (free-cube undo/redo/reset) · Epic 2 (in-session undo/redo, reset confirmation) · Epic 8 (physical undo)
FR-4: Epic 2 (Lesson schema, path tests) · Epic 7 (Stage gating, all Stages, skip-ahead check)
FR-5: Epic 2 — four Teaching Modes
FR-6: Epic 2 (explained Demonstrations on the net) · Epic 5 (3D arrows and highlights)
FR-7: Epic 8 — confirm physical Moves, Expected label
FR-8: Epic 8 — switch modes mid-Lesson
FR-9: Epic 8 (honest mismatch, no-camera ladder) · Epic 10 (Quick Check rung)
FR-10: Epic 4 — Diagnosis of Error Categories
FR-11: Epic 4 — Hint Ladder
FR-12: Epic 4 — retry, undo, Guided recovery
FR-13: Epic 4 — rule-based adaptive assistance
FR-14: Epic 1 (`CubeValidator` rules) · Epic 3 (Manual Cube Editor)
FR-15: Epic 3 — offline Solver
FR-16: Epic 3 (Solution Playback) · Epic 8 (physical follow-along) · Epic 10 (Quick Check in playback)
FR-17: Epic 9 — 2-look LL mini-Course
FR-18: Epic 9 — Algorithm Library
FR-19: Epic 6 (snapshots, Checkpoints, attempts, preferences) · Epic 7 (progress reset) · Epic 9 (Algorithm Status) · Epic 10 (calibration reset)
FR-20: Epic 7 — Independent practice, mastery, trend
FR-21: Epic 10 — Guided 6-face Scan
FR-22: Epic 11 — Quick Scan
FR-23: Epic 10 — validate and correct a Scan Result
FR-24: Epic 10 — Color Calibration
FR-25: Epic 10 — scan to solve
FR-26: Epic 10 — Quick Check
FR-27: Epic 11 — Placement
FR-28: Epic 11 — Case recognition
FR-29: not covered — P1, out of MVP scope (deferred in PRD §6.2 and spine Deferred)

NFRs: NFR-1/2 Epics 5, 12 · NFR-3 Epic 3 · NFR-4 Epics 1, 2, 5, 8 · NFR-5/8 Epic 1 (build checks), Epic 12 · NFR-6 Epic 6 · NFR-7 Epics 1, 5, 12 (+ every UI story) · NFR-9/11 Epics 7, 12 · NFR-10 Epic 2 · NFR-12 Epic 1 (gates), all `:core:*` stories · NFR-13 Epic 12 (+ every UI story).

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

### Epic 4: Get help when I go wrong (P0.2, weeks 4–5)
When a Learner makes a mistake, the tutor names what happened with honest Certainty, offers the next hint rung, and leads back by undo or the shortest Guided recovery, with assistance adapting to their history.
**Absorbs:** E05.
**FRs covered:** FR-10, FR-11, FR-12, FR-13. **Also:** AR-9, UX-DR9, UX-DR10, UX-DR11, UX-DR13.

### Epic 5: Turn the cube in 3D (P0.2–P0.3, weeks 5–7)
A Learner can turn and inspect a smooth 3D cube with gestures, rotate the view freely, and see Demonstrations with arrows and highlights, with every action also reachable without gestures or sight.
**Absorbs:** E03 (3D + gestures), part of E09.
**FRs covered:** FR-1 (3D, gestures), FR-2, FR-6 (3D overlays). **Also:** NFR-1, NFR-2, NFR-4, NFR-7, AR-8, AR-17, UX-DR6, UX-DR26, UX-DR27, UX-DR29.

### Epic 6: Pick up where I left off (P0.3, week 6)
A Learner's Lesson survives closing the app or process death, their attempts are recorded, and their preferences stick.
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

### Epic 10: Scan my cube (P0.4, weeks 10–12)
A Learner can scan a cube face by face, correct and calibrate it, solve it from the scan, and verify physical Moves with a Quick Check.
**Absorbs:** E10a (Guided capture + calibration), E10b, E10c (scan to solve, Quick Check).
**FRs covered:** FR-9 (Quick Check rung), FR-16 (Quick Check in playback), FR-19 (calibration reset), FR-21, FR-23, FR-24, FR-25, FR-26. **Also:** recognition NFRs, NFR-5, NFR-8, AR-13, UX-DR15, UX-DR16, UX-DR19.

### Epic 11: Scan shortcuts: Quick Scan, Placement and Case recognition (P0.5, weeks 13–14)
A Learner can scan faster from corners, start the Course from their own cube, and ask which OLL/PLL Case they're looking at. These are first on the cut order and ship behind flags.
**Absorbs:** E10a (Quick Scan), E10c (Placement, Case recognition).
**FRs covered:** FR-22, FR-27, FR-28. **Also:** AR-18, UX-DR19, UX-DR25.

### Epic 12: Release-ready for learners and reviewers (P0.6, weeks 15–16)
The app meets every release gate. It is accessible, fast and stable on the reference devices, the pilot's findings are fixed, and the store listing and public repo are ready for a hiring manager's five-minute look.
**Absorbs:** E09, PRD §7.
**FRs covered:** none new (verifies all). **Also:** NFR-1, NFR-2, NFR-7, NFR-8, NFR-9, NFR-11, NFR-13, AR-3, AR-5, AR-16, UX-DR28, UX-DR29, Flow 6.

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

### Story 1.10: Week-2 decision gate: schedule, reference devices and baselines

As the developer,
I want the open week-2 decisions closed with numbers,
So that the rest of P0 is planned against real devices and a confirmed scope.

**Refs:** NFR-1, NFR-2, NFR-3, NFR-13 · AD-14 · OQ-9, ARCH-OQ-1

**Acceptance Criteria:**

**Given** the spike results from Stories 1.8 and 1.9
**When** the gate is held at the end of week 2
**Then** OQ-9 is decided (confirm ~16 weeks, or move scan uses to P1) and recorded, and if scope moves, `bmad-correct-course` is run on this epic list

**Given** ARCH-OQ-1
**When** the gate closes
**Then** a minimum and a mid-tier reference device are named and recorded in the architecture memlog

**Given** the reference devices
**When** a Macrobenchmark module and a JVM benchmark harness are added
**Then** they produce a baseline for frame timing on the 2D net (NFR-2) and a placeholder for the solve corpus (NFR-3), runnable on demand

**Deferred after this story:** once 1.10 has re-measured the OpenGL ES renderer on the reference devices and the choice stands (p90 ≤ 33.3 ms), delete the Story 1.8 SceneView spike: `app/src/spike3dSceneView/`, `app/src/spike3dNoSceneView/`, the `rubikxplorer.spike3d.sceneview` switch in `app/build.gradle.kts`, and the `sceneview` entry in `gradle/libs.versions.toml`. Tracked as a sprint-status action item.

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

As a Learner,
I want the Solver ready quickly without bloating the app,
So that solving feels instant and the download stays small.

**Refs:** FR-15 · NFR-3, NFR-11 · AD-8, AD-16 · AR-12, AR-22 · UX-DR25

**Acceptance Criteria:**

**Given** the pruning tables
**When** they are generated
**Then** their size and build time are measured on the minimum reference device, and the bundled-vs-first-run choice is decided against NFR-11 and recorded in the architecture memlog

**Given** first-run generation (if chosen)
**When** tables are built
**Then** it happens on `Dispatchers.Default`, results are cached in app-private storage, Lessons never wait on them, and StrictMode reports no main-thread disk access

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
**Then** median, p95, memory and failure rate are reported, and 95% solve within 3 s on the reference-device-equivalent profile [ASSUMPTION: JVM proxy in CI, confirmed on device in Story 12.2]

**Given** a cancelled coroutine
**When** search is in progress
**Then** it stops promptly and returns no partial result

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

### Story 3.7: Solve tab end to end

As a Learner who just wants a stuck cube solved,
I want one place to enter my cube and get it solved,
So that I don't have to take a course first.

**Refs:** FR-14, FR-15, FR-16 · AD-3, AD-10, AD-18 · UX-DR22, UX-DR25 · UX Flow 3

**Acceptance Criteria:**

**Given** the Solve tab in the bottom bar
**When** it opens
**Then** it offers *Enter colors* (and *Scan my cube* only once Epic 10 lands, hidden while absent), and an open Lesson shows "Starting a solve pauses your lesson." before the Lesson session is suspended

**Given** a valid entry
**When** the Solver runs
**Then** a cancelable indeterminate progress bar shows, "Preparing the solver (one time, a few seconds)." shows while tables build, and a timeout shows "Couldn't find a solution in time. Your colors are still here — try again." with no partial result

**Given** the last Playback step
**When** the cube is solved
**Then** a quiet offer "Want to learn how to do this yourself?" links to Learn, and solves are not resumed after the app closes

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
**Then** it is `UNEXPECTED_ORIENTATION` with "Your cube is turned — hold it with white on the bottom, green in front." and *Show me how*, without undo

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
**Then** it goes through the same `MoveCommitted` path as a Move key, the input is acknowledged within 100 ms, and a light haptic tick fires (switchable off)

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
**Then** it offers *I'm new* (Stage 1) and *I can already solve it*; *Start from my cube* stays hidden until Epic 11 enables `PLACEMENT`; color-blind glyphs are offered

**Given** *I already know this* on a Stage, or *I can already solve it* for the whole Course
**When** the Learner solves that Objective from a fresh Scramble with hints off and no time limit
**Then** `PassSkipCheck` marks the Stage(s) ◇ *Skipped* (never mastered) and unlocks the next

**Given** the Learner gives up the check
**When** they exit
**Then** "Let's do this Stage together — it'll be quick." (or "Let's find where to start" from Welcome) shows, with no record against them

### Story 7.8: Ship a pilot build to closed testing

As the developer,
I want a signed build in pilot beginners' hands by week 9,
So that their results come back in time for fixes.

**Refs:** NFR-8, NFR-9, NFR-11 · AD-13, AD-15 · AR-5 · OQ-5 · PRD §7 gate 4

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

## Epic 8: Use my real cube

A Learner can follow Lessons and Solution Playback on a physical cube, with the app honestly tracking the Expected State and handling mismatches without a camera. The camera rungs are added in Epic 10.

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
**Then** a Confirm-your-cube card offers *It matches* / *Enter it* (*Scan it* hidden until Epic 10), and the frame turns to dashed EXPECTED with no Certainty chip

**Given** a step
**When** the Learner taps **Done** (always primary) or *I did something else* then a Move key
**Then** the Expected State advances by the confirmed or entered Move

**Given** the Objective is met on the Expected State
**When** the panel updates
**Then** it reads "If your cube matches the picture, this step is done.", keeps EXPECTED, enables *Next*, and shows no celebration

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
**Then** "Your cube should be solved now. Check it with the camera, or tell me if it is." shows, and the milestone plays only after *Yes, it's solved* (or a Quick Check, once Epic 10 lands)

**Given** an end-to-end instrumented suite
**When** it runs virtual and physical flows with Moves, confirmations, undo, mode switches, pause, kill and resume
**Then** there is zero silent desync between `virtual`, `expected` and the rendered cube

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

## Epic 10: Scan my cube

A Learner can scan a cube face by face, correct and calibrate it, solve it from the scan, and verify physical Moves with a Quick Check. P0.4 exit: the Guided Scan meets its accuracy targets on the test matrix, or `CAMERA_SCAN` ships as BETA.

### Story 10.1: Classify stickers and assemble a Scan Result

As a Learner,
I want stickers read against my own cube's colors with honest uncertainty,
So that the app flags what it isn't sure of instead of guessing.

**Refs:** FR-21, FR-23, FR-24 · recognition NFRs, NFR-12 · AD-2, AD-5, AD-9 · AR-13

**Acceptance Criteria:**

**Given** `FaceSample` fixtures from the Story 1.9 matrix
**When** `StickerClassifier` (calibration as a parameter) and `ScanAssembler` run on the JVM
**Then** they produce `ScanResult.Full(Facelets + StickerConfidence)` or `Partial`, rotating samples into URFDLB order using center and up-neighbor colors

**Given** the fixture suite
**When** accuracy is reported
**Then** per-sticker and per-scan accuracy and the confidently-wrong rate (target ≤ 0.1%) are printed, and only categorical confidence leaves the module

### Story 10.2: Guided 6-face Scan viewfinder

As a Learner,
I want the camera to guide me face by face and capture on its own,
So that scanning is quick even for a beginner.

**Refs:** FR-21, FR-23 · NFR-5, NFR-8 · AD-9, AD-13, AD-18 · AR-13 · UX-DR15, UX-DR19, UX-DR21, UX-DR25 · UX Flows 4, 6

**Acceptance Criteria:**

**Given** the first scan
**When** the camera permission is requested
**Then** an explanation shows first; if denied, an explanation card with *Enter colors instead* (and a Settings deep-link when permanently denied) is shown

**Given** the CameraX adapter in `:app/camera`
**When** the grid is aligned and steady for ~600 ms
**Then** the face is captured automatically (manual shutter also available), the prompt names the center and up colors with a glyph swatch, and the mini net advances

**Given** a wrong orientation, glare, blur or low light
**When** detected
**Then** the Learner is re-prompted with specific coaching ("Too much glare — tilt the cube"), not an error

**Given** frames
**When** processed
**Then** they stay in memory, are never persisted or sent, and release logs contain no samples; a debug-only recorder exports `FaceSample` JSON with ground truth

### Story 10.3: Review and correct a Scan Result

As a Learner,
I want to fix any sticker the camera misread before it's used,
So that a bad read never reaches the Solver or the tutor.

**Refs:** FR-23, FR-14 · AD-3, AD-9 · AR-13 · UX-DR16, UX-DR22, UX-DR25

**Acceptance Criteria:**

**Given** a scan sub-flow returning `ScanOutcome {Accepted, Cancelled, FellBackToEditor}` through a typed navigation result
**When** review opens
**Then** uncertain stickers are pre-selected, corrections use tap-sticker-then-color, and invalid states name the likely bad stickers ("This corner shows two whites.")

**Given** any uncertain sticker unresolved or an invalid state
**When** the Learner looks at Accept
**Then** it is disabled

**Given** the scan flow
**When** it completes
**Then** only the caller dispatches the result, and the flow never touches a session

### Story 10.4: Calibrate colors for my cube

As a Learner with a stickerless or unusual cube,
I want to teach the app my cube's colors,
So that scans read it correctly.

**Refs:** FR-24, FR-19 · AD-9, AD-10 · AR-13, AR-21 · UX-DR25 · UX Flow 9

**Acceptance Criteria:**

**Given** many uncertain stickers in a scan, a first stickerless scan, or Settings
**When** "Colors look off — calibrate?" or Settings → Color Calibration is chosen
**Then** each of the six centers is shown in turn, the Learner confirms each swatch, and *Save* calls `UpdateCalibration`

**Given** `CalibrationRepository` as the only owner
**When** a rescan runs
**Then** the classifier uses the saved calibration, which is never in `SessionState` or a Checkpoint

**Given** *Reset calibration* in Settings
**When** confirmed
**Then** `ResetCalibration` clears only calibration

### Story 10.5: Scan to solve

As a Learner who just wants it solved,
I want to scan instead of typing colors,
So that I go from scrambled to solving in under a minute.

**Refs:** FR-25, FR-15, FR-16 · AD-8, AD-9, AD-18 · UX-DR19, UX-DR25 · UX Flow 3

**Acceptance Criteria:**

**Given** the Solve tab
**When** *Scan my cube* is chosen
**Then** scan → review → validate → solve → playback runs without leaving the flow, and *Enter colors instead* is always visible

**Given** `CAMERA_SCAN` is BETA
**When** the Solve tab renders
**Then** *Enter colors* is the primary action and scan carries the BETA label and tooltip

**Given** camera permission denied from Solve
**When** the Learner taps *Scan my cube*
**Then** the Manual Cube Editor opens directly with a one-line note

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
**When** the observed faces cover every face any Move since the last Full verified observation could touch and exactly one candidate fits
**Then** Certainty is VERIFIED; otherwise LIKELY or UNKNOWN, and a scenario suite yields 0 false *verified*

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
**Then** *Quick Check* is offered (never required; *Done* still works), and it is the first rung of the mismatch ladder

**Given** a matching result
**When** shown
**Then** a ✓ *Verified* chip and a VERIFIED frame appear until the next *Done*; a mismatch shows the SEEN strip with differing stickers outlined, never a whole cube from a Partial

**Given** a Full observation in mismatch
**When** *Use my cube as it is now* is chosen
**Then** `AdoptObserved` replaces the Expected State and starts a Guided recovery; a Partial must first be completed by a Guided Scan or the Editor

**Given** physical Solution Playback or the Stage 6 end
**When** the Learner runs a Quick Check
**Then** it is available at any step, and a verified solved cube triggers the first-solve milestone; *Scan it* now appears on the Confirm-your-cube card

### Story 10.8: Recognition test matrix and GA/BETA decision

As the developer,
I want the scan features measured against their targets before release,
So that each ships with an honest GA or BETA label.

**Refs:** FR-21, FR-26 · recognition NFRs · AD-14, AD-18 · OQ-8 · PRD §7 gate 6

**Acceptance Criteria:**

**Given** the OQ-8 matrix (cubes × daylight, warm indoor, cool LED, low light × reference devices)
**When** Guided 6-face Scan and Quick Check are run with recorded ground truth
**Then** accuracy, corrections, time and confidently-wrong rates are reported against the §4.8 targets

**Given** the results
**When** a target is missed
**Then** `CAMERA_SCAN` is set to BETA for release, recorded in the PRD memlog, and every scan flow is verified to have a working manual fallback

## Epic 11: Scan shortcuts: Quick Scan, Placement and Case recognition

These are first on the PRD cut order (Quick Scan → beta, then Case recognition, then Placement). Each ships behind its AD-18 flag and can be turned OFF without dead UI.

### Story 11.1: Quick Scan from two corners

As a Learner,
I want to scan my cube in two photos,
So that scanning takes seconds.

**Refs:** FR-22, FR-23 · recognition NFRs, NFR-11 · AD-9, AD-18 · OQ-7 · UX-DR15, UX-DR19 · UX Flow 3

**Acceptance Criteria:**

**Given** `QUICK_SCAN` is BETA or GA
**When** the Learner holds the cube by a corner and captures twice ("1 / 2")
**Then** the adapter's corner detection and perspective unwarp (method from OQ-7) emit `FaceSample`s, and two opposite-corner captures yield 54 stickers

**Given** faces that are uncertain or an invalid state
**When** assembly finishes
**Then** the Guided Scan grid asks for only those faces, never a guessed state

**Given** the test matrix
**When** Quick Scan is measured
**Then** it ships GA only at ≥ 95% valid after ≤ 3 corrections and < 15 s, otherwise BETA; its size cost fits NFR-11

### Story 11.2: Start the Course from my cube (Placement)

As a Learner who already knows some steps,
I want the app to look at my cube and suggest where to start,
So that I continue from where I really am.

**Refs:** FR-27, FR-4 · AD-6, AD-10, AD-18 · AR-21 · UX-DR19, UX-DR25 · UX Flow 4

**Acceptance Criteria:**

**Given** `PLACEMENT` is BETA or GA
**When** Welcome → *Start from my cube* (or Recognize's link) scans an accepted Full state
**Then** `PlaceFromScan` evaluates each Stage Predicate and suggests the first unmet Stage, with *Choose another Stage*

**Given** acceptance
**When** recorded
**Then** that Stage unlocks and earlier Stages get `StageSkippedByPlacement` (◇ *Skipped by placement*, never mastered, always revisitable)

**Given** edge outcomes
**When** the cube is unsolved, has a non-white cross, is at the LL, or is solved
**Then** the EXPERIENCE.md Placement-outcome copy and options show

**Given** `PLACEMENT` is OFF
**When** Welcome and Recognize render
**Then** *Start from my cube* is absent everywhere

### Story 11.3: Recognize an OLL/PLL Case

As a Learner practicing the last layer,
I want to photograph my top layer and be told which Case it is,
So that I can look up the right Algorithm.

**Refs:** FR-28, FR-18, FR-27 · recognition NFRs · AD-9, AD-18, AD-19 · UX-DR19, UX-DR25 · UX Flow 5

**Acceptance Criteria:**

**Given** `CASE_RECOGNITION` is BETA or GA
**When** Library → *Recognize case* captures one top-corner view (U + two sides)
**Then** `RecognizeCase` matches the visible stickers through `LastLayerMatcher` under AUF and returns the Case within 1 s

**Given** a unique match
**When** shown
**Then** "OLL 45 · T-shape" opens the Algorithm detail with its Demonstration playing, and *Mark as Learning* is offered, never automatic

**Given** several candidate Cases
**When** shown
**Then** "This could be one of 3 cases" lists candidate tiles with *Scan another angle*, never a silent pick

**Given** the first two layers are unsolved
**When** recognized
**Then** "The first two layers aren't solved yet." shows with *Start from my cube* when `PLACEMENT` is on

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

### Story 12.2: Performance and compatibility on the reference devices

As a Learner on an older phone or a tablet,
I want the app smooth and correctly laid out,
So that it works on the device I have.

**Refs:** NFR-1, NFR-2, NFR-3, NFR-13 · AD-14, AD-17 · UX-DR23

**Acceptance Criteria:**

**Given** Macrobenchmarks on the minimum and mid reference devices
**When** face turns and View Rotation run
**Then** ≥ 30 FPS on minimum and 60 FPS on mid-range, with input acknowledged within 100 ms

**Given** the solve corpus on the minimum device
**When** run
**Then** 95% solve within 3 s, with median, p95, memory and failure rate reported

**Given** phone and tablet in portrait and landscape on API 29 through targetSdk 36
**When** every Key Flow runs
**Then** layouts follow their window class and rotation never changes session state

### Story 12.3: Stability, size and privacy hardening

As a Learner,
I want an app that doesn't crash, stays small and keeps my data on my phone,
So that I can trust it.

**Refs:** NFR-5, NFR-8, NFR-9, NFR-11 · AD-13, AD-15, AD-16 · AR-4, AR-5

**Acceptance Criteria:**

**Given** the release AAB
**When** CI checks run
**Then** size is < 30 MB installed, the merged manifest has no `INTERNET` and only `CAMERA`, and no denylisted SDK is present

**Given** a fresh install in airplane mode
**When** the Course, Library, scan and solve are exercised
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
**Then** gates 1–6 each link to their passing evidence (CI runs, scenario suites, e2e suite, pilot notes, offline/a11y checks, scan GA/BETA decision)

### Story 12.5: Store listing and public portfolio repo

As a hiring manager,
I want a polished listing and a public repo I can judge in five minutes,
So that I can shortlist the developer with confidence.

**Refs:** PRD §7, UJ-6, SM-13 · AD-15 · OQ-1 · UX Flow 6

**Acceptance Criteria:**

**Given** OQ-1
**When** the trademark search is done
**Then** the final app name and icon contain no "Rubik's" mark, and the codename is replaced in the listing

**Given** the Play listing
**When** it is submitted
**Then** it has screenshots, a short promo video recorded from the app, a privacy-policy page, and a completed content-rating questionnaire

**Given** the repo goes public after the app works end to end
**When** a reviewer opens it
**Then** the README shows screenshots, an architecture overview linking `docs/ARCHITECTURE.md`, green CI and coverage badges, and a Conventional Commits history

**Given** all gates pass
**When** the build is promoted
**Then** it moves closed testing → production manually in Play Console
