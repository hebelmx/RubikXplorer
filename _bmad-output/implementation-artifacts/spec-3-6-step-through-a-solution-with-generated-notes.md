---
title: 'Step through a Solution with generated notes'
type: 'feature'
created: '2026-11-03'
status: 'done'
baseline_revision: ed182dfe8674f0193bd3992cc3ff332e07428c6e
review_loop_iteration: 0
followup_review_recommended: false
context:
  - '{project-root}/AGENTS.md'
  - '{project-root}/_bmad-output/implementation-artifacts/epic-3-context.md'
warnings: ['oversized']
deferred:
  - summary: >-
      Shared playback transport lacks the UX-DR14 Move strip with an ink-primary outline on the current Move and a visible scrubber.
    evidence: |-
      Intent audit: PlaybackTransport (extracted unchanged from the Story 2.4 Demonstration player) has no strip; scrubTo is reachable only programmatically. Pre-existing in the Demonstration transport, not introduced by 3.6.
    location: >-
      app/src/main/kotlin/dev/rubikxplorer/app/ui/playback/PlaybackTransport.kt
    severity: low
---

<intent-contract>

## Intent

**Problem:** A verified Solution (Story 3.4 `SolveCubeOutcome.Solved`) has nowhere to go: there is no `SOLVE_PLAYBACK` session start, no generated per-Move note, and the shared playback transport is private to the Lesson Demonstration player (FR-16, AD-20, UX-DR14/24).

**Approach:** Add a pure `MoveNote.of(move): Text` in `:core:domain` `playback/` with one fixed-template, argument-free `en.json` message per Move value (54); add a `SessionEvent.SolvePlaybackStarted(state, solution)` that starts a `SOLVE_PLAYBACK` session holding the start state and Solution; extract the Demonstration transport into a shared composable and build a `SolutionPlayback` composable that plays the session's Solution through `PlaybackController` with the MoveNote under the current Move.

## Boundaries & Constraints

**Always:** Notes come only from `MoveNote.of` (AD-20) and describe face + direction + piece group, never a purpose (Principle 3). Note copy lives in `core/domain/src/main/resources/content/strings/en.json`, resolved by `ContentStrings`. Playback position/speed live in `PlaybackController` (not session state, never checkpointed — `PlaybackNotCheckpointedTest` must stay green). Session changes only via `SessionEvent` → `reduce` (AD-3). Existing Demonstration behavior, test tags (`playback:*`) and `DemonstrationPlayerTest` stay unchanged. `:core:*` stay pure JVM.

**Never:** Wire playback into `AppShell`/Solve tab, run `SolveCube`, add the Learn offer, suspend/resume a Lesson, physical-cube *Done* stepping or Quick Check (Stories 3.7, Epic 8). No playback field on `SessionState`/`SessionEvent`/`Effect`. No free-text or screen-literal notes. Do not edit planning artifacts, `_bmad/` or `.claude/skills/`.

## I/O & Edge-Case Matrix

| Scenario | Input / State | Expected Output / Behavior | Error Handling |
|----------|--------------|---------------------------|----------------|
| Note for R | `FaceTurn(R, CLOCKWISE)` | `Text("msg.move-note.face.r.clockwise")` → "Turn the Right face clockwise: the right column moves up." | — |
| Every Move | all 54 values (6 face + 6 wide × 3 turns, 3 slice + 3 rotation × 3 turns) | distinct key, no args, key in `en.json`, matches template regex, passes `VoiceGuide`, no purpose words | test fails on any miss |
| Start playback | IDLE or a Lesson session; `SolvePlaybackStarted(scrambled, solution)` where solution solves it | `kind = SOLVE_PLAYBACK`, `lesson = null`, `entry == virtual == scrambled`, `solution` stored, revision +1, pending cleared, tutor step/reveal/feedback cleared (mode kept), one `Presentation.Snap(entry)` | — |
| Unverified Solution | solution that does not leave the state solved | state unchanged, no presentation | ignored (never an invented answer) |
| Other events in playback session | `MoveCommitted`, `Undo`, `Redo`, `Reset`, `StepAdvanced`, … while `SOLVE_PLAYBACK` | state unchanged | ignored |
| Step forward/back | `SolutionPlayback` on that session, tap `playback:forward` / `playback:back` | counter `n / size` changes by one; `playback:move` shows current Move; `playback:note` shows its resolved MoveNote | back disabled at 0, forward/end disabled at end |
| Pause / replay | tap `playback:playPause` while playing; tap `playback:restart` | playing stops where it is; replay snaps to start and plays again | — |
| Final step | forward to the end (or `playback:end`) | drawn net shows the solved state | — |
| Empty Solution | already-solved state, `solution = []` | session starts; transport disabled; no note shown | — |

</intent-contract>

## Code Map

- `core/domain/src/main/kotlin/dev/rubikxplorer/domain/playback/` -- spine puts `MoveNote` here beside `PlaybackController` (pure; `of`, `play`, `pause`, `stepForward`, `stepBack`, `replay`, `seek`, `withSpeed`, `advance`).
- `core/domain/src/main/kotlin/dev/rubikxplorer/domain/cube/Move.kt` -- `FaceTurn`/`WideTurn(face, turn)`, `SliceTurn(slice M/E/S)`, `CubeRotation(axis X/Y/Z)`, `Turn`.
- `core/domain/src/main/kotlin/dev/rubikxplorer/domain/text/Text.kt` -- `Text(key, args)`.
- `core/domain/src/main/resources/content/strings/en.json` -- add 54 `msg.move-note.*` keys; `msg.*` keys are exempt from the "content string used by a Lesson" check.
- `core/domain/src/test/kotlin/dev/rubikxplorer/domain/content/VoiceGuide.kt` -- internal test helper (`problems(message)`); reuse for notes. `ShippedContentTest` scans literal `Text("…")` calls (computed keys are skipped, so the MoveNote test must check key existence itself).
- `core/application/src/main/kotlin/dev/rubikxplorer/application/session/{SessionEvent,SessionState,SessionKind,Reduce}.kt` -- `reduce` guard currently `!state.isActive && event !is LessonStarted`; `isActive = lesson != null`; `start()` pattern for a new session; `withHistory` bumps revision.
- `core/application/src/main/kotlin/dev/rubikxplorer/application/session/TRANSITIONS.md` -- normative table parsed by `TransitionsTableTest` (row per event; asserts `eventTypes.size == 10`; `changed()` detects `session` via `lesson`/`entry`, revision check via `lesson`/`history`).
- `core/application/src/test/kotlin/dev/rubikxplorer/application/session/{TransitionsTableTest,ReduceTest,PlaybackNotCheckpointedTest}.kt` -- extend; fixtures in `Lessons.kt` (`started()`, `commit`, `undo`, `T0`).
- `core/application/src/main/kotlin/dev/rubikxplorer/application/usecase/SolveCube.kt` -- `SolveCubeOutcome.Solved(moves: List<FaceTurn>)`; read-only, source of the Solution in 3.7.
- `app/src/main/kotlin/dev/rubikxplorer/app/ui/lesson/DemonstrationPlayer.kt` -- private `PlaybackTransport`, `TransportButton`, `frameDelta`, `SPEED_LABELS`, frame loop `LaunchedEffect`; `DemonstrationPlayerState` wraps controller + `Net2DRenderer` + revision counter, `currentMove = moves[max(position-1,0)]`.
- `app/src/main/kotlin/dev/rubikxplorer/app/content/ContentStrings.kt` -- `resolve(Text)`.
- `app/src/main/kotlin/dev/rubikxplorer/app/ui/theme/Type.kt` -- `RubikTheme.textStyles.notation/data`; meta = `MaterialTheme.typography.bodySmall`.
- `app/src/test/kotlin/dev/rubikxplorer/app/ui/lesson/DemonstrationPlayerTest.kt`, `ui/editor/ManualCubeEditorTest.kt` -- Robolectric compose patterns (sticker color semantics, `ContentStrings` from `ContentLoader.load(ContentSource.SHIPPED)`).

## Tasks & Acceptance

**Execution:**
- [x] `core/domain/src/main/kotlin/dev/rubikxplorer/domain/playback/MoveNote.kt` -- `object MoveNote { fun of(move: Move): Text }` keyed `msg.move-note.<face|wide|slice|rotation>.<u|r|f|d|l|b|m|e|s|x|y|z>.<clockwise|counterclockwise|half>`, no args -- AD-20 single note source.
- [x] `core/domain/src/main/resources/content/strings/en.json` -- 54 messages per Design Notes table -- copy.
- [x] `core/domain/src/test/kotlin/dev/rubikxplorer/domain/playback/MoveNoteTest.kt` -- enumerate all 54 Moves; assert exact R example, distinct keys, empty args, key present, template regex, `VoiceGuide.problems` empty, and a purpose-word regex (e.g. `solve|so that|in order|to (place|put|fix|move|bring|get|make)|because|pair|insert|set ?up|prepare|cross|corner|edge|algorithm|goal|ready`) never matches -- AC 1.
- [x] `core/application/.../session/SessionEvent.kt`, `SessionState.kt`, `SessionKind.kt`, `Reduce.kt` -- add `SolvePlaybackStarted(state: CubeState, solution: List<Move>, at)`; `SessionState.solution: List<Move>` (empty unless `SOLVE_PLAYBACK`); `isActive = kind != null`; non-start events apply only when `kind == LESSON`; KDocs updated -- AC 2 session.
- [x] `core/application/.../session/TRANSITIONS.md` + `TransitionsTableTest.kt` -- row `SolvePlaybackStarted | session, virtual, pending, tutor | no | yes`; document playback-session rules; count 11; `changed()`/revision check also compare `kind` and `solution`; scenarios from IDLE, `a`, `b`, `askedB`, `revealed` -- normative table.
- [x] `core/application/src/test/.../session/ReduceTest.kt` (or new `SolvePlaybackSessionTest.kt`) -- matrix rows for start, unverified Solution, ignored events, Lesson replaced, `LessonStarted` from playback.
- [x] `app/src/main/kotlin/dev/rubikxplorer/app/ui/playback/PlaybackTransport.kt` -- move transport, buttons, speed row, counter/move row, `frameDelta`, frame-loop effect here behind a small `PlaybackTransportState` interface; optional `note: String?` line (tag `playback:note`, meta style) under the Move -- one shared transport (AD-20). Update `DemonstrationPlayer.kt` to use it with no behavior change (keep `frameDelta` reachable for its existing test).
- [x] `app/src/main/kotlin/dev/rubikxplorer/app/ui/solve/SolutionPlayback.kt` -- `SolutionPlaybackState` (controller from `session.entry` + `session.solution`, `Net2DRenderer`, implements transport state, `currentMove`), `rememberSolutionPlaybackState(session, reduceMotion)` requiring `kind == SOLVE_PLAYBACK`, and `SolutionPlayback(state, strings: ContentStrings, modifier, style)` composable (net + transport + note from `MoveNote.of(currentMove)`), tag `solutionPlayback`.
- [x] `app/src/test/kotlin/dev/rubikxplorer/app/ui/solve/SolutionPlaybackTest.kt` -- Robolectric: session from `reduce(IDLE, SolvePlaybackStarted(...))`; cover transport, note, pause/replay, final solved net, empty Solution rows.

**Acceptance Criteria:**
- Given `MoveNote.of(move)`, when generated for every Move type and turn, then each note resolves to "Turn the <layer> <direction>[, like the <Face> face]: the <piece group> <moves|turns> <way>." and a test asserts no note claims a strategic purpose.
- Given a `SOLVE_PLAYBACK` session started with a validated state and its Solution, when the Learner uses the shared playback transport, then they can step forward and back, pause and replay, the resolved MoveNote of the current Move shows under it, and the final step draws the solved state.

## Spec Change Log

## Review Triage Log

### 2026-11-03 — Review pass
- verdicts: 23 findings — high 0, medium 0, low 18, false 5, maybe-false 0
- findings:
  - `[low]` `[reject]` Blind: MoveNoteTest regex does not tie direction/way to the real cube effect — copy is fixed text checked by hand (verification layer re-checked every direction and found them correct); a cube-simulation oracle adds test machinery for a static table.
  - `[low]` `[reject]` Blind: template check allows singular/plural verb mismatch — current copy is correct; tightening is extra test branching with no present defect.
  - `[low]` `[reject]` Blind: slice halves drop ", like the … face" while rotation halves keep it — deliberate: slice halves would exceed the 20-word voice limit, rotation halves need it to tell x2 from y2.
  - `[low]` `[reject]` Blind: "Up two layers" wide phrasing awkward — wording follows the spec copy table ("Right two layers"); cosmetic.
  - `[false]` `[reject]` Blind: `require` in rememberSolutionPlaybackState crashes when a LessonStarted replaces the session mid-composition — no caller exists (wiring is Story 3.7); a fail-fast contract on an unreachable misuse is correct behavior and is tested.
  - `[low]` `[patch]` Blind: remember keys omit revision, so an identical restart keeps old position — added `session.revision` to the keys.
  - `[low]` `[reject]` Blind: `isActive` widened to any session / hasUnsavedSteps KDoc says Lesson — no main-source reader of `isActive`; documented in SessionState and TRANSITIONS.md.
  - `[low]` `[reject]` Blind: TeachingModeChanged ignored during playback — documented in TRANSITIONS.md (tutor events leave a playback session unchanged); playback has no mode control, mode is kept for the next Lesson.
  - `[low]` `[reject]` Blind: rejected Solution indistinguishable to caller; length unbounded — caller checks `kind`; SolveCube Solutions are short and verification is one linear pass.
  - `[low]` `[reject]` Blind: TransitionsTableTest lacks playback scenarios for Redo/ResetConfirmed/ResetDeclined/StepAdvanced — those are covered by SolvePlaybackSessionTest `a playback session ignores every event but a new start`.
  - `[low]` `[reject]` Blind: note at position 0 and 1 shows the same Move — spec-mandated shared Demonstration rule (`moves[max(position-1,0)]`).
  - `[low]` `[patch]` Blind: `playback:note` not announced to screen readers — note Text is now a polite live region; asserted in SolutionPlaybackTest.
  - `[low]` `[reject]` Blind: SolutionPlaybackTest skips speed and animated path — the shared transport's speed and frame loop are covered by DemonstrationPlayerTest.
  - `[low]` `[reject]` Blind: refactor loose ends (frameDelta shim, public helpers, manual forwards) — the shim keeps DemonstrationPlayerTest unchanged as the spec requires; visibility nits have no named harm.
  - `[false]` `[reject]` Blind: PlaybackNotCheckpointedTest not extended to SolutionPlaybackState/NetPlayback — those live in `:app`, which `:core:application` session types cannot reference (AD-1 boundary); the reflection test already scans the new `solution` field.
  - `[low]` `[reject]` Blind: test style nits (FQNs, assertEquals(null), word-split) — cosmetic.
  - `[false]` `[reject]` Edge: require during recomposition crashes app — same refutation as the Blind `require` row; no host renders it yet.
  - `[low]` `[patch]` Edge: identical restart keeps old position — same root cause and fix as the Blind remember-keys row.
  - `[false]` `[reject]` Edge: NetPlayback/renderer allocated before require — the constructor throws immediately, the objects are garbage; no observable harm.
  - `[low]` `[patch]` Verification: no test that a new session re-keys playback state — added `a new session starts the playback over` (different Solution and identical restart).
  - `[false]` `[reject]` Intent: playback not reachable from the app shell — story AC is stated "given a SOLVE_PLAYBACK session"; Story 3.7 ("Solve tab end to end") owns the entry point.
  - `[false]` `[reject]` Intent: Lesson replaced rather than suspended — Story 3.7's AC owns "before the Lesson session is suspended".
  - `[low]` `[defer]` Intent: transport lacks UX-DR14 Move strip/visible scrubber — pre-existing in the Demonstration transport; recorded in frontmatter deferred.

## Design Notes

Copy rule (Learner faces F, U on top). Face names capitalized: Up, Down, Left, Right, Front, Back. Directions: "clockwise", "counter-clockwise", "a half turn". Half turns end "… moves halfway around." (rotations: see below).

| Move | Layer phrase | Group | clockwise way | counter-clockwise way |
|---|---|---|---|---|
| R / L | Right / Left face | right / left column | moves up / moves down | moves down / moves up |
| U / D | Up / Down face | top / bottom row | moves left / moves right | moves right / moves left |
| F | Front face | front layer | turns to the right | turns to the left |
| B | Back face | back layer | turns to the left, as seen from the front | turns to the right, as seen from the front |
| wide r l u d f b | Right two layers (etc.: "Turn the Right two layers clockwise: …") | right two columns / top two rows / front two layers … | same way as the face, plural verb ("move", "turn") | same |
| M | middle layer between Left and Right … , like the Left face | middle column | moves down | moves up |
| E | middle layer between Up and Down …, like the Down face | middle row | moves right | moves left |
| S | middle layer between Front and Back …, like the Front face | middle layer | turns to the right | turns to the left |
| x / y / z | whole cube …, like the Right / Up / Front face | front face / front face / top face | moves to the top / to the left / to the right | to the bottom / to the right / to the left |

Rotation halves: x2, y2 "the front face moves to the back"; z2 "the top face moves to the bottom". Examples: "Turn the middle layer between Left and Right clockwise, like the Left face: the middle column moves down." / "Turn the whole cube counter-clockwise, like the Up face: the front face moves to the right." No Notation letters in notes (the Move token is shown separately); every note ≤ 20 words.

`SolvePlaybackStarted` checks `solution.fold(state, apply).isSolved` (bounded work) so an unverified list never becomes a session. Playback stepping is not a `SessionEvent`: AD-20 makes `PlaybackController` the owner of position and forbids checkpointing it; the session only fixes what is played. The current Move follows the Demonstration rule (`moves[max(position-1, 0)]`).

## Verification

**Commands:**
- `export JAVA_HOME=/usr/lib/jvm/java-25-openjdk-amd64 ANDROID_HOME=$HOME/Android/Sdk; ./gradlew :core:domain:test :core:application:test :app:testDebugUnitTest` -- expected: new and existing tests pass.
- `export JAVA_HOME=/usr/lib/jvm/java-25-openjdk-amd64 ANDROID_HOME=$HOME/Android/Sdk; ./gradlew spotlessCheck build` -- expected: all CI gates pass (Kover, LearnerTextPolicy, boundaries, lint, APKs).

## Auto Run Result

Status: done. Added `MoveNote.of(move): Text` (54 argument-free fixed-template notes in `en.json`), a `SessionEvent.SolvePlaybackStarted(state, solution)` that starts a `SOLVE_PLAYBACK` session only for a Solution that verifiably solves the state, a shared `PlaybackTransport` extracted from the Demonstration player (now with a note line), and a `SolutionPlayback` composable that plays the session's Solution with the MoveNote under the current Move. Not wired into the app shell (Story 3.7).

Files changed:
- `core/domain/.../playback/MoveNote.kt`, `content/strings/en.json` — note source and 54 messages.
- `core/domain/src/test/.../playback/MoveNoteTest.kt` — all 54 Moves: example, keys, template, voice guide, no-purpose check.
- `core/application/.../session/{SessionEvent,SessionState,SessionKind,Reduce}.kt`, `TRANSITIONS.md` — playback session start, `solution` field, `isActive = kind != null`, non-Lesson sessions ignore non-start events.
- `core/application/src/test/.../session/{SolvePlaybackSessionTest,TransitionsTableTest,Lessons}.kt` — session matrix rows; table now 11 events.
- `app/.../ui/playback/PlaybackTransport.kt` — shared transport, NetPlayback, frame loop, polite live-region note.
- `app/.../ui/lesson/DemonstrationPlayer.kt` — uses shared transport, behavior unchanged.
- `app/.../ui/solve/SolutionPlayback.kt` + `SolutionPlaybackTest.kt` — playback state/composable and 9 Robolectric tests.

Review: four layers, 23 findings (low 18, false 5). Patched 3 low entries (remember keys on revision, re-key test, live-region note); deferred 1 (UX-DR14 Move strip, pre-existing); rejected the rest with reasons in the Review Triage Log. Follow-up review recommended: false — no high or medium patched (patched by verdict: high 0, medium 0, low 3).

Verification: `./gradlew :core:domain:test :core:application:test :app:testDebugUnitTest spotlessCheck build` succeeded before review and after patches. MoveNoteTest 6, SolvePlaybackSessionTest 7, TransitionsTableTest 2, SolutionPlaybackTest 9, DemonstrationPlayerTest 16 — 0 skipped/failed. Matrix audit: every row covered by an executed test.

Residual risks: note directions verified by hand, not by a cube-effect oracle; playback unreachable in-app until Story 3.7; Lesson suspension on solve start is 3.7's; not checked on a physical device.
