---
name: RubikXplorer
status: final
created: 2026-10-29
updated: 2026-10-29
sources:
  - ../../prds/prd-RubikXplorer-2026-10-29/prd.md
  - ../../prds/prd-RubikXplorer-2026-10-29/addendum.md
  - ../../architecture/architecture-RubikXplorer-2026-10-29/ARCHITECTURE-SPINE.md
---

# RubikXplorer — Experience Spine

## Foundation

- **Platform:** a native Android app for phones and tablets, in portrait and landscape. It uses Jetpack Compose and **Material3**, so navigation, system back, dialogs, sheets, dynamic type and TalkBack all follow Material3 and Android conventions. This document specifies only the behavior that differs from them.
- **Visual identity:** defined in `DESIGN.md`.
- **Themes:** light and dark are designed equally and follow the system setting, with an override in Settings.
- **Offline:** every surface works without a network.
- **Language:** English only. Every string is a content key (spine AD-6).
- **Source of terms:** PRD Glossary terms are used verbatim.
- **Source of behavior:** the architecture spine binds behavior. The renderer is a projection (AD-4); certainty is categorical (AD-5); features ship behind flags (AD-18).

## Information Architecture

| Surface | Reached from | Purpose | FR |
| --- | --- | --- | --- |
| Welcome (first launch only) | Cold start, no profile | Choose a starting point: *I'm new* (Stage 1), *Start from my cube* (Placement, hidden if `PLACEMENT` is OFF), or *I can already solve it* (a skip-ahead check of the whole Course, then *Faster last layer*). | FR-4, FR-17, FR-27 |
| **Learn** (tab) | Bottom nav | Continue card (the latest Checkpoint), the Course Stage list, and *Faster last layer* (2-look, as Stage rows with completed and mastered states). | FR-4, FR-17, FR-19 |
| Stage detail | Learn → Stage row | The Stage's Lessons, the Mastery progress line, the trend row, *Practice this Stage*, and *I already know this* (skip-ahead check). | FR-4, FR-20 |
| **Lesson** | Continue, Stage detail | Split cube + lesson panel: Teaching Modes, Demonstration, hints, recovery. | FR-1–FR-13 |
| Physical-cube Mode | Lesson toggle | The same Lesson with the Expected State, *Done*, *Report a mismatch* and Quick Check. | FR-7–FR-9, FR-26 |
| Independent practice | Stage detail, end of Course | A fresh Scramble, assistance reduced. | FR-20 |
| **Solve** (tab) | Bottom nav | *Scan my cube* and *Enter colors*. If `CAMERA_SCAN` is BETA (the spike-failure fallback), *Enter colors* becomes the primary action. Solves are not resumed after the app closes (AD-10). | FR-14, FR-25 |
| Manual Cube Editor | Solve, scan fallback, mode switch | Paint the 54 stickers; validation feedback. | FR-14 |
| Scan (sub-flow) | Solve, Physical mode, Welcome, Recognize | Guided 6-face Scan, or Quick Scan, then sticker review and correction. Returns a result to its caller. | FR-21–FR-23 |
| Solution Playback | After a valid scan or entry | Step through the Solution, with optional physical confirmation. | FR-15, FR-16 |
| **Library** (tab) | Bottom nav | 78 Algorithms grouped by OLL and PLL family, with filters by family and Algorithm Status. | FR-18 |
| Algorithm detail | Library tile, 2-look Lesson, Recognize | Demonstration, recognition diagram, Notation, Algorithm Status. | FR-18 |
| Recognize case | Library top bar | Scan the top layer and get the Case, or candidates. | FR-28 |
| **You** (tab) | Bottom nav | Progress: Stage states, a trend row per Stage (hints and errors per attempt), and Algorithm Status counts shown as plain counts, not achievements. Also holds Settings. | FR-19, FR-20 |
| Settings | You | Theme, color-blind glyphs, animation speed, haptics, reduce motion, the separate resets, about/licences. | FR-19, NFR-7 |
| Color Calibration | Settings, scan prompt ("Colors look off — calibrate?"), first scan of a stickerless cube | Show each of the six centers in turn and confirm the swatch, then save. A separate reset is available. | FR-24 |

- **Navigation:** a bottom navigation bar with four tabs: Learn, Solve, Library, You. Medium and expanded widths switch to a navigation rail.
- **Full-screen task flows:** Lesson, Scan and Playback hide the bottom bar and use a top app bar with back.
- **Modal depth:** never more than one modal level. The Scan sub-flow is a full-screen task that returns to its caller, not a stacked modal.

## Voice and Tone

The DESIGN.md Brand & Style sets the brand voice. The microcopy should sound **precise, calm and on the Learner's side**: short declarative sentences, the Glossary's terms, and no exclamation marks except at milestones.

Every explanation step follows the four-part voice guide (PRD addendum §I): **Goal → Keep → Watch → Notice**. The UI label *Keep* displays the schema field `preserve` (AD-6); the label is deliberately friendlier, and the field name is unchanged.

Some UI labels differ from their Glossary terms:

| UI label | Glossary term |
| --- | --- |
| *Guide me back* | Guided recovery |
| *Physical mode* | Physical-cube Mode |
| *Solve* tab | Solve my cube |
| ◇ *Skipped* / *Skipped by placement* | the two skip records in AD-10 |

| Do | Don't |
| --- | --- |
| "Goal: bring the white-red edge up next to its center." | "Let's do this!" |
| "Keep: the white cross stays solved." | (no mention of what must not break) |
| "Watch the right face — only that column moves." | "Perform R U R' U'." |
| "Notice: the corner is in the top layer now, and the cross is untouched." | "Correct! ✓✓✓" |
| "That turn moved the cross. Undo, or let me guide you back." | "Wrong move." / "Error." |
| "I can't tell what happened on your cube yet. Can you scan the front and right faces?" | "You turned the wrong face." (without evidence) |
| "Verified: you turned R instead of R'." | "Probably you turned R instead of R'" labeled as fact |
| "This corner shows two whites. Check these two stickers." | "Invalid cube state." |
| "Your cube should be solved now. Check it with the camera, or tell me if it is." (physical, before evidence) | "Solved!" on the strength of a *Done* tap |
| "Solved. That's your first solve — on your own cube." (after evidence) | Streak counters, points, leaderboards |

**Certainty wording is fixed.** Only *Verified*, *Likely* and *Unknown* are used, and each comes with the action available next. Solution Playback notes use the generated template only, for example "Turn the Right face clockwise: the right column moves up". They never claim a purpose (AD-20).

## Component Patterns

These are behavioral rules. Visual specs are in DESIGN.md Components.

| Component | Use | Behavioral rules |
| --- | --- | --- |
| Cube stage | Lesson, Playback, Practice, Library detail, Editor | Shows committed state only (AD-4). A frame label always says which state is shown: VIRTUAL, EXPECTED or VERIFIED. Double-tap resets the view. Pieces outside the current step dim during Demonstrations and in *Guide me*; the Keep pieces stay lit. A Partial observation shows only as the SEEN strip (DESIGN.md). |
| Undo / Redo / Reset | Cube stage toolbar | Undo steps back one committed transition. Redo is cleared by any new Move. Reset asks "Reset the cube? This lesson's unsaved steps will be lost." only when progress would be lost. Undo stops at barriers (a scan, a mode switch, a restore), with the tooltip "Can't undo past your last scan." In Physical-cube Mode, Undo reverts the Expected State and says "Turn R' on your cube to undo." |
| Move key bar | Every interactive cube (in Physical-cube Mode, shown only after *I did something else*) | Each key commits exactly one Move, the same path as a gesture. Key labels never change with View Rotation. A long-press shows the key's plain-language name, for example "U' — Up face counter-clockwise". Wide and slice keys are under "more". A light haptic tick fires on commit. |
| Teaching Mode switcher | Lesson | Switching mode keeps the Cube State and progress. The UI renders `TutorDecision.reveal` and never decides reveal itself (AD-3). The Objective always shows first (Principle 1). *Show me* plays after the Objective, on tap. *Guide me* shows Goal, Keep and Watch, then reveals the Move chip on tap. *Test me* waits for the Learner's Move. *Let me try* keeps Goal and Keep visible and hides only the Moves. The last mode used is remembered. The mode is hidden when its flag is OFF (`TEST_ME`). |
| Assistance line | Lesson panel | When adaptive assistance (FR-13) changes the help, one `meta` line says why, for example "Showing the turn, since this step tripped you up twice." or "Fewer hints now — you've got this step." It never switches the Teaching Mode without a tap. *Back to my usual help* undoes the change. When `ADAPTIVE_ASSISTANCE` is OFF, the line is absent and the Hint Ladder is fixed. |
| Step card | Lesson | Shows Goal, Keep and Watch before the Move, and Notice after it. In *Test me* and *Let me try*, the Move chips stay hidden until a hint is requested. |
| Hint button | Lesson, Practice | Each tap moves up one rung: conceptual clue → highlighted target or Move arrow (violet) → full Demonstration. The rung count shows on the button. The ladder resets on a new Objective. |
| Recovery prompt | After a diagnosed error | Inline in the lesson panel, never a dialog. It offers *Undo*, *Guide me back* and *Try again*, in that order. *Guide me back* shows "Finding the shortest way back…" while the plan is pending, then the correction ("3 moves — your cross stays") with the preserved pieces highlighted. If no plan is found: "I can't find a short way back. Undo, or start this step again." |
| Physical controls | Physical-cube Mode | A primary **Done** button stays primary, even under a recovery prompt. A secondary *I did something else* opens the Move key bar to enter the Move. A tertiary menu holds *My cube doesn't match* and *I'm not sure what I did*. *Quick Check* (a camera icon) is offered at Objective boundaries, Stage milestones and after any mismatch report. It is never required, and *Done* always works (FR-26). |
| Certainty chip | Diagnosis, Quick Check results, Playback | Always shows one of the three states, never a percentage. Tapping it explains what evidence the state rests on. |
| Playback transport | Demonstrations, Solution Playback, Algorithm detail | One shared controller (AD-20): play/pause, step back/forward, restart, and speed 0.5×/1×/2×. Scrubbing snaps to Move boundaries. |
| Scan viewfinder | Scan sub-flow | Captures automatically when the grid is aligned and steady (about 600 ms), with a manual shutter as backup. The face prompt names the center color and the up color. A wrong orientation produces a re-prompt, not an error. Quality coaching appears inline ("Too much glare — tilt the cube"). |
| Sticker review net | Scan review, Editor | Uncertain stickers are pre-selected. Tap a sticker, then a color, to set it. **Accept** stays disabled until every uncertain sticker has been confirmed or changed and the state is valid. Validation messages point at the specific stickers. |
| Algorithm tile | Library | Tap to open detail. Status changes happen only in detail, through an explicit control. Algorithm detail keeps the bottom nav. |
| Trend row | You, Stage detail | Shows the trend across attempts in order, never a total or a score. Hint use is never framed as failure (SM-C1). |
| Quick Scan capture | Scan sub-flow | Hold the cube by a corner and capture twice (the counter shows "1 / 2"). Any face that can't be read hands off to the Guided Scan grid for that face only. |
| Beta label | Flagged features | Tapping it explains: "Works, but may misread colors in poor light. Your manual entry always works." |

## State Patterns

| State | Surface | Treatment |
| --- | --- | --- |
| First launch | Welcome | Three choices. Nothing is gated behind an account or the network. |
| Resume after kill (virtual) | Lesson | Restores silently to the Checkpoint. A one-line note reads "Picked up where you left off." |
| Another session started | Solve, while a Lesson is open | "Starting a solve pauses your lesson." The Lesson resumes from its Checkpoint. A paused physical Lesson returns through *Confirm your cube*. |
| Resume after kill (physical) | Lesson | Shows the **Confirm your cube** card with three options: *It matches* / *Scan it* / *Enter it*. The cube frame shows EXPECTED (AD-10). |
| Content changed since save | Lesson | "This lesson was updated. We've restarted it with your cube as it was." |
| Expected only | Physical mode | Dashed EXPECTED frame. No certainty chip until there's evidence: the EXPECTED frame is itself the honest label (decision logged). |
| Objective met, physical (expected) | Physical mode | "If your cube matches the picture, this step is done." The frame stays EXPECTED, a Quick Check is offered, and *Next* is enabled. No celebration. |
| Mismatch ladder | Physical mode | The tutor asks for information in this order: (1) a Quick Check of the named faces; (2) "Which turn did you make?" with the Move key bar; (3) "Look at the front face — which corner is white?" with a one-face picker; (4) "Let's re-enter your cube" with a Guided Scan or the Editor. Each rung stays at ? *Unknown* until it yields evidence. |
| Adopt observed | Mismatch, Full observation | *Use my cube as it is now* replaces the Expected State and starts a Guided recovery. A Partial observation must first be completed by a Guided Scan or the Editor. |
| Verified | Physical mode | A ✓ *Verified* chip and a VERIFIED frame. It reverts to EXPECTED on the next *Done*. |
| Mismatch, unknown | Physical mode | Chip ? *Unknown*, with the next request in FR-9 order. Never a guessed cause. |
| Mismatch, likely | Physical mode | Chip ≈ *Likely*, with the candidate shown and *Scan all six faces* (Guided 6-face Scan) offered to confirm it. |
| Test me outcomes | Lesson | **On path** (the next Move of the current step's authored path): "That's the move — keep going." **Progress** (a different Move that still helps): "That works too — you're closer." **Neutral**: "That didn't change much. Try another turn." **Harmful** (disturbs a piece the current step preserves): the same as a diagnosed error, with a recovery prompt. |
| Error category copy | Lesson | **Unexpected orientation:** "Your cube is turned — hold it with white on the bottom, green in front." Offers *Show me how*, no undo. **Wrong piece:** "That moved the white-orange corner; we want white-red-green." **Wrong orientation:** "Right corner, but twisted — white should face down." **Invalid entry:** see the Editor rows. |
| Stale scan result | Physical mode | Silently discarded (AD-3). If the Learner started it, re-offer: "Your cube changed while scanning — scan again?" |
| Completed layer disturbed | Lesson | The disturbed pieces get a violet outline. A recovery prompt appears, and the Checkpoint is kept. |
| Objective met (virtual or verified) | Lesson | A subtle check, a brief violet pulse on the affected pieces, and a light haptic. Notice text appears, and *Next* is enabled. |
| First solve | End of Stage 6 | The milestone moment. In Physical-cube Mode it shows only after a Quick Check or *Yes, it's solved*. |
| Stage mastered | When the Mastery Condition is met (usually in Practice) | The milestone moment, then the next Stage offered. |
| Skip-ahead check | Stage detail, Welcome | Passed: ◇ *Skipped*, and the next Stage unlocks (not mastered). Not passed: "Let's do this Stage together — it'll be quick." No penalty and no record against the Learner. |
| Placement outcomes | Placement | A partly solved cube: suggest the first unmet Stage, with *Choose another Stage*. Nothing solved: suggest Stage 1. Cross or more done but a non-white cross: "Start with a white cross for this course." Already at the last layer: offer *Faster last layer* or Stage 5. Solved: "Your cube is solved — scramble it and scan again, or start Stage 1." |
| Solver working | Solve → Playback | An indeterminate progress bar, cancelable. This state is rarely visible. |
| Solver timeout / failure | Solve | "Couldn't find a solution in time. Your colors are still here — try again." No partial result is shown. |
| Pruning tables building (first use) | Solve | "Preparing the solver (one time, a few seconds)." Lessons are unaffected. |
| Invalid entered state | Editor, Scan review | The offending pieces are outlined in red (status-invalid), with a plain explanation. Accept stays disabled. Errors that don't belong to one piece get their own lines: "One corner is twisted — check the corners you changed last.", "One edge is flipped.", "Two pieces are swapped — this can't happen on a real cube." |
| Camera permission denied | Scan entry points | From *Solve*, the Manual Cube Editor opens directly (FR-25), with a one-line note. Elsewhere: an explanation card and *Enter colors instead*, plus a Settings deep-link if the permission is permanently denied. |
| Feature flag OFF | Any | The entry point is hidden entirely, with no dead buttons. |
| Feature flag BETA | Quick Scan, Recognize, Placement, and every camera feature when `CAMERA_SCAN` is BETA | A BETA label. The manual route is always one tap away. Tooltip copy depends on the feature: for camera features "May misread colors in poor light. Manual entry always works."; for *Test me* "New — tell us if it feels off." |
| Cross-links to OFF features | Any | Links to a flagged-OFF feature are removed. For example, *Start from my cube* disappears from Recognize and Welcome when `PLACEMENT` is OFF. |
| Back to virtual | Physical mode → virtual | "The on-screen cube now matches what we expected on yours." Redo clears, and Undo stops here. |
| Recognize: not at LL | Recognize | "The first two layers aren't solved yet." Offers *Start from my cube*. |
| Recognize: ambiguous | Recognize | "This could be one of 3 cases" with tiles, plus *Scan another angle*. |
| Library filter empty | Library | "No algorithms marked Learning yet." Links to 2-look. |
| Progress reset | Settings | A confirmation sheet names exactly what is erased. Calibration and preferences are untouched. |

## Interaction Primitives

- **Cube gestures** (via a pure `GestureInterpreter`, AD-4):
  - A one-finger drag that **starts on a sticker** and runs along a row or column turns that layer.
  - A one-finger drag **starting off the cube**, or a two-finger drag anywhere, is a View Rotation.
  - Below the confidence threshold, a drag does nothing. It never guesses a turn.
- **Double-tap** on the stage resets the view.
- **Input during animation** commits immediately. Once more than 3 Moves are queued, the queue fast-forwards (AD-4).
- **Cube Rotations:**
  - z: a two-finger twist on the cube.
  - x and y: the ⟳ keys. [ASSUMPTION: gestures for x and y are deferred.]
  - A Cube Rotation re-frames the cube with a 200 ms turn, and the frame label briefly shows the new front color.
  - A View Rotation (a one-finger drag off the cube, or a two-finger drag) changes nothing and gets no label.
- **Haptics:** a light tick on each committed Move, a soft confirm when an Objective is met, and nothing on a mistake. All of them can be turned off.
- **Banned:**
  - shake animations on errors;
  - red flashes for a Learner's mistakes;
  - countdown timers outside FR-29 (P1);
  - streaks, points, push notifications or badges;
  - auto-advancing past a Notice before the Learner taps *Next*;
  - carousels.

## Accessibility Floor

Behavioral rules here; visual contrast is in DESIGN.md.

- **Non-gesture equivalents:** every gesture has a button through the Move key bar. View Rotation also has arrow buttons in the stage overflow.
- **TalkBack:**
  - The cube stage exposes a description derived from `CubeState` (AD-17). It summarizes by default: "White cross solved; 2 of 4 first-layer corners placed". A *Read faces* action reads face by face: "Up face: yellow, yellow, red…".
  - Every certainty change is announced.
  - Move keys announce plain names, for example "R prime: right face counter-clockwise".
- **Color-blind glyphs:** a Settings toggle shows letters on every sticker surface. It is offered on first launch and on the first scan.
- **Reduce motion:** when the system setting is on, or the app's override is:
  - Demonstrations step instead of animating;
  - the milestone burst is replaced by a static frame;
  - camera transitions snap.
- **Dynamic type** up to 200%. The lesson panel reflows and scrolls, the cube stage keeps its minimum size, and no control truncates.
- **Touch targets:** at least 48dp, including move keys.
- **Labels:** every interactive control has a content description, enforced by Android lint in CI (AD-14).
- **Focus order:** the frame label, then the cube description, then the Move key bar, then the lesson panel, then the Teaching Mode switcher.

## Responsive & Platform

| Window class | Lesson layout | Navigation |
| --- | --- | --- |
| Compact (phone portrait) | Stacked: the cube stage (about 45% of the height, minimum 280dp) over the scrolling lesson panel. The Move key bar sits on the stage's bottom edge. | Bottom bar, hidden in task flows |
| Medium (phone landscape, small tablet) | Side by side: the cube stage on the left (about 55%), the lesson panel on the right. The Move keys form a vertical grid on the stage's right edge. | Navigation rail |
| Expanded (tablet) | Side by side, with the lesson panel capped at 560dp wide. Library uses a 4-column grid. | Navigation rail |

- **State preservation:** changing orientation never changes session state. All state lives in the session (AD-3), so the layout just recomposes.
- **Camera:** the camera runs in portrait. In landscape the scan UI rotates its overlays and keeps the grid upright.
- **Back:** system back follows Android conventions. In a Lesson, back leaves immediately with no confirmation, because every Move is committed and checkpointed before it animates (AD-4, AD-10).

## Inspiration & Anti-patterns

[ASSUMPTION: drawn from the PRD's competitive notes; the user named no references.]

- **Kept from competitor apps:** an interactive 3D cube, camera scanning, and a step-through solution player. These are table stakes (PRD addendum §G).
- **Rejected: solver-first home screens.** RubikXplorer opens on *Learn*, and *Solve* is a tab, not the front door.
- **Rejected: notation dumps.** Notation never appears without a Goal/Watch explanation.
- **Rejected: gamification.** No streaks, XP or leaderboards; the counter-metrics are SM-C1 to SM-C3.
- **Rejected: overconfident feedback.** No "Wrong!" without evidence. Certainty is always labeled.

## Key Flows

The flows mirror the PRD's user journeys UJ-1 to UJ-6.

### Flow 1 — Ana's first solve, physical cube (UJ-1)

1. On first launch Welcome appears, and Ana taps **I'm new**. She's offered the color-blind letters and declines.
2. She starts Learn → Stage 1 *Cube fundamentals*. Using the Move key bar she learns what `R` is: the step card says "Watch the right face — only that column moves."
3. In Stage 2 *First cross* she switches to Physical-cube Mode. A Confirm-your-cube card appears, and she taps *It matches*. The frame changes to EXPECTED.
4. She watches the Demonstration, turns her cube, and taps **Done**. "If your cube matches the picture, this step is done." The Notice line appears, the frame stays EXPECTED, and *Quick Check* is offered.
5. In Stage 3 she turns the wrong way and taps *My cube doesn't match*. The chip shows ? *Unknown*: "I can't tell what happened yet. Scan the front and right faces?"
6. The Quick Check returns, and the chip changes to ✓ *Verified*: "That turn moved the cross." A recovery prompt offers *Undo* or *Guide me back*. She picks *Guide me back*.
7. Sessions later, Stage 6 ends. "Your cube should be solved now. Check it with the camera, or tell me if it is." She runs a Quick Check, and it comes back ✓ *Verified*.
8. **Climax:** the milestone moment. "Solved. That's your first solve — on your own cube." Then *Practice on a new scramble* is offered.

**Failure path:** she has no camera permission at step 5. The tutor works down the mismatch ladder instead: "Which turn did you make?" with the Move keys, then a one-face question. Certainty stays *Unknown* until she answers. At step 7, she taps *Yes, it's solved* instead of scanning.

### Flow 2 — Diego goes faster: 2-look LL (UJ-2)

1. Learn → *Faster last layer* has unlocked. Diego got there by passing the skip-ahead check from Welcome (*I can already solve it*, Flow 8); finishing the Course also unlocks it.
2. He opens a 2-look OLL Lesson. It shows the recognition diagram, the Demonstration, and *Guide me* on the virtual cube.
3. At the end of the Lesson: "Add these algorithms to Learning?" He accepts.
4. He practices a fresh Scramble's last layer using 2-look only.
5. **Climax:** "Last layer solved with 2-look, no hints." The mini-Course row now reads *Mastery: 1 of 2*. In Library he filters by *Learning* and sees his set out of 78.

### Flow 3 — Lucía just wants it solved (UJ-3)

1. Solve tab → **Scan my cube** → Quick Scan (BETA) → two corner photos.
2. The review net opens with 2 uncertain stickers pre-selected. She fixes them, and Accept enables.
3. Playback opens. She taps *I'm following on my cube*, and each step advances on *Done*, with the MoveNote under each Move. A Quick Check is available at any step.
4. **Climax:** the final step solves her cube. Then a quiet offer: "Want to learn how to do this yourself?"

**Failure path:** glare spoils the bottom face. The app asks for a Guided Scan of that face only, with *Enter colors instead* always visible.

### Flow 4 — Marco starts from his own cube (UJ-4)

1. Welcome → **Start from my cube** → Guided 6-face Scan → review → Accept.
2. Placement: "Your cross is done and 2 of 4 corners are in. Start at *First-layer corners*?" He can also tap *Choose another Stage*, but he accepts. Stages 1–2 show ◇ *Skipped by placement*.
3. In Physical mode he makes a mistake. A Quick Check gives ✓ *Verified*: "You turned R instead of R'." The fix is shown.
4. **Climax:** the layer is complete, Stage 3 is marked *Completed*, and Stages 1–2 remain open to visit.

### Flow 5 — Diego asks which OLL this is (UJ-5)

1. Library → **Recognize case** (BETA) → one top-corner photo.
2. **Climax:** "OLL 45 · T-shape". The Algorithm detail opens with its Demonstration playing.
3. He taps *Mark as Learning*, an explicit choice that is never automatic.

**Failure paths:**
- If the visible stickers fit several Cases, he sees the candidate tiles and *Scan another angle*.
- If the first two layers aren't solved, he's offered Placement (*Start from my cube*).

### Flow 6 — Laura's five-minute evaluation (UJ-6)

1. She installs the app and opens it, offline and with no sign-up. Welcome → *I'm new*.
2. She turns the cube with gestures, then rotates the view.
3. Library → an OLL tile → she scrubs the Demonstration back and forth.
4. Solve → *Scan my cube*, and she accepts the camera permission.
5. **Climax:** smooth 60 FPS turns, an honest scan review, and a clean, consistent visual system in both themes. Nothing crashes and nothing asks for an account.

### Flow 7 — Ana practices to mastery (FR-20)

1. Stage detail for *First cross* reads: Mastery 1 of 2 · 1 with reduced help. Ana taps *Practice this Stage*.
2. Independent practice deals a fresh Scramble. The step card shows only the Objective, and hints are available on request. This is the reduced assistance.
3. She solves the cross with one hint.
4. **Climax:** the Mastery Condition is met. The milestone moment plays, then "First cross mastered."
5. Her trend row on You shows hints per attempt falling across five attempts (3 → 2 → 2 → 1 → 1), drawn as dots with no score.

### Flow 8 — Diego skips ahead (FR-4, FR-17)

1. Welcome → *I can already solve it* → "Solve this scramble your way. Hints are off."
2. He solves the scramble, with no time limit.
3. **Climax:** Stages 1–7 show ◇ *Skipped*, and *Faster last layer* unlocks. None of them is marked mastered.
4. **Failure path:** if he gives up, "Let's find where to start" offers Placement or Stage 1, with no penalty.

### Flow 9 — Calibration and resets (FR-19, FR-24)

1. On Lucía's stickerless cube the scan flags many uncertain stickers, and the scan offers "Colors look off — calibrate?"
2. Calibration shows each of the six centers in turn: "Show the white center." She confirms each swatch, then taps *Save*.
3. **Climax:** her rescan reads every sticker confidently.
4. Later, in Settings → *Reset progress*, the sheet lists exactly what is erased: Stages, attempts and Algorithm Statuses. Calibration and preferences have their own resets.

## Surface Coverage

Every surface in the Information Architecture is reached by at least one Key Flow:

| Surface | Flows |
| --- | --- |
| Welcome | 1, 4, 6, 8 |
| Learn | 1, 2, 4 |
| Stage detail | 7, 8 |
| Lesson | 1, 2, 4 |
| Physical-cube Mode | 1, 4 |
| Independent practice | 7 |
| Solve | 3, 6 |
| Scan | 1, 3, 4, 5, 6, 9 |
| Manual Cube Editor | failure paths of 1 and 3 |
| Solution Playback | 3 |
| Library | 2, 5, 6 |
| Algorithm detail | 2, 5, 6 |
| Recognize case | 5 |
| You | 7 |
| Settings | 9 |
| Color Calibration | 9 |

Every PRD user journey (UJ-1 to UJ-6) maps to one Key Flow (1–6).

## Mockups

These mockups show composition only. Where a mockup and this spine disagree, the spine wins.

| Mockup | Shows |
| --- | --- |
| `mockups/key-lesson-phone.html` | Compact Lesson, *Guide me* before the Move, light and dark |
| `mockups/key-lesson-tablet.html` | Expanded Lesson, Objective met |
| `mockups/key-physical-and-scan.html` | Physical-cube Mode after a Quick Check (Verified, recovery prompt) with an inset of the Unknown state; Quick Scan sticker review with glyphs on |
| `mockups/key-library.html` | Library (dark) and Algorithm detail with the playback transport |

The mockups predate the finalize edits, so they don't show a few of them: the cube-stage toolbar (Undo / Redo / Reset), the Assistance line and the SEEN strip. The tablet mockup also shows the virtual Objective-met state; the physical variant uses the "If your cube matches…" wording. [NOTE FOR UX: re-render them if a visual reference is needed for these.]
