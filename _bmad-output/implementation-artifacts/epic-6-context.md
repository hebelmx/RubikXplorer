# Epic 6 Context: Pick up where I left off

<!-- Generated from planning artifacts. Regenerate with compile-epic-context if planning docs change. -->

## Goal

A Learner's Lesson survives closing the app, a phone call, or forced process death. They come back to exactly the cube and step they left, every try at a step is recorded honestly for their trend, and their display and teaching preferences stick between launches. This epic introduces the Room persistence layer, creating only the tables it needs. It also gives the free cube one event owner: a pure reducer that becomes the only path for turns, undo, redo, reset and scramble. The free cube stays in memory and is never persisted.

## Stories

- Story 6.1: Survive process death with session snapshots
- Story 6.2: Versioned Checkpoints at Objective boundaries
- Story 6.3: Record every attempt append-only
- Story 6.4: Preferences and Settings
- Story 6.5: Resume from the Continue card
- Story 6.6: Give the free cube one event owner

## Requirements & Constraints

- Persist these across restarts and forced process death: the current Checkpoint, each attempt's outcome with its error count and hints used, and preferences (animation speed, color-blind glyphs, theme, haptics, reduce-motion override, last Teaching Mode). Stage progress, Algorithm Status and Color Calibration also persist, but later epics own them (7, 9, 10).
- After a forced restart, restore the latest committed Checkpoint. A physical-mode Checkpoint must ask the Learner to confirm, scan or re-enter their cube before continuing. Physical mode itself lands in Epic 8.
- Repeating a Lesson adds new attempt records and never corrupts or changes earlier ones.
- Each scoped reset needs confirmation and touches only its own data. *Reset preferences* resets preferences only. The progress and calibration resets come later.
- Offline and private: data lives in app-private storage only. Backup stays disabled, there is no network, and release logs never contain Cube States.
- The Solver, I/O and classification never run on the main thread. StrictMode flags main-thread disk access in debug.
- `:core:*` stays pure Kotlin (no Room or Android) with at least 80% coverage. Each Room schema change ships with a migration and a migration test.

## Technical Decisions

- **Two saves with two purposes, both keyed by `SessionKind`:**
  - A `SessionSnapshot` is written on every committed transition, for process death.
  - A `Checkpoint` is written at Objective boundaries through the reducer effect `PersistCheckpoint`. It survives disturbances and backs the Continue card.
  - On cold start only `LESSON`, `PRACTICE` and `TWO_LOOK` resume. `SOLVE_PLAYBACK` never resumes.
- **Checkpoint DTO:** versioned, with `checkpointFormat`, `contentRevision`, content IDs, Facelets plus orientation strings, history as Notation, and mode.
  - It never stores raw `SessionState` and never embeds content. Cube states are stored as a 54-character facelet string plus an orientation ID. Instants are stored as UTC epoch ms.
  - The current step is never stored, because it is re-derived from the Lesson and the state.
  - On restore, IDs are resolved against the loaded content. If the revision differs or an ID is missing, the Lesson restarts with the stored cube and the Learner is told.
  - A restored physical session always lands in `PhysicalAwaitingConfirm`, with `observed` cleared and no effects resumed. `CheckpointRestored` is an undo barrier and clears redo.
  - Every format bump ships with a decoder for the previous format and a round-trip test.
- **Writes:**
  - All writes go through one serial writer, off the main thread.
  - Snapshot writes are conditional on `revision`.
  - Log rows and the snapshot from one transition are written in a single transaction.
  - Log rows carry the idempotency key `(sessionId, revision)`.
- **Append-only `attempt` log:**
  - It is written only from the reducer effects `AttemptStarted` and `AttemptEnded(objectiveId, outcome, errors, hints, assistanceLevel)`.
  - Rows are never updated. The only deletes are the Learner's scoped resets.
- **Single writers:**
  - Session state, Checkpoints and Attempts: the reducer and its effects.
  - Preferences: only `UpdatePreference` (Room or DataStore).
  - Free cube `MoveHistory`: only `reduceFreeCube`. It lives in memory and is never persisted.
- **Stack and DI:**
  - Room is `androidx.room3` 3.0.3 with KSP.
  - The Room db, DAOs, serial writer and repositories live in `:app` under `data/`. Repository ports live in `:core:application/port`.
  - Hilt is used only in `:app`. The reducer runs on one confined dispatcher, and I/O runs on the serial writer.
  - Effect failures become `…Failed` events, never crashes, and results are sealed.
- **Session model:**
  - At most one session is active, held by the `@Singleton SessionHost`. Starting a session of another kind suspends the current one.
  - A suspended Lesson resumes from its Checkpoint.
- **Free-cube reducer (6.6):**
  - It is a pure `reduceFreeCube(state, FreeCubeEvent)` in `:core:application`. The events are `MoveCommitted`, `Undo`, `Redo`, `Reset` and `Scrambled(moves)`.
  - The state holds the `MoveHistory` and a monotonic `revision`.
  - The presentation is revision-stamped: `Animate(move)` for a commit or Redo, `Animate(inverse)` for Undo, and `Snap` for Reset and Scramble.
  - The free cube stays outside `SessionHost` and the session model. `FreeCubeViewModel` only dispatches events, and Scramble Moves are generated outside the reducer.
- **Preference consumers:** `StickerAppearance` drives the glyphs on every sticker surface, and `RenderStyle` carries the color-blind display and speed into `CubeRenderer`.

## UX & Interaction Patterns

- **Resume after a kill (virtual):** the Lesson restores silently to the Checkpoint with the one-line note "Picked up where you left off."
- **Content changed since the save:** show "This lesson was updated. We've restarted it with your cube as it was."
- **Another session started:** show "Starting a solve pauses your lesson." The Lesson resumes from its Checkpoint, and a paused physical Lesson returns through *Confirm your cube*.
- **Learn tab:** shows a Continue card for the latest Checkpoint.
- **Settings:**
  - It is reached from the You tab placeholder.
  - It holds theme (system/light/dark, following the system by default), color-blind glyphs, animation speed, haptics, the reduce-motion override and the separate resets.
  - Changes apply immediately on every surface.
- **New Lesson:** it opens in the last Teaching Mode used.
- **Leaving a Lesson:** it is immediate, with no confirmation, because everything is committed and checkpointed first.
- **Copy:** all copy uses content string keys. The reset confirmation names exactly what is erased.

## Cross-Story Dependencies

- 6.1 sets up Room, the migration harness and the serial writer. Stories 6.2–6.4 build on it, and 6.3's attempt rows share 6.1's snapshot transaction.
- 6.5 depends on 6.2's Checkpoints. Its suspend-by-solve criterion relies on Story 3.7, which is still open pending its hardware gate (3.8).
- The Checkpoint retention on disturbance from Story 4.4 is what 6.2 persists.
- 6.6 is independent of 6.1–6.5. It must leave the Epic 1 and Epic 5 free-cube suites unchanged: the 2D/3D randomized sync suite and the 5.2 gesture tests.
- Later epics reuse this layer:
  - Epic 7 adds `progress_event` and progress reset.
  - Epic 8 adds the physical resume flow (*Confirm your cube*).
  - Epic 9 adds `algorithm_status_event`.
  - Epic 10 adds calibration and its reset.
