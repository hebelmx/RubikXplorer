---
title: 'Story 2.3: Run a Lesson through the session reducer'
type: 'feature'
created: '2026-10-30'
status: 'done'
baseline_revision: '4124dcd6ac2b086fb58d3cc36d38d7b678bbe7ca'
review_loop_iteration: 0
followup_review_recommended: false
context:
  - '{project-root}/_bmad-output/implementation-artifacts/epic-2-context.md'
  - '{project-root}/AGENTS.md'
warnings: ['oversized']
deferred:
  - summary: >-
      Presentation order to the renderer depends on callers resuming in revision order after SessionHost.dispatch returns.
    evidence: |-
      maybe-false (if true: medium, unverified). dispatch returns the Transition and the caller submits presentation itself; Net2DRenderer never checks revision. Settle by driving two concurrent callers on different dispatchers and checking submit order, or by publishing presentation from inside the confined block when Story 2.5 adds the first production caller.
    location: >-
      app/src/main/kotlin/dev/rubikxplorer/app/session/SessionHost.kt
    severity: medium (unverified)
  - summary: >-
      ResetConfirmation dispatches ResetConfirmed and drops the returned Transition, so a renderer never receives the reset Snap.
    evidence: |-
      maybe-false (if true: medium, unverified). Today no screen hosts the dialog next to a renderer, so nothing desyncs. Same root cause as the item above (presentation is delivered by callers, not the host). Settle when Story 2.5 places the dialog in the Lesson screen: either the host publishes presentation itself or the dialog hands its Transition to the screen's renderer; a test confirming Reset there must assert renderedFacelets() == entry.
    location: >-
      app/src/main/kotlin/dev/rubikxplorer/app/ui/lesson/ResetConfirmation.kt
    severity: medium (unverified)
---

<intent-contract>

## Intent

**Problem:** A Lesson has no state owner yet: the free cube keeps its own `MoveHistory` in a ViewModel, so nothing guarantees that each Move in a Lesson is recorded exactly once, that stale async results are ignored, or that Reset warns before losing Lesson steps (FR-1, FR-3, FR-4, NFR-4, AD-3, AD-4, AD-11, AD-12).

**Approach:** Add the pure `LearningSession` reducer (`SessionState`, `SessionEvent`, `Effect`, `Presentation`, `Transition`, `reduce()`) plus the normative `TRANSITIONS.md` in `:core:application/session`, a `Clock` port, an `@Singleton SessionHost` in `:app/session` that stamps and serializes events on one confined dispatcher, and a reset-confirmation dialog in `:app/ui/lesson`; prove them with table, staleness, purity, host, dialog and a 10,000-step render-sync test.

## Boundaries & Constraints

**Always:**
- `reduce(state: SessionState, event: SessionEvent): Transition(state, effects, presentation)` is a pure top-level function in `dev.rubikxplorer.application.session`: no I/O, no `Clock`, no `RandomSource`, no `Instant.now()`/`System.*`/`kotlin.random`, bounded work. An idle initial value (e.g. `SessionState.IDLE`, revision 0, no Lesson) exists; every event other than `LessonStarted` on it is a no-op.
- `SessionState` holds at least: `kind` (`SessionKind` enum with all four AD-3 values; Lessons use `LESSON`), the `Lesson` (from `:core:domain/content`), `entry: CubeState`, the in-session history (done/undone Moves) with `virtual: CubeState` = current state, `revision: Long`, the Completion Predicate result (`objectiveMet: Boolean`, `progress: ObjectiveProgress` from `lesson.completion` on `virtual`, recomputed on every `virtual` change), a pending reset confirmation (or none), a deterministic next-effect-id counter, and the last `EffectResultDiscarded` record (or none).
- Every `SessionEvent` carries `at: Instant` (`kotlin.time.Instant`; use `java.time.Instant` only if the former needs an opt-in). Events: `LessonStarted(lesson)`, `MoveCommitted(move)`, `Undo`, `Redo`, `Reset`, and the confirmation results `ResetConfirmed(effectId, revision)` / `ResetDeclined(effectId, revision)`.
- Rules: `LessonStarted` replaces any session, decodes `lesson.entryState` via `FaceletString.decode` + `CubeValidator.validate` (a failure is programmer error: loaded content is validated — `check`/`error` with a message), sets `virtual = entry`, empty history, presentation `Snap(entry)`. `MoveCommitted` applies the Move, clears redo, `Animate(move)`, and cancels a pending confirmation. `Undo`/`Redo` walk history, `Animate(inverse)`/`Animate(move)`; no-op when nothing to undo/redo. `Reset` with **unsaved steps** (≥ 1 committed, not-undone Move since `LessonStarted`) changes nothing but issues `Effect.ConfirmReset(effectId, revision, prompt = Text("msg.session.reset-unsaved"))` and stores it as pending; without unsaved steps it resets immediately. A current `ResetConfirmed` resets to `entry` (history and redo cleared, `Snap(entry)`, pending cleared); a current `ResetDeclined` only clears pending.
- Revisions: `revision` increases by exactly 1 on every transition whose state changes (never otherwise, never decreases, also across `LessonStarted` replacing a session). An effect carries the `revision` of the state it was issued in. A result whose `(effectId, revision)` does not match the current pending effect and current revision changes nothing except recording `EffectResultDiscarded(effectId, issuedRevision, currentRevision)` (revision unchanged, no presentation); if it was a stale `ResetConfirmed` and unsaved steps still exist, the reducer re-offers a fresh `ConfirmReset` at the current revision (AD-3: the Learner started it).
- `Presentation` items (`Animate(move, revision, target: CubeState)`, `Snap(revision, target)`) carry the resulting revision and state; `:app` maps them to its existing `render/PresentationItem` (`target.toFacelets()`).
- `core/application/src/main/kotlin/dev/rubikxplorer/application/session/TRANSITIONS.md` is normative: one Markdown table row per `SessionEvent` type with columns `Event | Mutates | Undoable | Clears redo` (virtual mode only; `expected`/`observed`/physical rows arrive with Epic 8). A test parses it, fails if a `SessionEvent` type lacks a row or a row names no type, and asserts every row's three cells against the reducer.
- `Clock` port (`fun interface Clock { fun now(): Instant }`) in `:core:application/port`; `:app` adapter (system clock) bound in a Hilt `@Singleton` module.
- `SessionHost` (`@Singleton`, `@Inject constructor(clock: Clock)`, optional internal dispatcher parameter for tests) in `app/src/main/kotlin/dev/rubikxplorer/app/session/`: holds exactly one `SessionState` (at most one session), exposes it as a `StateFlow`, and has a suspend `dispatch` that stamps `at` from `clock.now()` at dispatch and runs `reduce` on one confined dispatcher (`Dispatchers.Default.limitedParallelism(1)`), returning the `Transition`. Inject it in `MainActivity` (like `featureFlags`) so Hilt wires it from the first build.
- `ResetConfirmation` composable in `app/src/main/kotlin/dev/rubikxplorer/app/ui/lesson/`: an `AlertDialog` shown while the session has a pending confirmation, message = the prompt resolved from the loaded content strings (pass the string in; no literal), confirm button = existing `R.string.toolbar_reset`, dismiss = a new `R.string` cancel resource; confirm/dismiss dispatch `ResetConfirmed`/`ResetDeclined` with the pending `effectId`/`revision`. Calm copy, neutral ink.
- `msg.session.reset-unsaved` = `Reset the cube? This lesson's unsaved steps will be lost.` added to `core/domain/src/main/resources/content/strings/en.json`.
- Tests (JUnit Jupiter / Robolectric as already used; seeds pinned): reducer unit tests incl. I/O matrix; staleness test; source-scan purity test; `TRANSITIONS.md` table test; `SessionHost` test (stamps `at`, one session, concurrent dispatch serialized with strictly increasing revisions); Robolectric dialog test (asks only with unsaved steps, confirm resets, cancel keeps); Robolectric sync suite of 10,000 random steps (Moves of every type incl. rotations/wide/slice, Undo, Redo, Reset + confirm/decline) through `SessionHost` → `Net2DRenderer`, asserting `renderedFacelets() == state.virtual.toFacelets()` at every drained step.
- `:core:*` stay pure Kotlin, Kover ≥ 80%, `./gradlew spotlessCheck build` green; no new dependencies (coroutines already reach `:app` via Compose).

**Never:**
- No Lesson screen, teaching modes/`teachingMode`, `TutorDecision`, Demonstration/`PlaybackController`, physical mode, persistence/snapshots/Checkpoints, Attempt effects, or ViewModel (Stories 2.4–2.6, Epics 4/6/8).
- No change to the free cube's behavior (`FreeCubeViewModel` keeps its own history, Reset still asks nothing); no edits to `CubeRenderer`/`Net2DRenderer` beyond what the tests need (preferably none).
- No state held or mutated in a ViewModel or composable; no `Clock`/`RandomSource` in `reduce`; no new modules or Gradle deps; no edits to `_bmad/`, `.claude/skills/`, planning artifacts.

## I/O & Edge-Case Matrix

| Scenario | Input / State | Expected Output / Behavior | Error Handling |
|----------|--------------|---------------------------|----------------|
| Start | `IDLE` + `LessonStarted(lesson entry SOLVED+F)` | `virtual` = entry, revision 1, `Snap`, progress from `completion` | No error expected |
| Commit | + `MoveCommitted(F')` | revision 2, `Animate(F')`, `objectiveMet` (white-cross lesson) | No error expected |
| Undo / Redo | + `Undo`, + `Redo` | `Animate(F)` back to entry, then `Animate(F')`; nothing to undo → no-op, revision unchanged | No error expected |
| New Move clears redo | Undo then `MoveCommitted(R)` | redo empty | No error expected |
| Reset, nothing unsaved | right after `LessonStarted` (or all Moves undone) | resets at once, `Snap(entry)`, no effect | No error expected |
| Reset, unsaved steps | after 1 committed Move | state unchanged except pending; effect `ConfirmReset(id, N, Text(msg.session.reset-unsaved))` | No error expected |
| Confirm current | `ResetConfirmed(id, N)` | `virtual` = entry, history + redo cleared, `Snap` | No error expected |
| Stale result | `ConfirmReset` at N, then `MoveCommitted` (N+1), then `ResetConfirmed(id, N)` | not reset; `EffectResultDiscarded(id, N, N+1)` recorded; fresh `ConfirmReset` at N+1 re-offered | value, no throw |
| Before any Lesson | `IDLE` + `Undo`/`MoveCommitted`/`Reset` | no-op, revision 0 | No error expected |

</intent-contract>

## Code Map

- `core/application/build.gradle.kts` -- depends only on `:core:domain`; jvm-core convention (JUnit Jupiter, Kotest property, Kover 80%, boundary + Learner-text gates). No changes expected.
- `core/application/src/main/kotlin/dev/rubikxplorer/application/port/{FeatureFlags,RandomSource}.kt` -- port style to mirror for `Clock.kt`.
- `core/application/src/test/kotlin/dev/rubikxplorer/application/usecase/SeededRandomSource.kt` -- seeded test fake pattern.
- `core/domain/src/main/kotlin/dev/rubikxplorer/domain/cube/MoveHistory.kt` -- immutable done/undone history starting at `SOLVED` (private ctor; `reset()` → solved). Reuse for the session by adding a start-state constructor whose `reset()` returns to that start; the no-arg form must keep its behavior (free cube + `MoveHistoryTest`). `undoable`/`redoable` give the Move for `Animate`.
- `core/domain/src/main/kotlin/dev/rubikxplorer/domain/cube/{CubeState,Move,Notation}.kt` -- `CubeState.apply`, `toFacelets()`, `Move.inverse()`.
- `core/domain/src/main/kotlin/dev/rubikxplorer/domain/content/{Model,FaceletString}.kt` -- `Lesson` (`entryState` 54-letter string, `completion: Predicate`), `FaceletString.decode` → `Decoded(facelets)`; `CubeValidator.validate(facelets)` → valid `CubeState` (see `ContentValidator.entryStateProblem`, lines 78–90, for the exact result types).
- `core/domain/src/main/kotlin/dev/rubikxplorer/domain/predicate/{Predicate,ObjectiveProgress}.kt` -- `isSatisfied`, `progress`.
- `core/domain/src/main/kotlin/dev/rubikxplorer/domain/text/Text.kt` -- `Text(key, args)`.
- `core/domain/src/main/resources/content/strings/en.json` -- add `msg.session.reset-unsaved`. `ShippedContentTest` scans every `Text(` call in `:core:*`/`:app` main sources and fails if its key is missing from en.json.
- `core/domain/src/test/resources/content-fixtures/valid/stages/stage-01-basics.json` -- reference for a valid Lesson (`white-cross-solved`, entry SOLVED+F, demonstration `F'`). Domain test resources are not on the `:core:application`/`:app` test classpath, so tests build an equivalent `Lesson` value in a test helper with `entryState = FaceletString.encode(CubeState.SOLVED.apply(F).toFacelets())`.
- `app/src/main/kotlin/dev/rubikxplorer/app/render/{CubeRenderer,PresentationItem,Net2DRenderer}.kt` -- port + 2D adapter; `submit(items)` queues, `Content()` drains on the Compose frame clock, `renderedFacelets()` is the test hook. Read-only.
- `app/src/main/kotlin/dev/rubikxplorer/app/ui/freecube/FreeCubeViewModel.kt` -- reference for commit-then-submit mapping; do not change.
- `app/src/main/kotlin/dev/rubikxplorer/app/{MainActivity,di/FlagsModule}.kt` -- Hilt entry point and `@Binds @Singleton` module pattern for the `Clock` binding.
- `app/src/main/res/values/strings.xml` -- `toolbar_reset` exists; add a cancel string (chrome).
- `app/src/test/kotlin/dev/rubikxplorer/app/ui/freecube/FreeCubeScreenTest.kt:52-90,136-150` -- Robolectric + `createComposeRule` pattern, `waitForIdle`, `mainClock.autoAdvance` bursts; template for the sync and dialog tests.
- `build-logic/.../policy/LearnerTextPolicy.kt` -- multi-word literals in main sources fail the build; keep prose in en.json/strings.xml.

## Tasks & Acceptance

**Execution:**
- `core/domain/src/main/kotlin/dev/rubikxplorer/domain/cube/MoveHistory.kt` (+ `MoveHistoryTest`) -- add start-state constructor; reset returns to start -- session history from a Lesson entry.
- `core/application/src/main/kotlin/dev/rubikxplorer/application/port/Clock.kt` -- `Clock` port -- AD-11.
- `core/application/src/main/kotlin/dev/rubikxplorer/application/session/{SessionKind,SessionState,SessionEvent,Effect,Presentation,Transition,EffectResultDiscarded,Reduce}.kt` -- reducer per Always rules -- AC 1–2.
- `core/application/src/main/kotlin/dev/rubikxplorer/application/session/TRANSITIONS.md` -- normative table -- AC 4.
- `core/domain/src/main/resources/content/strings/en.json` -- reset prompt -- AC 6.
- `core/application/src/test/kotlin/dev/rubikxplorer/application/session/{ReduceTest,StaleEffectTest,ReducerPurityTest,TransitionsTableTest}.kt` -- I/O matrix rows, staleness, source scan of `session/*.kt` for `Clock`/`RandomSource`/`now()`/`System.`/`kotlin.random`, table parse + per-row assertions -- AC 1–4.
- `app/src/main/kotlin/dev/rubikxplorer/app/{clock/SystemClock.kt,di/ClockModule.kt,session/SessionHost.kt,session/PresentationMapping.kt}` + `MainActivity.kt` injection -- host, adapter, mapping -- AC 1.
- `app/src/main/kotlin/dev/rubikxplorer/app/ui/lesson/ResetConfirmation.kt` + `strings.xml` -- dialog -- AC 6.
- `app/src/test/kotlin/dev/rubikxplorer/app/session/{SessionHostTest,SessionRenderSyncTest}.kt`, `app/src/test/kotlin/dev/rubikxplorer/app/ui/lesson/ResetConfirmationTest.kt` -- host, 10,000-step sync, dialog -- AC 1, 5, 6.

**Acceptance Criteria:**
- Given `@Singleton SessionHost` holding at most one `LearningSession` (`SessionKind.LESSON`), when `LessonStarted`, `MoveCommitted`, `Undo`, `Redo`, `Reset` are dispatched, then the pure `reduce()` runs on one confined dispatcher and returns `Transition(state, effects, presentation)`, `revision` increases monotonically, and every event carries the `at: Instant` stamped at dispatch.
- Given an async Effect issued at revision N, when its result arrives after revision N+1, then the reducer records `EffectResultDiscarded` and a test proves it.
- Given the reducer purity test, when it scans the `session` sources, then `reduce()` never reads `Clock` or `RandomSource`.
- Given `session/TRANSITIONS.md`, when the table test runs, then every virtual-mode event row (Mutates, Undoable, Clears redo) is asserted and every `SessionEvent` type has a row.
- Given a seeded randomized suite of 10,000 Move/undo/redo/reset steps, when it runs against `SessionHost`/`reduce()` and `Net2DRenderer`, then `renderedFacelets()` equals `virtual.toFacelets()` at every drained step (NFR-4).
- Given a Lesson with unsaved steps, when the Learner taps Reset, then a dialog asks "Reset the cube? This lesson's unsaved steps will be lost." before resetting; confirming resets to the entry state, cancelling keeps the cube; with no unsaved steps Reset happens without asking (FR-3).

## Spec Change Log

## Review Triage Log

### 2026-10-30 — Review pass
- verdicts: 27 findings — high 0, medium 4, low 15, false 6, maybe-false 2
- findings:
  - `[false]` `[reject]` (intent) Reset and render sync are met only in test harnesses; no production screen dispatches to `SessionHost` — no Lesson exists in the shipped bundle (`stages: []`) for a Learner to be in; the Lesson screen is Story 2.5 per the epic's story list.
  - `[false]` `[reject]` (intent) staleness proven with one effect type (`ConfirmReset`) — the AC asks for "an async Effect"; one real effect with a proving test satisfies it.
  - `[medium]` `[patch]` (verification-gap) dialog dismiss path (back / outside tap) untested — added `dismissing with back keeps the cube and its steps` to `ResetConfirmationTest`.
  - `[medium]` `[patch]` (verification-gap, other) decline then a late confirm re-opens the prompt — grouped with edge #1 and blind #1; `confirmed()` now re-offers only when `event.revision < state.revision`; new `StaleEffectTest` case; TRANSITIONS.md prose updated.
  - `[medium]` `[patch]` (edge) confirm with no pending reset re-opens the dialog — same group and fix as above.
  - `[false]` `[reject]` (edge) double Reset replaces the first effect — the dialog reads `state.pendingReset`, so only the latest effect is ever shown and answered; the first is never visible.
  - `[low]` `[reject]` (edge) Reset with nothing to change emits a Snap at the unchanged revision — the I/O matrix specifies the Snap; `Net2DRenderer` ignores revision, so drawing is unaffected; fix would edit the spec.
  - `[false]` `[reject]` (edge) `reduce` throwing escapes `SessionHost` — only on an invalid entry state, which validated content cannot produce; loud failure on programmer error is the specified behavior (AD-12).
  - `[low]` `[reject]` (edge) dialog scope cancelled before a tapped answer dispatches — needs a non-modal input to clear the prompt in the same frame; the Move already cancelled the prompt, so nothing is lost; fix adds a host-owned scope API.
  - `[low]` `[reject]` (edge) purity scan mis-strips raw strings — no session source uses raw strings; adding a guard for a hypothetical is not worth it.
  - `[low]` `[reject]` (edge) sync test keeps answering non-pending effects — intended: it mixes stale answers deliberately; assertion (render == virtual) unaffected.
  - `[medium]` `[patch]` (blind) stale confirm re-prompts in another Lesson / after decline — same group as verification-gap other; decline case fixed; cross-Lesson residual noted under Residual risks.
  - `[maybe-false]` `[defer]` (blind) presentation order depends on callers resuming in order — no production caller yet; settle when 2.5 adds one (deferred list).
  - `[low]` `[reject]` (blind) `Transition.effects` unused in production — no effect runner exists until 2.5; the pending copy in state is by design (Design Notes).
  - `[low]` `[reject]` (blind) dialog scope can lose a tap — same as edge scope row.
  - `[low]` `[reject]` (blind) Snap at unchanged revision — same as edge Snap row.
  - `[low]` `[patch]` (blind) `SessionState` KDoc omits history from what advances revision — reworded.
  - `[low]` `[reject]` (blind) TRANSITIONS.md has no history column — Clears redo already covers it; fix adds a column and test logic.
  - `[false]` `[reject]` (blind) unused `sessionHost` injection in `MainActivity` — required by the spec so Hilt wires the binding from the first build, same pattern as `featureFlags`.
  - `[low]` `[reject]` (blind) purity scan misses other nondeterminism sources / relative paths — the AC names `Clock` and `RandomSource`; Gradle runs tests from the module dir (as `ShippedContentTest` already relies on).
  - `[low]` `[patch]` (blind) duplicated lesson helpers; `TickingClock.reads` misleading — removed unused `reads`; duplication across module test sets rejected (would need test-fixtures plugin).
  - `[false]` `[reject]` (blind) no core test checks `msg.session.reset-unsaved` exists — `ShippedContentTest` scans every `Text(` call in `:core:*` main sources against en.json.
  - `[low]` `[reject]` (blind) other dialog test gaps (double tap, re-offer, close on Move) — reducer tests cover re-offer and cancellation; the dismiss gap is patched above.
  - `[low]` `[reject]` (blind) dialog strings mix / no title — spec chose content prompt + chrome buttons; a title is optional for a one-line confirmation.
  - `[low]` `[reject]` (blind) `MoveHistory.done/undone` now public — read-only immutable lists; no invariant can be broken through them.
  - `[low]` `[patch]` (blind) 30-minute sync test timeout; per-burst checking — timeout lowered to 300 000 ms; burst checking is the spec's drained-step design.
  - `[maybe-false]` `[reject]` (blind) unused `SessionKind` values — AD-3 and the spec require all four kinds; no exhaustive `when` over it exists yet, so no harm shown.

### 2026-10-30 — Review pass (follow-up)
- verdicts: 38 findings — high 0, medium 4, low 19, false 12, maybe-false 3
- findings:
  - `[medium]` `[patch]` (blind) late confirm from an earlier Lesson re-offers a Reset prompt in the new Lesson — carried: same claim as the logged stale-confirm/another-Lesson row; code still re-offers when `event.revision < state.revision`; the intent's literal rule re-offers any stale confirm while unsaved steps exist.
  - `[false]` `[reject]` (blind) a second Reset while pending replaces the first effect — carried: the dialog reads `state.pendingReset`, so only the latest effect is shown and answered.
  - `[maybe-false]` `[defer]` (blind) renderer can receive presentation out of order with concurrent callers — carried: already in the deferred list.
  - `[low]` `[reject]` (blind) `Transition.effects` has no consumer — carried: no effect runner until 2.5; pending copy in state is by design.
  - `[low]` `[reject]` (blind) no-op Reset emits a Snap at an unchanged revision — carried: the I/O matrix specifies the Snap; `Net2DRenderer` ignores revision.
  - `[false]` `[reject]` (blind) Reset after undoing every Move discards redo without asking — the intent defines unsaved steps as committed, not-undone Moves and the matrix row "all Moves undone" resets at once.
  - `[low]` `[reject]` (blind) `MoveHistory.done/undone` now public — carried: read-only immutable lists; a cast to a mutable list is deliberate misuse.
  - `[false]` `[reject]` (blind) `SessionHost`/`ResetConfirmation` unused; `MainActivity` field unused — carried: no Lesson screen until 2.5; the injection is spec-required.
  - `[low]` `[reject]` (blind) purity scan non-recursive, misses other nondeterminism APIs, mis-strips raw strings — carried: no session subpackages or raw strings exist; the AC names `Clock` and `RandomSource`.
  - `[low]` `[reject]` (blind) `app.clock.SystemClock` shares its name with `android.os.SystemClock` — the name is the one the spec's task list fixes; only an IDE auto-import inconvenience, and the fix is a rename away from spec.
  - `[low]` `[patch]` (blind) lesson test fixtures duplicated across modules — carried: duplication rejected last pass (needs a test-fixtures plugin); not re-patched.
  - `[low]` `[reject]` (blind) dialog mixes string systems, no title, destructive confirm in neutral ink — carried: spec chose content prompt + chrome buttons and "neutral ink".
  - `[false]` `[reject]` (blind) `objectiveMet`/`progress` after a confirmed Reset not asserted — the outcome (stale predicate after Reset) does not occur: `reset()` goes through `withHistory`, the same path the Undo/Redo assertions cover.
  - `[false]` `[reject]` (blind) a `reduce` throw inside `dispatch` would crash the app — carried: only on an invalid entry state, programmer error by spec (AD-12).
  - `[low]` `[reject]` (blind) double confirm in the dialog untested — carried: the second confirm is a non-current result and is discarded (reducer tests).
  - `[low]` `[patch]` (verification-gap) nothing asserts `Presentation` → `PresentationItem` mapping — added `PresentationMappingTest` (Animate, Snap, list order); 3 tests green.
  - `[medium]` `[patch]` (verification-gap, other) stale/unknown-id confirm at an older revision re-offers in a new Lesson — carried: same as the first row.
  - `[maybe-false]` `[defer]` (edge) dialog drops the confirm's Transition, so a renderer never gets the reset Snap — grouped with the edge claim row below; no screen hosts the dialog with a renderer yet; settle in 2.5 (new deferred item).
  - `[low]` `[reject]` (edge) caller cancelled after `reduce` published state loses the Transition — a cancelled caller is leaving its screen (and renderer); fix (`NonCancellable` or host-published presentation) adds machinery for a rare case.
  - `[low]` `[reject]` (edge) dialog leaving composition cancels its in-flight dispatch — carried: same as the logged dialog-scope row.
  - `[medium]` `[patch]` (edge) stale confirm from the previous Lesson re-offers — carried: same as the first row.
  - `[medium]` `[patch]` (edge) unknown effect id with a lower revision re-offers — carried: same as the first row.
  - `[false]` `[reject]` (edge) double Reset issues a second ConfirmReset — carried: only the latest pending effect is shown.
  - `[low]` `[reject]` (edge) no-op Reset Snap breaks "revision increases with every item" — carried: same as the Snap row.
  - `[low]` `[reject]` (edge) wall clock moving backwards makes `at` stamps go backwards — nothing orders by `at` (revision orders); clamping adds state for no named consumer.
  - `[low]` `[patch]` (edge, claim) sync assertion runs once per burst, not per step — carried: burst checking is the spec's drained-step design; not re-patched.
  - `[low]` `[reject]` (edge, claim) the dispatch builder may ignore the stamped `at` — callers build events from the passed stamp (`SessionEvent::Undo`, `{ MoveCommitted(m, it) }`); closing it needs an API change for a misuse no caller makes.
  - `[maybe-false]` `[defer]` (edge, claim) confirming Reset never submits the Snap to a renderer — same group and deferred item as the dialog row above.
  - `[false]` `[reject]` (intent) revision advances only on content changes, not every state change — the I/O matrix (Reset-asks keeps N; stale row N → N+1 on the Move) only fits this reading.
  - `[false]` `[reject]` (intent) stale re-offer narrowed to overtaken confirmations — the clause ties re-offer to "the Learner started it"; a declined or unknown result was not, and the matrix's stale row is honored.
  - `[low]` `[reject]` (intent) TRANSITIONS.md cells checked over chosen scenarios, not every state — exhaustive state coverage is infeasible; each row is asserted against the reducer as asked.
  - `[false]` `[reject]` (intent) `:core:domain` `MoveHistory` changed — the spec's Code Map directs this change; free-cube behavior unchanged (`MoveHistoryTest`).
  - `[false]` `[reject]` (intent) dialog not hosted in production — carried: no Lesson screen until 2.5.
  - `[false]` `[reject]` (intent) render sync pipeline assembled in the test — carried: same as the logged harness row.
  - `[low]` `[reject]` (intent) host hands the stamp to a builder rather than stamping the event — same group as the builder row above.
  - `[low]` `[reject]` (intent) purity scan omits I/O and bounded work — carried: AC names `Clock`/`RandomSource`.
  - `[low]` `[reject]` (intent) no-op Reset Snap shares a revision — carried: same as the Snap row.
  - `[false]` `[reject]` (intent) Kover and `spotlessCheck build` not visible in the diff — verified: `./gradlew spotlessCheck build` exit 0.

## Design Notes

- **The reset confirmation is the async Effect.** It waits on the Learner, so it carries `effectId` + `revision` like any async effect. If a Move lands while the dialog is up, the late `ResetConfirmed` is stale: it is discarded and re-offered, so Reset never wipes a Move the Learner did not see in the prompt. This gives AC 2 a real effect instead of a test-only one.
- **Pending confirmation lives in `SessionState`** so the dialog survives configuration changes; composables only render it and dispatch the result.
- **No Lesson screen yet:** the dialog is a standalone composable driven by `SessionHost` state; Story 2.5 places it (and a Reset button) in the Lesson screen. Its test hosts a minimal Reset button + dialog over a real `SessionHost`.
- **Sync suite speed:** submit 1–5 events per burst with `mainClock.autoAdvance` on, then `waitForIdle()` and compare; the queue fast-forwards above 3, so 10,000 steps stay within a few minutes.

## Verification

**Commands:**
- `./gradlew :core:application:test :core:domain:test` -- expected: green, including the new session tests and `ShippedContentTest`.
- `./gradlew :app:testDebugUnitTest --tests '*session*' --tests '*lesson*'` -- expected: green, 10,000-step suite included.
- `./gradlew spotlessCheck build` -- expected: exit 0 (Kover ≥ 80% on `:core:*`, Learner-text and boundary gates).

## Auto Run Result

Status: done

**Summary:** Added the pure `LearningSession` reducer in `:core:application/session` (`SessionState`, `SessionEvent` with `at: Instant`, `Effect.ConfirmReset`, `Presentation`, `Transition`, `reduce()`, normative `TRANSITIONS.md`), a `Clock` port, a `@Singleton SessionHost` in `:app` that stamps events and reduces on one confined dispatcher, and a `ResetConfirmation` dialog that asks "Reset the cube? This lesson's unsaved steps will be lost." only when steps would be lost. Stale effect results are recorded as `EffectResultDiscarded`; a confirmation overtaken by a newer revision is re-offered.

**Files changed:**
- `core/domain/.../cube/MoveHistory.kt` (+ test) — start-state constructor; reset returns to start.
- `core/application/.../port/Clock.kt` — clock port.
- `core/application/.../session/{SessionKind,SessionState,SessionEvent,Effect,Presentation,Transition,Reduce}.kt`, `TRANSITIONS.md` — reducer and table.
- `core/application/src/test/.../session/{Lessons,ReduceTest,StaleEffectTest,ReducerPurityTest,TransitionsTableTest}.kt` — reducer tests.
- `core/domain/src/main/resources/content/strings/en.json` — reset prompt.
- `app/.../{clock/SystemClock,di/ClockModule,session/SessionHost,session/PresentationMapping,ui/lesson/ResetConfirmation}.kt`, `MainActivity.kt`, `strings.xml` — host, adapter, mapping, dialog.
- `app/src/test/.../session/{SessionHostTest,SessionRenderSyncTest,TestLessons}.kt`, `ui/lesson/ResetConfirmationTest.kt` — host, 10,000-step sync, dialog tests.

**Review findings:** 27 (high 0, medium 2 entries patched [re-offer narrowing group; dismiss-path test], low 3 patched [KDoc, unused helper, timeout]). Deferred: 1 (presentation ordering with concurrent callers, unverified medium). Rejected with reasons in the Review Triage Log (no production screen yet, single effect type, double Reset, Snap at unchanged revision, programmer-error throw, dialog scope cancel, purity-scan edge cases, unused effects list, history column, MainActivity injection, helper duplication, key check, dialog title/strings, public history lists, SessionKind placeholders).

**Follow-up review recommended:** true — two medium entries patched (first pass). Unverified risk: the narrowed re-offer rule (`event.revision < state.revision`) still re-offers a late confirm from a previous Lesson once the new Lesson has unsaved steps.

**Verification:** `./gradlew spotlessCheck build` exit 0 (Kover, Learner-text, boundary gates); `:core:application:test` — ReduceTest 14, StaleEffectTest 6, TransitionsTableTest 2, ReducerPurityTest 2 green; `:app:testDebugUnitTest` — SessionHostTest 4, ResetConfirmationTest 4, SessionRenderSyncTest 1 (10,000 steps, ~26 s) green; every I/O matrix row covered by a passing test.

**Residual risks:** cross-Lesson stale confirm re-offer (above); presentation order relies on callers (deferred); nothing in the shipped app dispatches to `SessionHost` until Story 2.5.

### Follow-up review pass (2026-10-30)

**Summary:** Follow-up review of the story 2.3 diff (four layers). One new test added; no production code changed.

**Files changed:** `app/src/test/kotlin/dev/rubikxplorer/app/session/PresentationMappingTest.kt` — asserts `Presentation` → `PresentationItem` mapping (kind, Move, revision, target, order).

**Review findings:** 38 (high 0, medium 4, low 19, false 12, maybe-false 3). Patches applied this pass: 1 (low: mapping test). Patched counts by verdict: high 0, medium 0, low 1. Deferred: 1 new (dialog drops the reset Snap presentation; unverified medium, settle in 2.5). Rejected with reasons in the Review Triage Log: redo loss on Reset (spec-defined), `SystemClock` name, completion after Reset (covered path), cancelled-caller loss, wall clock going backwards, builder stamp, revision semantics, re-offer narrowing, scenario-based table test, `MoveHistory` change, Kover/build visibility; the rest carried from the first pass.

**Follow-up review recommended:** false — follow-up pass patched no high finding.

**Verification:** `./gradlew :core:application:test :core:domain:test :app:testDebugUnitTest --tests '*session*' --tests '*lesson*'` green (PresentationMappingTest 3/3); `./gradlew spotlessCheck build` exit 0.

**Residual risks:** a late confirm from a previous Lesson can still re-open the Reset prompt in a new Lesson with steps (carried, matches the intent's literal re-offer rule); presentation delivery is the caller's job (two deferred items) until 2.5 adds the first production caller.
