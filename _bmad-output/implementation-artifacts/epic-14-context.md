# Epic 14 Context: Practice on my real cube and start at my level

<!-- Generated from planning artifacts. Regenerate with compile-epic-context if planning docs change. -->

## Goal

A Learner who can already solve chooses an entry level instead of proving every Stage. Earlier Stages are recorded honestly as *skipped*, never *mastered*. Any Learner can also practice any Algorithm or any Lesson step on their own physical cube, one turn at a time with *Done*, and run a Quick Check at any step, including mid-Algorithm. This is v1.0 scope (GitHub #208, #209) and runs before release Stories 12.3, 12.4 and 12.5. Camera parts stay behind `CAMERA_SCAN`. Everything else must work end to end with every camera flag OFF.

## Stories

- Story 14.1: Choose my entry level when I can already solve it
- Story 14.2: Practice an Algorithm on my real cube
- Story 14.3: Practice every Lesson step on my real cube, with a Quick Check at any step

## Requirements & Constraints

- **Entry level only adds, never downgrades.** A chosen level never re-locks, downgrades or erases progress and never resets it. Earlier Stages show as *skipped*, stay open to revisit, and none counts as *mastered*. The scramble skip-ahead check stays available as one of the choices.
- **Algorithm practice** works for all 78 Algorithms (57 OLL + 21 PLL). The Learner sets the case up from solved, or starts directly at the Algorithm when the cube already shows the case. There is no Recognize or Quick Scan step.
- **Quick Check** is available at every step of a Lesson, Solution Playback or Algorithm practice, including partway through a step's Moves. It is never required, and *Done* always works. It must never produce a false *verified*: a partial-face check can only verify a match, and a mismatch is at most *likely* and offers a Guided 6-face Scan.
- **Physical-cube Mode** is offered by every Lesson, including every 2-look Lesson.
- **Copy and accessibility:** all Learner-facing text comes from content strings, reads correctly with TalkBack, and fits at 200% font on a 600 dp width.
- **Human gate for every story:** a device check on the Lenovo Tab M10 Plus 3rd Gen (TB128XU, the minimum reference) and the Galaxy S24 FE, on the main app build reached from the real entry point. A debug-only launcher never counts.

## Technical Decisions

- **Progress log (append-only Room `progress_event`):** `StageSkippedByChoice` behaves like `StageSkippedByCheck` in Stage status (skipped, never mastered). `ChooseEntryLevel` is its only writer. Like `PlaceFromScan`, it appends a skip only for earlier Stages that are `LOCKED` or `IN_PROGRESS`, and `StageUnlocked` only for a target that is not yet unlocked. It never writes `ProgressReset`, and it never touches Lesson completions, Checkpoints, attempts or Algorithm Statuses. Rows are never updated. Every Room schema change ships with a migration and a migration test.
- **Practice is a `SOLVE_PLAYBACK` session.** Algorithm detail stays outside any session. *I'm doing it on my cube* starts a `SOLVE_PLAYBACK` session in Physical-cube Mode whose Move list is the set-up (the Algorithm's inverse applied from solved, skippable) followed by the Algorithm. *Done*, the Expected State, `MoveNote` and the mismatch ladder therefore behave exactly as in Solution Playback. Do not add a new `SessionKind`. Like every `SOLVE_PLAYBACK` session, it does not resume after the app closes.
- **One session owner:** every change goes through the pure `SessionEvent` reducer on the single `SessionHost` session. In physical mode, `virtual` mirrors `expected`.
- **Certainty** (`VERIFIED | LIKELY | UNKNOWN`) comes only from the tutor's assessment. A partial observation is never completed from `expected`.
- **Segments:** the playback Move list is split into labeled *Set-up* and *Algorithm* segments. Each label is a content `Text` key that names what is practiced and never explains why a Move was chosen. Per-Move text comes only from `MoveNote`. Case patterns and set-ups come from the single `LastLayerMatcher` and are never stored.
- **Flags:** read them only through the `FeatureFlags` port. Camera code is composed only when a camera flag is above OFF, `PLACEMENT` requires `CAMERA_SCAN`, and no entry point is ever shown dead.

## UX & Interaction Patterns

- **Entry-level picker** is reached from Welcome → *I can already solve it*, or from Learn → *Find my place* → *Choose my level*. It is titled "Where do you want to start?" and offers four choices:
  - first two layers: Stages 1–4 skipped, and *Last-layer orientation* unlocks;
  - beginner method: Stages 1–7 skipped, and *Faster last layer* unlocks;
  - 2-look OLL and PLL: Stages 1–7 and both 2-look Stages skipped, and the Library opens filtered to the full OLL set;
  - *Check me with a scramble instead*: the existing skip-ahead check, unchanged.

  The line "Skipped Stages stay open, and nothing is marked mastered." sits under the list. *Not sure? Scan my cube* appears only when `PLACEMENT` is on.
- **Stage row:** a chosen-level skip reads exactly like a skip-ahead check: an outlined ◇ and *Skipped*. Only Placement adds "by placement".
- **Physical controls:** **Done** stays primary, even under a recovery prompt. *I did something else* is secondary. The overflow menu holds *My cube doesn't match* and *I'm not sure what I did*. The *Quick Check* camera icon stays available at every step but is highlighted only at Objective boundaries, Stage milestones and after mismatches. It is hidden when `CAMERA_SCAN` is OFF.
- **Algorithm practice flow:** Library → Algorithm → *I'm doing it on my cube*, then "Set it up from solved, or does your cube already show this case?" Playback opens on the 2D net with the *Set-up* and *Algorithm* segments, showing a read-only Move strip and no scrubber. The last *Done* offers *Again*, which replays from the set-up.
- **Certainty wording** is limited to *Verified*, *Likely* or *Unknown*, each with the next action. The app never says "Solved!" on the strength of a *Done* tap.

## Cross-Story Dependencies

- The order is 14.1 → 14.2 → 14.3.- 14.1 reuses the whole-Course skip-ahead check from Story 7.7 and shares the *Find my place* entry with the Placement work in Story 11.2. With `PLACEMENT` OFF, that entry shows only *Choose my level*.
- 14.2 depends on Story 9.5 (Algorithm detail) and Story 8.5 (physical Solution Playback). Its Quick Check criterion also depends on Story 10.7.
- 14.3 depends on Story 8.2 (the Physical-mode switch), Story 9.7 (2-look Lessons) and Story 10.7 (Quick Check).
- Epic 15 shares the segment labels and `SOLVE_PLAYBACK` playback. Story 10.11 raises `CAMERA_SCAN` for v1.0.
