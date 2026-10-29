---
type: architecture-review
lens: rubric (good-spine checklist)
target: ../ARCHITECTURE-SPINE.md (+ .memlog.md)
driving_spec: _bmad-output/planning-artifacts/prds/prd-RubikXplorer-2026-10-29/prd.md (+ addendum.md)
date: 2026-10-29
verdict: REVISE
counts: { critical: 1, high: 6, medium: 9, low: 6 }
---

# Rubric Review — RubikXplorer Architecture Spine

## Verdict: REVISE (not ready to drive epics yet)

The core of the spine is strong. It has a clear hexagonal / functional-core paradigm that Gradle modules enforce, one reducer that owns state, commit-before-animate, categorical Certainty, content as data with a closed predicate registry, one Notation parser, and offline enforced at build time. Most ADs have enforceable Rules that prevent the divergence they name. The mermaid diagrams are valid, no template comments are left, and the text is terse.

Before epics are cut, a few things need fixing:

1. **AD-2's canonical model cannot represent part of the PRD's Move set.** Slice moves, wide moves and Cube Rotations move centers, but AD-2 says "centers fix the frame."
2. **Partial observations are missing from the recognition pipeline.** Quick Check and Case recognition both work from partial observations, and AD-9 only covers full scans.
3. **AD-10 conflicts with FR-12 on Checkpoints.**
4. **The operational envelope is mostly silent.** Signing, build variants, R8, backup, release branching and versioning have no decision. Crash observability appears only as a diagram edge.
5. **Several NFRs have no owner.** NFR-3, NFR-7, NFR-9 and NFR-13 are not bound by any AD. The reference-device set, which the PRD explicitly hands to architecture, is not fixed.

## Checklist summary

| Criterion | Result |
| --- | --- |
| Fixes the real divergence points for epics | Partial: misses the Move/frame model, partial observations, Lesson schema shape, playback model, accessibility and feature-flag mechanism |
| Every AD Rule is enforceable and prevents its divergence | Mostly. AD-1 contradicts AD-8. AD-10 cannot satisfy FR-12 as written. AD-9 does not cover FR-26 or FR-28 |
| Nothing under Deferred lets two units diverge | Pass. Each deferred item is contained behind a port or module |
| Named tech is verified-current (memlog version entries) | Partial: several rows say "latest stable at scaffold" or "confirm". Hilt, kotlinx, JUnit, ktlint, detekt, Kover and Gradle have no memlog version entry |
| Covers the spec's capabilities (FR-1..FR-28, NFRs) | FRs are mapped. NFR-3, NFR-7, NFR-9 and NFR-13 are claimed in `binds` but governed by no AD |
| Every owned dimension decided, deferred or open | Fail on the operational envelope. Only the deployment diagram covers it, and the memlog's "Ops" decision was not carried into an AD |
| Decisions, not rationale; terse | Pass |
| Valid mermaid; no template comments | Pass (small risk noted in L-5) |

## Findings

### Critical

**C-1 — The canonical CubeState cannot represent slice moves, wide moves or Cube Rotations**
- **Location:** AD-2 ("Centers fix the frame"), AD-7, Consistency Conventions "Moves & colors".
- **Issue:** FR-1 and the Glossary require M/E/S, wide turns (u, d, l, r, f, b) and Cube Rotations (x, y, z). All of these move centers. A cubie model whose frame is fixed by the centers cannot apply them directly. Several other parts depend on how this is handled:
  - the renderer's sticker colors;
  - Completion Predicates ("aligned with centers");
  - the Error Category "cube held in an unexpected orientation";
  - `Facelets` conversion for scans taken in an arbitrary orientation;
  - Solver interop, since Kociemba expects fixed URFDLB centers.

  The Notation, renderer, predicate, recognition and solver stories will each pick a different encoding. This is the highest-risk divergence point in the app (NFR-4), and the spine leaves it open.
- **Fix:** extend AD-2 with one rule. `CubeState` = cubies + `orientation: CubeOrientation`, one of 24 frames (or center permutation). Slice and wide Moves are defined as face-turn-plus-rotation equivalents inside `:core:domain`. Predicates evaluate in a frame normalized to the Course's reference orientation (white down or up, as the content declares). `toFacelets()` honors the orientation. The Solver adapter normalizes to fixed centers and maps the result back. Add a property test: "x⁴ = M⁴ = identity, and rotations never change `isSolved`."

### High

**H-1 — Partial observations (Quick Check, Case recognition) are outside the recognition pipeline**
- **Location:** AD-9, AD-5.
- **Issue:**
  - AD-9 has one path only: `ScanResult` = 54 `Facelets` → `CubeValidator` → `CubeState`.
  - FR-26's Quick Check captures only the faces the latest Moves affected, in at most two captures.
  - FR-28 uses a single corner capture showing U plus two side faces.

  Neither produces a validatable 54-sticker state. Without a rule, the two epics will each invent their own partial type and matching logic. One could even fill in the missing stickers from `expected`, which AD-5 forbids. The addendum's "observation port shared by manual input and camera" is also not named.
- **Fix:** add a `PartialObservation` type (known facelet positions + `StickerConfidence`, unknowns explicit). Define a single domain matcher, `match(PartialObservation, candidates): MatchResult{Unique, Multiple, None}`, that both Quick Check (candidates = Move sequences from `expected`) and Case recognition (candidates = derived Case patterns × AUF) must use. Name an `ObservationSource` port fed by Manual Editor, Guided Scan, Quick Scan and Quick Check. State that Certainty `VERIFIED` requires `MatchResult.Unique`.

**H-2 — AD-10's checkpoint-per-transition conflicts with FR-12's "keeps the earlier Checkpoint"**
- **Location:** AD-10 Rule 3, ERD (`LEARNER_PROFILE ||--o| CHECKPOINT`).
- **Issue:** AD-10 writes one checkpoint on every committed transition. FR-12 requires that a Move that breaks the cross leaves the earlier Checkpoint in place, so that it can be restored. With a single checkpoint that is overwritten on every Move, that Checkpoint is lost. The event `CheckpointRestored` exists, but nothing defines what is restored. The same rule also persists serialized `SessionState` with only the content `schemaVersion`. There is no version for the `SessionState` format and no rule for a checkpoint that references a Lesson which no longer exists.
- **Fix:** split the two concepts:
  - `ResumeSnapshot`, written on every transition, satisfies NFR-6.
  - Lesson `Checkpoint`, written only at Objective or Lesson milestones, is the restore target for FR-12 and UJ-1.

  Store `stateFormatVersion` together with `contentSchemaVersion`. When a snapshot cannot be decoded, or references unknown content, fall back to the last valid Lesson start and never crash.

**H-3 — The operational envelope is undecided (signing, variants, R8, release process, versioning)**
- **Location:** missing AD. Only the deployment mermaid in Structural Seed covers it. Memlog "(decision) Ops…" was not promoted.
- **Issue:** this is a Play Store release built partly by small agents, and none of the following is decided:
  - Play App Signing, upload-key custody, and how CI gets the keystore (secrets);
  - debug/release build types and any pilot flavor;
  - R8/minification and resource shrinking, which matter for NFR-11 (30 MB) and need keep rules for kotlinx.serialization and min2phase;
  - uploading the R8 mapping file to Play so vitals stack traces are readable;
  - the `versionCode`/`versionName` scheme;
  - branch model. AD-14 mentions a "release branch" that is never defined;
  - who promotes builds from internal → closed → production, and how;
  - which CI job uploads the build (manual or Gradle Play Publisher).
- **Fix:** add an AD for build and release:
  - AAB only, with Play App Signing and the upload key held in GitHub Actions secrets;
  - `debug` and `release` build types (release with R8 + shrinkResources), with keep rules owned by `build-logic`;
  - mapping file uploaded with each release;
  - `versionCode` derived from CI run number or tag;
  - trunk-based on `main`, with release tags `vX.Y.Z` triggering the release workflow and instrumentation tests;
  - track promotion done manually in Play Console.

  Move anything left open to Deferred with a reason.

**H-4 — Crash and stability observability is only implied (NFR-9 has no AD)**
- **Location:** deployment diagram edge "Android vitals only". NFR-9 is missing from every AD and from the Capability map.
- **Issue:** with no analytics and no crash SDK (AD-13 denylists them), Android vitals is the only field signal. Pre-release stability work is also unspecified:
  - no StrictMode policy in debug;
  - no ANR guardrail beyond AD-11's dispatcher rule;
  - no rule for handling uncaught exceptions in effect handlers;
  - no on-device diagnostic export for pilot sessions.

  Each feature epic will handle failures differently.
- **Fix:** add a short AD:
  - Android vitals is the only production crash signal, plus mapping upload (see H-3);
  - StrictMode in debug only;
  - effect-handler failures become `SessionEvent`s (AD-12) and never crash the loop;
  - no I/O on main, enforced by StrictMode and lint;
  - an optional user-initiated "export diagnostics" from a debug/pilot build only.

  Map NFR-9 to it.

**H-5 — The reference-device set and performance budgets (NFR-2, NFR-3, NFR-11) are not fixed**
- **Location:** Stack, Deferred, Capability map. NFR-3 is not bound by any AD.
- **Issue:** PRD §5 says "the final set [of reference devices] is fixed during architecture." The spine neither fixes it nor defers it. Several things depend on it:
  - NFR-1/2/3 are measured on it;
  - FR-1's 250 ms animation budget;
  - the AD-8 Solver timeout value;
  - the recognition test matrix (OQ-8).

  Each perf story will choose its own device and harness. The Solver timeout in AD-8 has no value, and min2phase table initialization time and storage (generated at first run or bundled) is not decided, which affects both NFR-3 and NFR-11.
- **Fix:** add a row or AD naming the minimum and mid reference devices (or an explicit OQ with a deadline in week 2). Name the harness: a JVM benchmark for the Solver corpus and Macrobenchmark for frame timing. Set the Solver timeout (e.g. 3 s, matching NFR-3). Decide whether min2phase tables are precomputed and bundled or built lazily off-main at first use. Add an APK size check to CI (NFR-11).

**H-6 — Accessibility (NFR-7) and adaptive layouts (NFR-13) are silent across all screens and the renderer**
- **Location:** no AD. Missing from the Capability map. NFR-7 and NFR-13 are listed in `binds` but nothing governs them.
- **Issue:** NFR-7 asks for a non-gesture alternative for every essential action, content labels, scalable text, and P0 color-blind display (letters or patterns on stickers). The color-blind display must look the same on the 3D renderer, the 2D net, the Manual Editor, scan review and recognition diagrams. Otherwise five units will each implement their own sticker glyphs. The cube also needs a TalkBack-readable representation. NFR-13 (phone and tablet, portrait and landscape) needs a single layout convention, such as window size classes, and a rule for keeping state across configuration changes.
- **Fix:** add an AD for accessibility and adaptivity:
  - one `StickerAppearance` (color + optional glyph) in `:core:domain` or a shared UI module, used by every sticker surface;
  - every gesture action has a `MoveButtonBar` equivalent;
  - the cube exposes a semantics description derived from `CubeState`;
  - a Compose lint/a11y test in CI;
  - layouts keyed on `WindowSizeClass`;
  - no state lives in composables beyond UI-ephemeral state.

### Medium

**M-1 — AD-1 contradicts AD-8 on who implements ports**
- **Location:** AD-1 Rule ("Only `:app` implements ports") vs AD-8 ("implemented in `:core:solver`") and the module table.
- **Fix:** reword AD-1 to say adapters live in `:app` or `:core:solver`, and only `:app` wires them.

**M-2 — The Lesson-definition schema shape is not fixed**
- **Location:** AD-6.
- **Issue:** the addendum asks architecture for the Lesson schema, including the four voice fields (goal / preserve / watch / notice). FR-4 lists the required fields: prerequisites, Objective, Demonstration, learner action, Completion Predicate and Recovery Path. Engine stories and content-authoring stories for 7 Stages plus 2-look will drift on field names and structure.
- **Fix:** fix the top-level shape in AD-6, either as a field list or by naming `content/schema/lesson.schema.json` as the single source. State that the Kotlin model is generated from it, or that the schema is checked against the model by a test.

**M-3 — The Error Category taxonomy and the TutorDecision shape are not closed**
- **Location:** AD-5, AD-12 (only `TutorDecision` is named).
- **Issue:** FR-10 lists 7 categories, each of which needs a Recovery Path. Without a closed `ErrorCategory` enum and a fixed `TutorDecision` field set (from addendum §D), the diagnosis, hint and recovery epics will diverge.
- **Fix:** add the closed `ErrorCategory` enum, including `UNKNOWN_DISCREPANCY`, and the `TutorDecision` fields to AD-5 or AD-6. Require exhaustive `when` and one Recovery Path test per category.

**M-4 — The Demonstration/Playback model is shared by three features but not defined**
- **Location:** AD-3 and AD-4.
- **Issue:** FR-6 Demonstrations, FR-16 Solution Playback and FR-18 Library playback all need play, pause, step forward/back, replay and speed. The spine does not say whether playback is reducer state, renderer-local state or a separate component. It also does not say which flows own a `LearningSession`: Lesson, Solve my cube, Library and free cube.
- **Fix:** define one `PlaybackController` (pure, in `:core:application`) over `List<Move>`, used by all three. List the session kinds and state that only Lesson and Physical flows use `LearningSession`.

**M-5 — Auto Backup can move app data off the device (NFR-8)**
- **Location:** AD-13.
- **Issue:** Android's `allowBackup` defaults to on, which means the Room database (progress, calibration) can be copied to Google Drive. NFR-8 says data "never leaves the device," and the data-safety form will declare "no data collected." The spine does not decide this.
- **Fix:** add `android:allowBackup="false"` and empty `dataExtractionRules` to AD-13 (or state the deliberate opposite), and assert it in the manifest CI check.

**M-6 — There is no mechanism for feature flags or beta labels**
- **Location:** missing. It relates to PRD §6.2 (cut order, "beta" labels) and FR-22.
- **Issue:** Quick Scan, Placement, Case recognition and *Test me* may each be cut or shipped as beta. Without one mechanism, some epics will use `BuildConfig` and others content config or `if` checks.
- **Fix:** add a `FeatureFlags` port with a closed enum `{QUICK_SCAN, PLACEMENT, CASE_RECOGNITION, TEST_ME, …}` × `{OFF, BETA, GA}`, set per build type. The UI shows the beta label from the flag.

**M-7 — Stack versions are not pinned or verified**
- **Location:** Stack table, memlog `(version)` entries.
- **Issue:**
  - AGP says "9.x latest stable at scaffold … confirm stable".
  - compileSdk says "36 (or 37)".
  - Hilt, kotlinx.serialization, coroutines, JUnit 5, ktlint, detekt, Kover and Gradle have no version and no memlog entry.
  - Room 3.0.x and Kotest 6.x are marked "confirm" or "unverified".
  - Hilt's compatibility with AGP 9 (built-in Kotlin) is a known risk point and should be verified.
  - JUnit 5 does not apply to Android instrumentation tests, which use JUnit 4.
- **Fix:** pin exact versions that have been checked against release pages, add memlog `(version)` entries for each, choose compileSdk, and note "JUnit 5 (JVM) / JUnit 4 runner (instrumented)".

**M-8 — The renderer cannot be checked against Virtual State as the rule requires**
- **Location:** AD-4. Related: FR-1 ("checked sticker by sticker in tests"), NFR-4.
- **Issue:** the `CubeRenderer` contract has no read-back, so the zero-mismatch suite cannot compare what is rendered with the logical state. FR-1 also allows input during an animation to be "queued or rejected." AD-4 implies queueing but does not set a queue bound or a catch-up policy (speed-up or skip).
- **Fix:** add `renderedFacelets(): Facelets` to the renderer contract (used in tests). State: "input during animation is committed and queued; the queue fast-forwards when its length exceeds N."

**M-9 — The camera-feature release envelope and fixture capture conflict with AD-9**
- **Location:** AD-9 ("Frames are never persisted"), Tests convention (recorded fixtures).
- **Issue:** recognition fixtures (OQ-8 ground truth) have to be captured on real devices. A fixture recorder writes `FaceSample`s or frames to disk, which is a tool AD-9 forbids unless it is limited to one build type. The spine also says nothing about how ground truth is recorded.
- **Fix:** allow a fixture-recorder screen in `debug` only, exporting `FaceSample` JSON and never frames. Release builds strip it. Tie this to the build types in H-3.

### Low

**L-1 — Two names for the validation gate.** AD-2 uses `Facelets.toCubeState(): ValidationResult`. AD-9 and the Capability map use `CubeValidator`. Pick one and use it everywhere.

**L-2 — "Insert-only" conflicts with FR-19 reset.** AD-10 says `Attempt`/`LearningEvent` rows are insert-only, but FR-19 requires a "reset all progress." Add: "deleted only by an explicit Learner reset (whole tables)."

**L-3 — Coverage and linting of vendored min2phase.** The Kover ≥ 80% rule on `:core:*` will include vendored Solver code, which either drags coverage down or forces arbitrary tests. Exclude the vendored package from Kover, ktlint and detekt, and cover the adapter and the solve-corpus test instead.

**L-4 — The memlog and the spine disagree on FR range.** The memlog says "FR-1..29" and the spine says FR-1..FR-28. FR-29 is P1. Record that in the memlog, or list FR-29 under Deferred as P1, so the omission is visibly intentional.

**L-5 — Sequence diagram label.** `S-->>VM: StateFlow<SessionState>` may have `<SessionState>` stripped as HTML by some mermaid renderers. Use `StateFlow of SessionState` or `#lt;` escapes. The diagram also draws the pure reducer sending effects to handlers. Labeling the participant "Session loop" would match AD-3's statement that the reducer is a pure function.

**L-6 — Diagnosis VERIFIED rule could be enforced in types.** AD-5's VERIFIED condition is stated as prose. Make it a smart constructor (`Diagnosis.verified(evidence, MatchResult.Unique)`), so that `VERIFIED` cannot be built without evidence.

## What is good (keep)

- The paradigm and module table are short, and AD-1 makes them enforceable through Gradle.
- AD-3/AD-4 (single reducer, commit-before-animate, View Rotation outside `SessionEvent`) directly target the PRD's highest risk (NFR-4).
- AD-5's categorical Certainty and its rule that "observation is never derived from expected" capture Principle 3 exactly.
- AD-6 combines a closed predicate registry with derived diagrams, and keeps Algorithms single-sourced.
- AD-13 makes the offline requirement a CI failure rather than a guideline.
- Deferred items are each contained behind a port or module, so none of them lets units diverge.
