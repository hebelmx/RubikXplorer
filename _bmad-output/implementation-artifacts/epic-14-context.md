# Epic 14 Context: Practice on my real cube and start at my level

<!-- Generated from planning artifacts. Regenerate with compile-epic-context if planning docs change. -->

## Goal

Let a Learner who can already solve skip straight to what they want to learn next by choosing an entry level (no check required, earlier Stages recorded honestly as *skipped*, never *mastered*), and let any Learner practice any Algorithm or any Lesson step on their own physical cube one turn at a time with *Done*, with a Quick Check available at any step, including mid-Algorithm. It ships in v1.0 (PO scope move, 2026-11-20; GitHub #208, #209) and runs before release Stories 12.3, 12.4 and 12.5. Camera parts stay behind `CAMERA_SCAN`; everything else must work with every camera flag OFF.

## Stories

- Story 14.1: Choose my entry level when I can already solve it
- Story 14.2: Practice an Algorithm on my real cube
- Story 14.3: Practice every Lesson step on my real cube, with a Quick Check at any step

## Requirements & Constraints

- **Entry level:** a Stage can unlock by a chosen entry level as well as by prerequisites, skip-ahead check or Placement. Earlier Stages become *skipped* (self-chosen, recorded as such), never *mastered*; nothing already reached is re-locked, downgraded or erased; the scramble skip-ahead check stays available as a choice.
- **Progress persistence:** Stage completed/skipped/mastered survives restarts and process death; repeated writes append, never corrupt.
- **Algorithm practice:** any of the 78 Algorithms (57 OLL + 21 PLL) can be practiced on a real cube with *Done*, with a Quick Check at any step.
- **Quick Check:** offered at milestones and after mismatches, available at any step including mid-Algorithm, never required. A match is recorded *verified*; *verified* only when exactly one candidate Move sequence fits. A partial-face check verifies only a match; a mismatch from partial faces is at most *likely* and offers a Guided 6-face Scan. Never a false *verified*.
- **2-look in physical mode:** every 2-look Lesson offers Physical-cube Mode like any other Lesson.
- **Accessibility/localization:** content labels on every control, scalable text, non-gesture alternative for every essential action; all Learner-facing text in content strings (English only at launch). New copy must read correctly with TalkBack and fit at 200% font on the Lenovo's 600 dp width.
- **Human gate (every story):** device check on the Lenovo Tab M10 Plus 3rd Gen (TB128XU, the minimum reference) and the Galaxy S24 FE, on the main app build (never a debug-only launcher).

## Technical Decisions

- **AD-3 (One state owner per flow: `LearningSession`, revisioned):** one active session app-wide, held by the `@Singleton SessionHost`, never a ViewModel; kinds are `LESSON`, `PRACTICE`, `TWO_LOOK`, `SOLVE_PLAYBACK`. Every change is a `SessionEvent` through the pure reducer; async effects carry `effectId` + `revision`. Algorithm detail stays outside any session; *I'm doing it on my cube* starts a `SOLVE_PLAYBACK` session over the set-up Moves (inverse from solved, skippable) then the Algorithm, so *Done*, Expected State, mismatch ladder and Quick Check behave exactly as Solution Playback. No new `SessionKind`; like every `SOLVE_PLAYBACK`, it does not resume after app close. In physical mode `virtual` mirrors `expected`; any `PhysicalConfirmed` marks a prior `observed` stale.
- **AD-5 (Observations and Certainty):** `SessionState` holds `virtual`, `expected`, `observed`. Only `tutor/Assess` produces Certainty (`VERIFIED | LIKELY | UNKNOWN`, categorical only). A `Partial` observation is never completed from `expected`; it verifies only the empty candidate (a match); a partial mismatch is at most `LIKELY`, moves Sync State to `PHYSICAL_MISMATCH` and offers a Guided 6-face Scan. Information-request order: Quick Check → which Move → one face → full re-entry.
- **AD-6 (Content is data: schema, predicates, IDs, text):** Stages, Lessons, Algorithms are JSON content with stable distinct ID namespaces; 2-look Lessons reference Algorithm IDs. All Learner-facing text is `Text(key, args)` in `content/strings/<locale>.json` (key form `<area>.<contentId>.<field>[.<n>]`, ICU named args); `strings.xml` holds static nav chrome only; the content test fails on duplicate/missing/mismatched keys.
- **AD-10 (Persistence: snapshots, checkpoints and append-only logs):** `progress_event` types are `LessonCompleted`, `StageSkippedByCheck`, `StageSkippedByPlacement`, `StageSkippedByChoice`, `StageUnlocked`, `ProgressReset(epoch)`; Stage status and Mastery are pure functions over the current epoch. `StageSkippedByChoice` (new) counts as *skipped*, never *mastered*, exactly like `StageSkippedByCheck`. **Only add, never downgrade:** `ChooseEntryLevel` (and `PlaceFromScan`) append `StageSkippedBy…` only for earlier Stages that are `LOCKED` or `IN_PROGRESS`, and `StageUnlocked` only for a target not yet unlocked; never write `ProgressReset`, never re-lock, never touch Lesson completions, Checkpoints, attempts or Algorithm Statuses. Rows are never updated; writes go through one serial writer. Each Room schema change ships with a migration and a migration test.
- **AD-18 (Feature flags for beta and cut order):** `FeatureFlags` port maps `{QUICK_SCAN, PLACEMENT, CASE_RECOGNITION, TEST_ME, CAMERA_SCAN, ADAPTIVE_ASSISTANCE}` to `{OFF, BETA, GA}`, set per build type in `build-logic`; no feature reads `BuildConfig`. Camera code lives only in `app/src/camera/`, composed only when a camera flag is above OFF; `PLACEMENT` requires `CAMERA_SCAN` above OFF. A raised debug flag never shows a dead entry point; features are accepted on the main app build.
- **AD-19 (One last-layer matcher):** `LastLayerMatcher` in `:core:domain` is the only definition of Case patterns and AUF; Case states and diagrams are derived, never stored. The set-up for practice is the Algorithm's inverse applied from solved.
- **AD-20 (Shared playback, generated notes):** one pure `PlaybackController` over `List<Move>` drives Demonstrations, Solution Playback and the Library; never checkpointed. Per-Move text comes only from `MoveNote.of(move)`. A Move list may be split into labeled segments (*Set-up* and *Algorithm* for practice); a segment label is a content `Text` key naming what is practiced, never claiming why a Move was chosen. The Library and Algorithm detail are not sessions.
- **Ownership Matrix (progress events):** Stage progress (Room `progress_event`) has exactly these writers: `CompleteLesson`, `PassSkipCheck`, `PlaceFromScan`, `ChooseEntryLevel`, `ResetProgress`. `ChooseEntryLevel` is the only writer of `StageSkippedByChoice`. State mutation happens only through the `SessionEvent` reducer or these single writers.

## UX & Interaction Patterns

- **Flow 8 (entry level / placement):** Welcome → *I can already solve it* → "Where do you want to start?". Choosing *I solve with the beginner method* marks Stages 1–7 ◇ *Skipped* and unlocks *Faster last layer*; none mastered, each stays open to revisit. Alternative *Check me with a scramble instead* → "Solve this scramble your way. Hints are off." (no time limit, same result). Failure path: giving up the check shows "Let's find where to start", offering the level list, Placement or Stage 1, with no penalty.
- **Entry-level picker (state pattern):** reached from Welcome → *I can already solve it* or Learn top app bar → *Find my place* → *Choose my level*. Title "Where do you want to start?"; four choices:
  - *I can solve the first two layers* → Stages 1–4 ◇ *Skipped*, *Last-layer orientation* unlocks;
  - *I solve with the beginner method* → Stages 1–7 ◇ *Skipped*, *Faster last layer* unlocks;
  - *I know 2-look OLL and PLL* → Stages 1–7 and both 2-look Stages ◇ *Skipped*, then the Library opens filtered to the full OLL set;
  - *Check me with a scramble instead* → the existing whole-Course skip-ahead check, unchanged.
  One line under the list: "Skipped Stages stay open, and nothing is marked mastered." With `PLACEMENT` on, *Not sure? Scan my cube* at the bottom. *Find my place* sheet holds *Scan my cube* (hidden when `PLACEMENT` is OFF) and *Choose my level*; neither resets progress.
- **UX-DR18 (Stage row):** Stage number in `data`, name in `title`, state as text + icon: Locked, In progress, Completed, ◇ Skipped, ◇ Skipped by placement, ◆ Mastered (in `accent`). Every skipped state uses an outlined ◇; a chosen-entry-level skip reads exactly like a skip-ahead check: "Skipped" (only Placement adds "by placement"). Reused for the 2-look mini-Course.
- **UX-DR22 (information architecture):** Welcome (first launch only) plus a 4-tab bottom bar (Learn, Solve, Library, You) that becomes a navigation rail at medium/expanded widths; Lesson, Scan and Playback are full-screen task flows with a top app bar and back; never more than one modal level; the Scan sub-flow returns to its caller.
- **UX-DR25 (state-pattern copy):** state-pattern copy and treatments are implemented where the feature lands; for this epic that is the entry-level pattern above (and skip-ahead check outcomes: passed → ◇ *Skipped*, next Stage unlocks, not mastered; not passed → "Let's do this Stage together — it'll be quick.", no penalty or record against the Learner).
- **Physical controls (UX-DR12):** **Done** stays primary (even under a recovery prompt); secondary *I did something else*; overflow *My cube doesn't match* / *I'm not sure what I did*. The *Quick Check* camera icon is available at every step of a Lesson, Solution Playback and Algorithm practice, highlighted only at Objective boundaries, Stage milestones and after mismatches; never required; shown only when `CAMERA_SCAN` ≠ OFF. Every Lesson, including 2-look, offers the Physical-mode switch.
- **Flow 11 (Algorithm practice):** Library → Algorithm → *I'm doing it on my cube* → "Set it up from solved, or does your cube already show this case?" Playback opens in Physical-cube Mode on the 2D net with *Set-up* then *Algorithm* segments; each turn advances on *Done*; the last *Done* offers *Again* (replays from set-up). *My cube already shows this case* starts at the Algorithm with no Recognize or Quick Scan step. In physical mode the transport shows a read-only Move strip and no scrubber.
- **Certainty wording:** only *Verified*, *Likely*, *Unknown*, each with the next available action; never "Solved!" on the strength of a *Done* tap.

## Cross-Story Dependencies

- Order: 14.1 → 14.2 → 14.3.
- 14.1 depends on Story 7.7 (whole-Course skip-ahead check, reused unchanged). Story 11.2's re-placement shares the *Find my place* entry; with `PLACEMENT` OFF, *Find my place* offers only *Choose my level*, with no dead control.
- 14.2 depends on Stories 9.5 (Algorithm detail) and 8.5 (Solution Playback in Physical-cube Mode); its Quick Check AC also on Story 10.7.
- 14.3 depends on Stories 8.2 (Physical-mode switch), 9.7 (2-look Lessons) and 10.7 (Quick Check).
- Epic 15 shares AD-20 segment labels and `SOLVE_PLAYBACK` playback. Story 10.11 raises `CAMERA_SCAN` for v1.0, after release Stories 12.3–12.5.
