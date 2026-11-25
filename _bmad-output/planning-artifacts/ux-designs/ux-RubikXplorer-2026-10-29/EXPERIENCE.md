---
name: RubikXplorer
status: final
created: 2026-10-29
updated: 2026-11-24
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
- **Release (SCP 2026-11-20):** the PO moved the camera scan and review, Quick Check, Quick Scan and Placement, and the features of #198, #208, #209, #210, #249, #251, #252 and #253, into v1.0. Where this document says "v1.1, flag-gated" for those, read "v1.0, flag-gated": they stay behind their AD-18 flags, which Story 10.11 raises for the v1.0 release.
- **Source of behavior:** the architecture spine binds behavior. The renderer is a projection (AD-4); certainty is categorical (AD-5); features ship behind flags (AD-18).

## Information Architecture

| Surface | Reached from | Purpose | FR |
| --- | --- | --- | --- |
| Welcome (first launch only) | Cold start, no profile | Choose a starting point: *I'm new* (Stage 1), *Start from my cube* (Placement, hidden if `PLACEMENT` is OFF), or *I can already solve it*, which opens the entry-level picker (SCP 2026-11-20, #209; see State Patterns, *Entry level*). | FR-4, FR-17, FR-27 |
| **Learn** (tab) | Bottom nav | Continue card (the latest Checkpoint), the Course Stage list, and *Faster last layer* (2-look, as Stage rows with completed and mastered states; ◆ *Mastered* only once also *completed*, with "Mastery: 1 of 2" after a qualifying solve; SCP 2026-11-10). A *Find my place* action in the Learn top app bar opens a sheet with *Scan my cube* (re-placement, hidden when `PLACEMENT` is OFF) and *Choose my level* (the entry-level picker); neither resets progress (SCP 2026-11-20, #252, #209). | FR-4, FR-17, FR-19, FR-27 |
| Stage detail | Learn → Stage row | The Stage's Lessons, the Mastery progress line, the trend row, *Practice this Stage*, and *I already know this* (skip-ahead check). | FR-4, FR-20 |
| **Lesson** | Continue, Stage detail | Split cube + lesson panel: Teaching Modes, Demonstration, hints, recovery. | FR-1–FR-13 |
| Physical-cube Mode | Lesson toggle | The same Lesson with the Expected State, *Done*, *Report a mismatch* and Quick Check. | FR-7–FR-9, FR-26 |
| Independent practice | Stage detail, end of Course | A fresh Scramble, assistance reduced. | FR-20 |
| **Solve** (tab) | Bottom nav | *Scan my cube* and *Enter colors*. If `CAMERA_SCAN` is BETA (the spike-failure fallback), *Enter colors* becomes the primary action. **Any release with `CAMERA_SCAN` OFF:** *Enter colors* is the only action, and *Scan my cube* arrives when its flag is raised (Story 10.11). Solves are not resumed after the app closes (AD-10). | FR-14, FR-25 |
| Manual Cube Editor | Solve, scan fallback, mode switch, Placement, Recognize | Paint the 54 stickers, each face in any rotation; specific validation feedback (SCP 2026-11-20, #253). | FR-14 |
| Scan (sub-flow) *(v1.0, flag-gated: hidden while the flag is OFF, UX-DR19)* | Solve, Physical mode, Welcome, Recognize | Guided 6-face Scan, or Quick Scan, then sticker review and correction. Returns a result to its caller. | FR-21–FR-23 |
| Solution Playback | After a valid scan or entry | Step through the Solution on the 3D cube or the 2D net, with optional physical confirmation. *(FR-30, SCP 2026-11-20 #210, v1.0:* a *Solution style* control offers *Shortest* (the default on the Solve tab) or a method: *Beginner*, *2-look* or *Full OLL + PLL*; a method solution shows its segments by Stage or Case.) | FR-15, FR-16, FR-30 |
| **Library** (tab) | Bottom nav | 78 Algorithms grouped by OLL and PLL family, with filters by family and Algorithm Status. The letter families use shape names (*P-shape*, *T-shape*, *C-shape*, *W-shape*, *L-shape*), the same names the Recognize result uses. | FR-18 |
| Algorithm detail | Library tile, 2-look Lesson, Recognize | Demonstration, recognition diagram, Notation, Algorithm Status, and *I'm doing it on my cube* (Algorithm practice on a real cube, SCP 2026-11-20, #208). | FR-18 |
| Algorithm practice *(SCP 2026-11-20, #208)* | Algorithm detail → *I'm doing it on my cube* | Solution Playback in Physical-cube Mode over the set-up Moves then the Algorithm: *Done* advances, Quick Check at any step (camera flag). | FR-18, FR-26 |
| Recognize case *(v1.0, flag-gated: hidden while the flag is OFF, UX-DR19)* | Library top bar | Capture two opposite top corners, with yellow on top, and get the Case or candidates. | FR-28 |
| **You** (tab) | Bottom nav | Progress: Stage states, a trend row per Stage (hints and errors per attempt), and Algorithm Status counts shown as plain counts, not achievements. Also holds Settings. | FR-19, FR-20 |
| Settings | You | Theme, color-blind glyphs, *Move pictures* (default on; next to color-blind glyphs, SCP 2026-11-17), animation speed, haptics, reduce motion, the separate resets, about/licences. | FR-19, NFR-7 |
| Color Calibration *(v1.0, flag-gated: hidden while the flag is OFF, UX-DR19)* | Settings, scan prompt ("Colors look off — calibrate?"), first scan of a stickerless cube | Show each of the six centers in turn and confirm the swatch, then save. Each swatch is sampled from the ring around the center sticker's middle, so a logo is left out; if that ring isn't one even color, or the swatch is implausible for its color, the Learner is asked to put a plain sticker of that color in the middle square instead (SCP 2026-11-15). A swatch is captured automatically once the cube is held steady, as a face is in the scan; the Learner still confirms it with *Keep*, and *Capture* stays available (SCP 2026-11-16 main-app entry points, Story 10.5). A separate reset is available. | FR-24 **Torch (SCP 2026-11-21 quick-scan-side-face-accuracy, Story 10.21):** on a device with a flash, the calibration screen shows the same torch control as the Quick Scan corner view, on by default; Quick Scan uses a calibration only under the same torch state, and otherwise offers to calibrate again. |

- **Navigation:** a bottom navigation bar with four tabs: Learn, Solve, Library, You. Medium and expanded widths switch to a navigation rail.
- **Full-screen task flows:** Lesson, Scan and Playback hide the bottom bar and use a top app bar with back.
- **Modal depth:** never more than one modal level. The Scan sub-flow is a full-screen task that returns to its caller, not a stacked modal.

*(SCP 2026-11-25 retire-v1-1: v1.1 is retired; v1.0 scope.)*

## Voice and Tone

The DESIGN.md Brand & Style sets the brand voice. The microcopy should sound **precise, calm and on the Learner's side**: short declarative sentences, the Glossary's terms, and no exclamation marks except at milestones.

Every explanation step follows the four-part voice guide (PRD addendum §I): **Goal → Keep → Watch → Notice**. The UI label *Keep* displays the schema field `preserve` (AD-6); the label is deliberately friendlier, and the field name is unchanged.

Some UI labels differ from their Glossary terms:

| UI label | Glossary term |
| --- | --- |
| *Guide me back* | Guided recovery |
| *Physical mode* | Physical-cube Mode |
| *Solve* tab | Solve my cube |
| ◇ *Skipped* / *Skipped by placement* | the three skip records in AD-10 (*Skipped* covers both the skip-ahead check and a chosen entry level, SCP 2026-11-20) |

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
| "Your cube should be solved now. Compare it with the picture, then tell me if it is." (physical, before evidence, while `CAMERA_SCAN` is OFF). With `CAMERA_SCAN` on (v1.0, flag-gated): "Your cube should be solved now. Check it with the camera, or tell me if it is." | "Solved!" on the strength of a *Done* tap |
| "Solved. That's your first solve — on your own cube." (after evidence) | Streak counters, points, leaderboards |

**Certainty wording is fixed.** Only *Verified*, *Likely* and *Unknown* are used, and each comes with the action available next. Solution Playback notes use the generated template only, for example "Turn the Right face clockwise: the right column moves up". They never claim a purpose (AD-20).

*(SCP 2026-11-25 retire-v1-1: v1.1 is retired; v1.0 scope.)*

## Component Patterns

These are behavioral rules. Visual specs are in DESIGN.md Components.

| Component | Use | Behavioral rules |
| --- | --- | --- |
| Cube stage | Lesson, Playback, Practice, Library detail, Editor | Shows committed state only (AD-4). A frame label always says which state is shown: VIRTUAL, EXPECTED or VERIFIED, except on Solution Playback, which has no frame label and shows Certainty only through the certainty chip below the stage (SCP 2026-11-19). Double-tap resets the view. While the view is off home, a View rotated badge with Reset view shows in the stage's top-left overlay (after the frame label when shown), and face letters sit beside the faces that face the camera (SCP 2026-11-18). Pieces outside the current step dim during Demonstrations and in *Guide me*; the Keep pieces stay lit. A Partial observation shows only as the SEEN strip (DESIGN.md). On Solution Playback a view switch on the toolbar swaps the 3D cube and the 2D net. Solution Playback opens in 3D, except in Physical-cube Mode, where it opens on the 2D net (all 54 stickers to compare with EXPECTED); after the Learner's first switch, the remembered choice wins in both modes, and *Reset preferences* clears it. Switching mid-turn jumps straight to the current step with no animation (AD-4); the certainty chip and SEEN strip are unchanged. The 3D view shows the Move arrow on the current Move and carries the cube description for TalkBack (SCP 2026-11-17). |
| Undo / Redo / Reset | Cube stage toolbar | Undo steps back one committed transition. Redo is cleared by any new Move. Reset asks "Reset the cube? This lesson's unsaved steps will be lost." only when progress would be lost. Undo stops at barriers (a scan, a mode switch, a restore), with the tooltip "Can't undo past your last scan." In Physical-cube Mode, Undo reverts the Expected State and says "Turn R' on your cube to undo." |
| Move key bar | Every interactive cube (in Physical-cube Mode, shown only after *I did something else*) | Each key commits exactly one Move, the same path as a gesture. Key labels never change with View Rotation. A long-press shows the key's plain-language name, for example "U' — Up face counter-clockwise". Wide and slice keys are under "more". A light haptic tick fires on commit. |
| Teaching Mode switcher | Lesson | Switching mode keeps the Cube State and progress. The UI renders `TutorDecision.reveal` and never decides reveal itself (AD-3). The Objective always shows first (Principle 1). *Show me* plays after the Objective, on tap. *Guide me* shows Goal, Keep and Watch, then reveals the Move chip on tap. *Test me* waits for the Learner's Move. *Let me try* keeps Goal and Keep visible and hides only the Moves. The last mode used is remembered. The mode is hidden when its flag is OFF (`TEST_ME`). |
| Assistance line | Lesson panel | When adaptive assistance (FR-13) changes the help, one `meta` line says why, for example "Showing the turn, since this step tripped you up twice." or "Fewer hints now — you've got this step." It never switches the Teaching Mode without a tap. *Back to my usual help* undoes the change. When `ADAPTIVE_ASSISTANCE` is OFF, the line is absent and the Hint Ladder is fixed. |
| Step card | Lesson | Shows Goal, Keep and Watch before the Move, and Notice after it. In *Test me* and *Let me try*, the Move chips stay hidden until a hint is requested. |
| Hint button | Lesson, Practice | Each tap moves up one rung: conceptual clue → highlighted target or Move arrow (violet) → full Demonstration. The rung count shows on the button. The ladder resets on a new Objective. |
| Recovery prompt | After a diagnosed error | Inline in the lesson panel, never a dialog. It offers *Undo*, *Guide me back* and *Try again*, in that order. *Guide me back* shows "Finding the shortest way back…" while the plan is pending, then the correction ("3 moves — your cross stays") with the preserved pieces highlighted. If no plan is found: "I can't find a short way back. Undo, or start this step again." |
| Physical controls | Physical-cube Mode | A primary **Done** button stays primary, even under a recovery prompt. A secondary *I did something else* opens the Move key bar to enter the Move. A tertiary menu holds *My cube doesn't match* and *I'm not sure what I did*. *Quick Check* (a camera icon) is offered at Objective boundaries, Stage milestones and after any mismatch report. It is never required, and *Done* always works (FR-26). **Any step (SCP 2026-11-20, #208):** the *Quick Check* icon also stays available at every step of a Lesson, Solution Playback and Algorithm practice, including mid-Algorithm; it is offered (highlighted) only at the points above. Shown only when `CAMERA_SCAN` ≠ OFF. Every Lesson, including the 2-look Lessons, offers the Physical-mode switch. |
| Certainty chip | Diagnosis, Quick Check results, Playback | Always shows one of the three states, never a percentage. Tapping it explains what evidence the state rests on. |
| Playback transport | Demonstrations, Solution Playback, Algorithm detail | One shared controller (AD-20): play/pause, step back/forward, restart, and speed 0.5×/1×/2×. The Move strip shows every Move; tapping a chip or dragging the visible scrubber jumps to that Move boundary with no animation (AD-4). In Physical-cube Mode the strip is read-only and the scrubber hidden: only *Done* advances (FR-16). When *Move pictures* is on, the current Move label and each strip chip carry the move picture; TalkBack skips the picture, because the MoveNote is spoken (SCP 2026-11-17). |
| Scan viewfinder | Scan sub-flow | Captures automatically when the grid is aligned and steady (about 600 ms), with a manual shutter as backup. Focus, exposure and white balance re-meter on the grid by themselves when alignment is first found and whenever the face changes; the Learner never has to refocus (SCP 2026-11-15). The face prompt names the center color; the face may be turned any way (SCP 2026-11-16, Story 10.14). When the app can't work out how a face was turned, it asks for that face again and names the side to put on top: a re-prompt, not an error. When no rotation fits and more than 2 faces would need asking again, it asks for none and goes straight to review with the best-fitting rotations, since the colors were probably misread (SCP 2026-11-19, #164). A retake from review asks only for the faces chosen, with the same any-rotation prompt and no re-prompt (SCP 2026-11-19, #235). Quality coaching appears inline ("Too much glare — tilt the cube"). On a phone in portrait, the viewfinder, the face prompt, the face's sticker net, the palette and the primary action all fit on one screen without scrolling (see Responsive & Platform). **Close (SCP 2026-11-24 github-issue-triage, #325, #313):** while no face has been captured in this viewfinder, *Close* and the system Back leave at once. Once one or more faces are captured, they ask "Discard the faces you captured?" with *Keep scanning* (closes the question; every captured face, the mini net and the current prompt stay) and *Discard* (the scan is cancelled and the Learner returns to the screen the scan was opened from: Welcome, the Solve tab, *Find my place* (Re-placement) or the Quick Check that asked for a full scan; the same rule applies from every caller). A retake from review and a Quick Scan hand-over keep their own back rules (Sticker review net, Quick Scan capture). In tablet landscape, *Close* sits in the same control group as the shutter and the face prompt, never alone in a far corner, and TalkBack reaches it with those controls. |
| Sticker review net | Scan review, Editor | Only confident stickers are prefilled: a sticker is filled when its nearest calibrated color beats the second nearest by at least 10 ΔE (the close-call rule; Story 10.13 may retune it). Close calls, such as a yellow that sits near white, stay blank. Uncertain stickers are pre-selected. Tap a sticker, then a color, to set it. After a color is set, the selection moves to the next blank or uncertain sticker (auto-advance), so a face needs about one touch per sticker; tapping any sticker selects it for a fix. **Accept** stays disabled until every uncertain sticker has been confirmed or changed and the state is valid. Validation messages point at the specific stickers. (SCP 2026-11-15) **Guidance (UX update 2026-11-16):** the next sticker to mark pulses slowly, and every set color gets the update cue plus a light haptic tick (DESIGN.md Sticker review net). The instruction line under the title follows the state: blanks left: "{n} stickers need a color. Tap the flashing one, then its color."; invalid: names the problem, for example "This corner shows two whites. Check the outlined piece."; all confident and valid: "Everything was read. Compare with your cube, then Accept." On the Learner's first review only, a one-time coach mark points at the flashing sticker and the palette ("Tap it, then the color you see on your cube"); one tap dismisses it, and it never shows again. **Retake faces (SCP 2026-11-19, #235):** in the Guided 6-face Scan review, *Retake faces* switches the net into a retake mode. The instruction line reads "Tap each face to scan again." Tapping a face toggles it for retake (any 1 to 6 faces), and TalkBack reads a selected face as "Green face, selected for retake"; sticker editing is off. *Retake N faces* ("Retake 1 face" for one) opens the viewfinder for only those faces, each with the any-rotation prompt; *Cancel* leaves retake mode and clears the selection. Review then reopens with the kept faces exactly as they were, including the Learner's fixes, and each retaken face shows only its new capture. If the retaken faces can't be turned into one valid cube, review shows the best fit and the usual validation messages, with no re-prompt. Backing out of the viewfinder before every chosen face is captured reopens review as it was, with nothing replaced. In the Quick Scan review it arrives with Story 10.17 (#198), now v1.0 (SCP 2026-11-20). *Close* on the review asks "Discard this scan?" only if the Learner changed a sticker or completed a retake, and *Discard* cancels the scan; with no change, *Close* falls back to the Editor with the scan's stickers (seeded on the Solve tab only, SCP 2026-11-24, #321). The viewfinder's own *Close* follows the Scan viewfinder row (SCP 2026-11-24, #325). **Paint mode (SCP 2026-11-20, #249; Scan review and Editor):** a long-press on a palette color locks it. While it is locked, each tap on a sticker sets that color directly, with the usual update cue and haptic tick, and auto-advance and the next-sticker pulse pause. A tap or long-press on the locked color, choosing another color with a plain tap, or entering retake mode ends paint mode. The instruction line reads "Painting {color}. Tap stickers to set them. Tap {color} again to stop." TalkBack announces "Painting {color}" and "Painting off"; each swatch also has a *Lock this color* / *Stop painting* accessibility action, so no long-press is needed. Plain tap behavior is unchanged outside paint mode. **Editor (SCP 2026-11-20, #253):** each face may be entered in any rotation. Around the face being edited, the four neighbouring center colors sit beside its edges ("green toward you" made visible), so the hold is never a secret. The face tabs carry a ✓ once every sticker of that face has been set, and a warning dot when a validation message points at that face. When a face's last sticker is set and auto-advance moves on, a one-line confirmation shows for about 1.5 s ("Front done ✓ → Right"), and the next sticker to set carries the same pulsing cursor as Scan review. Once every face is entered, the entered faces are tried in all four rotations through `ScanAssembler`: one valid cube → accepted with no question, and the net redraws the turned face with a `meta` note "We turned the Up face to match the others."; none or several → the instruction line names the face and the hold ("Check the Up face: hold green toward you and look at the top.") with that face's tab marked. |
| Algorithm tile | Library | Tap to open detail. Status changes happen only in detail, through an explicit control. Algorithm detail keeps the bottom nav. |
| Trend row | You, Stage detail | Shows the trend across attempts in order, never a total or a score. Hint use is never framed as failure (SM-C1). |
| Quick Scan capture | Scan sub-flow | *(SCP 2026-11-24 continuous-quick-scan; supersedes 2-corner holds of 10.18–10.25)* Opened from the Solve tab while `QUICK_SCAN` is BETA or GA (carrying the shared BETA label while BETA). Turn the cube smoothly in front of the camera from any starting side — no fixed corner pose or drawn guide sketch. The live preview outlines tracked faces while a compact growing 6-face `URFDLB` net fills in (`Sides mapped: N/6`, using shared `StickerAppearance` color-blind glyphs, AD-17). One inline cue line from `en.json` guides the next motion ("Bring cube into view", "Turn cube to show remaining {n} sides", "Tilt slightly to show an adjacent face", "Tilt slightly away from light", "Show {face} clearly to resolve conflict", "Layer turned mid-scan — hold layers fixed and rescan", "All 54 stickers verified!"). Regrips pause safely and re-align automatically once an adjacent face is shown (never locking a single symmetric face). **Torch:** on a device with a flash, an optional torch toggle (OFF by default to avoid harsh specular glare on close scans) turns off automatically on *Scan face by face*, leaving, or backgrounding; on a device without flash (e.g. Lenovo TB128XU), no torch control or copy appears. **Controls & fallback:** *Reset* clears the current map in place; *Scan face by face* stays always visible, keeping every already-confirmed face and handing only the remaining faces to Guided 6-face Scan (Story 10.17 — backing out of the Guided handover returns to Quick Scan with the confirmed faces intact); *Enter colors instead* stays visible and seeds the Manual Cube Editor on the Solve tab with confirmed stickers when ≥ 1 face was mapped (Story 10.15, `#321`). **Close & Back:** leave immediately at 0 mapped faces, and ask "Discard this scan?" once ≥ 1 face is mapped (matching Quick Scan's existing discard rule). Once 54/54 legal stickers are verified, Quick Scan opens Story 10.3 Scan Review (with Story 10.16 per-face retake and Paint mode). Fits a phone in portrait without scrolling and at 200% font on Lenovo 600 dp; TalkBack announces side-count milestones, cues, conflict face, and completion. |
| Recognize capture | Recognize | Hold yellow on top with one corner facing you, then turn the cube halfway for the opposite corner (the counter shows "1 / 2"). A non-yellow top or a wrong second corner re-prompts that hold ("Turn the cube so yellow is on top.", "Keep yellow on top.", "That looks like the same corner…"). Colors come from the saved Color Calibration; without one, calibration runs once first. A sticker read as uncertain counts as unseen, which can only widen the candidates. Results show only after both corners. |
| Beta label | Flagged features | Tapping it explains: "Works, but may misread colors in poor light. Your manual entry always works." |

## State Patterns

| State | Surface | Treatment |
| --- | --- | --- |
| First launch | Welcome | Three choices. Nothing is gated behind an account or the network. |
| Resume after kill (virtual) | Lesson | Restores silently to the Checkpoint. A one-line note reads "Picked up where you left off." |
| Another session started | Solve, while a Lesson is open | "Starting a solve pauses your lesson." It shows on the Solve tab next to *Scan my cube* and *Enter colors*, in the Editor and while solving, so a scan whose review goes straight to Solution Playback still shows it before the Lesson is paused (SCP 2026-11-19, #203). The Lesson resumes from its Checkpoint. A paused physical Lesson returns through *Confirm your cube*. |
| Resume after kill (physical) | Lesson | Shows the **Confirm your cube** card with three options: *It matches* / *Scan it* / *Enter it*. The cube frame shows EXPECTED (AD-10). |
| Content changed since save | Lesson | "This lesson was updated. We've restarted it with your cube as it was." |
| Expected only | Physical mode | Dashed EXPECTED frame. No certainty chip until there's evidence: the EXPECTED frame is itself the honest label (decision logged). Solution Playback has no frame label, so it shows no Certainty until there's evidence (SCP 2026-11-19). |
| Objective met, physical (expected) | Physical mode | "If your cube matches the picture, this step is done." The frame stays EXPECTED, a Quick Check is offered, and *Next* is enabled. No celebration. The Quick Check offer appears only when `CAMERA_SCAN` ≠ OFF (v1.0, flag-gated). |
| Mismatch ladder | Physical mode | The tutor asks for information in this order: (1) a Quick Check of the named faces; (2) "Which turn did you make?" with the Move key bar; (3) "Look at the front face — which corner is white?" with a one-face picker; (4) "Let's re-enter your cube" with a Guided Scan or the Editor. Each rung stays at ? *Unknown* until it yields evidence. |
| Adopt observed | Mismatch, Full observation | *Use my cube as it is now* replaces the Expected State and starts a Guided recovery. A Partial observation must first be completed by a Guided Scan or the Editor. |
| Verified | Physical mode | A ✓ *Verified* chip and a VERIFIED frame (Solution Playback shows the chip only, SCP 2026-11-19). It reverts to EXPECTED on the next *Done*. |
| Mismatch, unknown | Physical mode | Chip ? *Unknown*, with the next request in FR-9 order. Never a guessed cause. |
| Mismatch, likely | Physical mode | Chip ≈ *Likely*, with the candidate shown and *Scan all six faces* (Guided 6-face Scan) offered to confirm it. |
| Test me outcomes | Lesson | **On path** (the next Move of the current step's authored path): "That's the move — keep going." **Progress** (a different Move that still helps): "That works too — you're closer." **Neutral**: "That didn't change much. Try another turn." **Harmful** (disturbs a piece the current step preserves): the same as a diagnosed error, with a recovery prompt. |
| Error category copy | Lesson | **Unexpected orientation:** "Your cube is turned — hold it with {bottom} on the bottom, {front} in front." The colors name the Lesson's hold; for the shipped home-frame Lessons (white on top, green in front) this reads "hold it with yellow on the bottom, green in front." Offers *Show me how*, no undo. **Wrong piece:** "That moved the white-orange corner; we want white-red-green." **Wrong orientation:** "Right corner, but twisted — white should face {face}." The face is given in the Lesson's hold, e.g. "white should face up" for a first-layer corner. **Invalid entry:** see the Editor rows. |
| Stale scan result | Physical mode | Silently discarded (AD-3). If the Learner started it, re-offer: "Your cube changed while scanning — scan again?" |
| Completed layer disturbed | Lesson | The disturbed pieces get a violet outline. A recovery prompt appears, and the Checkpoint is kept. |
| Objective met (virtual or verified) | Lesson | A subtle check, a brief violet pulse on the affected pieces, and a light haptic. Notice text appears, and *Next* is enabled. |
| First solve | End of Stage 6 | The milestone moment. In Physical-cube Mode it shows only after a Quick Check or *Yes, it's solved*. In v1.0 it shows only after *Yes, it's solved*. |
| Stage mastered | When the Mastery Condition is met (usually in Practice) | The milestone moment, then the next Stage offered. |
| Skip-ahead check | Stage detail, entry-level picker | Passed: ◇ *Skipped*, and the next Stage unlocks (not mastered). Not passed: "Let's do this Stage together — it'll be quick." No penalty and no record against the Learner. |
| Entry level *(SCP 2026-11-20, #209)* | Welcome → *I can already solve it*, Learn → *Find my place* → *Choose my level*, Placement's solved outcome → *I can already solve it* (SCP 2026-11-24, #324) | "Where do you want to start?" with four choices: *I can solve the first two layers* (Stages 1–4 ◇ *Skipped*, *Last-layer orientation* unlocks); *I solve with the beginner method* (Stages 1–7 ◇ *Skipped*, *Faster last layer* unlocks); *I know 2-look OLL and PLL* (Stages 1–7 and both 2-look Stages ◇ *Skipped*, then the Library opens filtered to the full OLL set); *Check me with a scramble instead* (the existing whole-Course skip-ahead check). One line under the list: "Skipped Stages stay open, and nothing is marked mastered." Choosing never re-locks or downgrades a Stage already reached. With `PLACEMENT` on, *Not sure? Scan my cube* is offered at the bottom. Opening the picker writes nothing; only choosing a level does. Opened from Placement's solved outcome, Back returns to that outcome (SCP 2026-11-24, #324). |
| Placement outcomes | Placement | **Every outcome shows three lines (SCP 2026-11-20, #251):** a *reading* of the cube (`body`), a *reason* (`meta`), then the suggestion "Start at *Stage*?" with its options; TalkBack reads them in that order. Under the options one `meta` line: "The lesson sets up its own cube, so you can put yours aside." The outcomes are checked in this order. **Solved:** "Your cube is solved." / "Scramble it and scan again, or start Stage 1." (*Scan again* / *Start Stage 1* / *I can already solve it*). A solved cube says nothing about skill, so *I can already solve it* opens the Entry level picker; it grants no progress until a level is chosen there (SCP 2026-11-24, #324; the two lines are unchanged). **1–2 turns from solved** (any face, including a last-layer or first-layer turn): "Your cube is one turn from solved: {move, in words}." (or "two turns from solved: … then …") / "Finish it, then scramble it and scan again, or pick a Stage." (*Scan again* / *Choose another Stage*). **Nothing solved:** "Your cube is fully mixed. Nothing is solved yet." / "*Cube fundamentals* starts from here." **Non-white cross or layers:** "Your {color} cross is solved, nice work." (or "Your first two layers are solved, nice work. You started from {color}.") / "Our lessons start with the white face and leave yellow for the end, so to follow them, start with the white cross." — suggesting *First cross*, with *Choose another Stage*; when the first two layers are solved from another color, *Show me the last layer anyway* (suggests *Last-layer orientation*) is added. **Partly solved:** names what is done and how far the next Stage is, from the Stage's Objective progress: "Your white cross is done, and 2 of 4 first-layer corners are in place." / "*First-layer corners* picks up from here." **At the last layer:** "Your first two layers are solved. Only the last layer is left: the top needs orienting." (or "…the top is oriented; the pieces need moving into place."), adding "It looks like {case}." when one Case fits / "*Last-layer orientation* (or *Faster last layer*) covers this.", offering *Faster last layer* or Stage 5/6. Accepting *Faster last layer*, or choosing a 2-look Stage, marks Stages 1–7 *skipped by placement*. *Choose another Stage* lists Stages 1–7 and the 2-look Stages. *Enter colors instead* or a camera denial opens the Manual Cube Editor, and its accepted cube is placed like a scanned one (SCP 2026-11-18). A Placement scan that falls back opens that Editor blank, even after faces were captured (SCP 2026-11-24, #321: seeding is the Solve tab's only). No exclamation marks. |
| Re-placement *(SCP 2026-11-20, #252)* | Learn → *Find my place* → *Scan my cube* | A short note before the scan: "This keeps your progress. Stages you've finished stay as they are." Then the same scan, review and Placement outcomes. Accepting only adds progress: earlier Stages not yet finished become *Skipped by placement*, the suggested Stage unlocks, and nothing is re-locked or erased. If the suggested Stage is already open or finished: "You've already reached *Stage*." with *Open Stage* and no write. Hidden when `PLACEMENT` is OFF. |
| Solver working | Solve → Playback | An indeterminate progress bar, cancelable. This state is rarely visible. |
| Solver timeout / failure | Solve | "Couldn't find a solution in time. Your colors are still here — try again." No partial result is shown. |
| Solver preparing (first solve after the app starts) | Solve | "Preparing the solver." (SCP 2026-11-22 quick-scan-corners-and-solver-copy, PO Q-g, #262; replaces the SCP 2026-11-12 text. The line makes no claim about how long later solves take.) Shown on the first solve in each app process, while the solver loads its tables. Later solves in the same process skip it. Lessons are unaffected. |
| Solver tables generated (first use) | Solve | "Setting up the solver for first use. This can take a little while." Shown instead of the line above, only when no valid shipped or saved tables exist and they must be generated and saved. Lessons are unaffected. |
| Invalid entered state | Editor, Scan review | The offending pieces are outlined in red (status-invalid), with a plain explanation. Accept stays disabled. Errors that don't belong to one piece get their own lines: "One corner is twisted — check the corners you changed last.", "One edge is flipped.", "Two pieces are swapped — this can't happen on a real cube." **Specific messages (SCP 2026-11-20, #253), shared by the Editor and Scan review:** color counts name each color that is off: "Red: 10 (one too many) · Green: 8 (one missing)"; impossible pieces name the piece and its faces: "The corner between Up, Front and Right shows two greens."; suspect stickers carry the numbered markers Scan review uses, and their face tabs a warning dot. **Likely misreads (SCP 2026-11-24, #323; closes the #174 deferral):** Scan review names a sticker as a likely misread ("This sticker may be {color} — check it.") only when its measured color is close to the under-counted color: at most the suspect margin, 20 ΔE (PO 2026-11-24; twice the close-call rule), farther from that color's calibrated center than from the center of the color it was read as. A sticker that qualifies for several under-counted colors is suggested as the nearest one, and the list is ordered by margin. Clearly different colors are never suggested. This rule is Scan review's only; the Editor's markers are unchanged. With no close candidate, the count line and the piece problems still show, with no "may be" suggestion and no count-suspect marker. **Not entered yet:** in an Editor opened on the default solved cube, while some faces haven't been touched, no piece or count error shows; the line reads "3 faces left to enter: Right, Back, Down." An Editor opened on a caller's cube (physical mode, Placement fallback) counts every face as entered. An Editor seeded from a scan (Solve tab only; SCP 2026-11-24, #321) shows each sticker the scan did not read, and every sticker of a captured face whose rotation could not be worked out, as *unset*: the review net's blank well (no sticker color, dashed edge and "?"), read by TalkBack as "not set". Tap it, then a color, to set it. A face counts as entered once all nine of its stickers are set, and while any sticker is unset no piece or count error shows: the line names the faces left to enter. **Orientation:** see the Sticker review net row (Editor). |
| Camera permission: before the first request | Scan entry points, the first time the Learner opens a scan | A rationale card before the system dialog: "Cubentia uses the camera to read your cube's colors. It reads them on this device. Nothing is saved or sent." (PO-approved 2026-11-14, SCP 2026-11-14 Q10; revised by the PO on 2026-11-14 after UX review, OQ-UX-1 resolved). Two actions: *Continue* shows the system permission dialog; *Enter colors instead* opens the Manual Cube Editor without asking. The copy is a content string; Story 10.2 chooses its key. Shown only when `CAMERA_SCAN` ≠ OFF. TalkBack reads the rationale, then *Continue*, then *Enter colors instead*. |
| Camera permission denied | Scan entry points | From *Solve*, the Manual Cube Editor opens directly (FR-25), with a one-line note. Elsewhere: an explanation card and *Enter colors instead*, plus a Settings deep-link if the permission is permanently denied. |
| Feature flag OFF | Any | The entry point is hidden entirely, with no dead buttons. |
| Feature flag BETA | Quick Scan, Recognize, Placement, and every camera feature when `CAMERA_SCAN` is BETA | A BETA label. The manual route is always one tap away. Tooltip copy depends on the feature: for camera features "May misread colors in poor light. Manual entry always works."; for *Test me* "New — tell us if it feels off." Every feature uses one shared label component, as shipped for *Test me* (#141): the label sits inside the feature's own control, so tapping it selects the feature and shows the feature's note in a polite live region, and TalkBack reads "<feature>, BETA". |
| Cross-links to OFF features | Any | Links to a flagged-OFF feature are removed. For example, *Start from my cube* disappears from Recognize and Welcome when `PLACEMENT` is OFF. |
| Back to virtual | Physical mode → virtual | "The on-screen cube now matches what we expected on yours." Redo clears, and Undo stops here. |
| Recognize: not at LL | Recognize | "The first two layers aren't solved yet." Offers *Start from my cube*. |
| Recognize: ambiguous | Recognize | "This could be one of 3 cases" with tiles, plus *Scan another angle* (one more corner; the candidates are intersected again). |
| Recognize: no fit | Recognize | "I couldn't match this to a case. Try another angle or better light." Offers *Scan another angle* (both corners again, fresh) and *Browse the Library*. |
| Recognize: last layer solved | Recognize | "Your last layer is already solved." Offers *Back to Library*. |
| Library filter empty | Library | "No algorithms marked Learning yet." Links to 2-look. |
| Progress reset | Settings | A confirmation sheet names exactly what is erased. Calibration and preferences are untouched. |

*(SCP 2026-11-25 retire-v1-1: v1.1 is retired; v1.0 scope.)*

## Interaction Primitives

- **Cube gestures** (via a pure `GestureInterpreter`, AD-4):
  - A one-finger drag that **starts on a sticker** and runs along a row or column previews that layer's Move: the layer turns with the finger (up to a quarter turn), it is highlighted, and the Move name (for example `R'`) follows the finger. A middle row or column previews a slice (M, E, S).
  - A two-finger drag that **starts on the cube**, with both fingers moving the same way, previews the wide Move of the outer layer under the fingers (for example `r`).
  - **Lift to commit**, also when the finger has slid off the stage; the turn finishes from where the finger left it. To cancel, drag back to within the lock distance of where the drag began and lift: the layer snaps back and nothing changes.
  - A drag that starts while a Move is animating first finishes the animation at once, then previews.
  - A one- or two-finger drag **starting off the cube** is a View Rotation.
  - With TalkBack on, a two-finger drag reaches the app as a one-finger drag, so wide Moves come from "more" on the Move key bar.
  - Below the confidence threshold, a drag shows nothing and does nothing. It never guesses a turn.
- **Double-tap** on the stage resets the view; so does the **Reset view** action on the View rotated badge. The reset animates home, or jumps when reduce motion is on.
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
  - Scan review announces the selected sticker ("Up face, position 4, needs a color") and every set color ("Up face, position 4: orange").
  - *(SCP 2026-11-20)* Paint mode is announced on and off ("Painting red", "Painting off"). In the Editor, a completed face announces "Front face done. Right face next.", a resolved rotation "Up face turned to match", and an orientation question reads the face and hold ("Check the Up face: hold green toward you and look at the top."). Placement reads the reading, the reason, then the suggestion.
  - Move keys announce plain names, for example "R prime: right face counter-clockwise".
  - The previewed Move is announced by name while dragging; "Turn cancelled" (only when a locked drag is cancelled), "View rotated" and "View reset" are announced once.
- **Color-blind glyphs:** a Settings toggle shows letters on every sticker surface. It is offered on first launch and on the first scan.
- **Reduce motion:** when the system setting is on, or the app's override is:
  - Demonstrations step instead of animating;
  - the milestone burst is replaced by a static frame;
  - camera transitions snap;
  - the scan review's pulsing next-sticker ring is static, and the update cue shows only its ✓.
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
- **Scan layout (SCP 2026-11-15):** never scrolls.
  - **Compact (phone portrait):** the viewfinder takes the top ~45% of the height, with the grid, the face prompt and a 6-face progress strip on it. Below it sit the 3×3 net of the current face beside a 2×3 palette, then one primary action (*Capture* or *Next*).
  - **Medium and expanded (tablet, the Lenovo's 600 dp):** the viewfinder on the left, and the face net, palette and action on the right.
  - Rare or destructive controls (a quality override, *Reset labels*, *Recalibrate*) sit apart from the net and palette, behind an overflow or a separate row, and each asks for a confirm. An active override shows on the primary action.
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
6. The Quick Check returns, and the chip changes to ≈ *Likely*: "That turn probably moved the cross." A recovery prompt offers *Undo* or *Guide me back*, plus *Scan all 6 faces* to be sure. A Quick Check verifies only a match (SCP 2026-11-17). She picks *Guide me back*.
7. Sessions later, Stage 6 ends. "Your cube should be solved now. Check it with the camera, or tell me if it is." She runs a Quick Check, and it comes back ✓ *Verified*.
8. **Climax:** the milestone moment. "Solved. That's your first solve — on your own cube." Then *Practice on a new scramble* is offered.

**While `CAMERA_SCAN` is OFF:** steps 4–7 offer no Quick Check. At step 5 the tutor starts at rung 2 of the mismatch ladder, and at step 7 she taps *Yes, it's solved*. This is the failure path below, and it is the main path while the flag is OFF.

**Failure path:** she has no camera permission at step 5. The tutor works down the mismatch ladder instead: "Which turn did you make?" with the Move keys, then a one-face question. Certainty stays *Unknown* until she answers. At step 7, she taps *Yes, it's solved* instead of scanning.

### Flow 2 — Diego goes faster: 2-look LL (UJ-2)

1. Learn → *Faster last layer* has unlocked. Diego got there by passing the skip-ahead check from Welcome (*I can already solve it*, Flow 8); finishing the Course also unlocks it.
2. He opens a 2-look OLL Lesson. It shows the recognition diagram, the Demonstration, and *Guide me* on the virtual cube.
3. At the end of the Lesson: "Add these algorithms to Learning?" He accepts.
4. He practices a fresh Scramble's last layer using 2-look only.
5. **Climax:** "Last layer solved with 2-look, no hints." The mini-Course row now reads *Mastery: 1 of 2*. In Library he filters by *Learning* and sees his set out of 78.

### Flow 3 — Lucía just wants it solved (UJ-3)

*While `CAMERA_SCAN` is OFF: Solve → *Enter colors* replaces steps 1–2 (Manual Cube Editor); Quick Scan and the Quick Check in playback appear when their flags are raised (Story 10.11).*

1. Solve tab → **Scan my cube** → Quick Scan (BETA) → two corner photos.
2. The review net opens with 2 uncertain stickers pre-selected. She fixes them, and Accept enables.
3. Playback opens straight from the review: its *Accept* is her one and only confirmation, so no Editor or second net comes in between, after a Quick Scan or a Guided 6-face Scan alike. A scan of a cube that is already solved shows the Solve tab's already-solved message instead. The Manual Cube Editor is only the fallback: *Enter colors instead*, a scan that can't be made valid or complete, or a denied camera permission (SCP 2026-11-19, #203). When the fallback follows a scan that captured faces, the Editor opens with the scanned colors and the Learner's review fixes already in place, in the home frame, and only the stickers the scan did not read are left to fill, with the note "Your scan is here. Fill in the stickers it couldn't read."; a denied permission or a scan with no captured face opens the blank Editor with "No scan this time. Enter your cube's colors here." (SCP 2026-11-24, #321; Solve tab only). She taps *I'm following on my cube*, and each step advances on *Done*, with the MoveNote under each Move. A Quick Check is available at any step.
4. **Climax:** the final step solves her cube. Then a quiet offer: "Want to learn how to do this yourself?"

**Failure path:** glare spoils the bottom face. The app asks for a Guided Scan of that face only, with *Enter colors instead* always visible.

### Flow 4 — Marco starts from his own cube (UJ-4) *(v1.0, flag-gated)*

1. Welcome → **Start from my cube** → Guided 6-face Scan → review → Accept.
2. Placement: "Start at *First-layer corners*?" He can also tap *Choose another Stage*, but he accepts. Stages 1–2 show ◇ *Skipped by placement*.
3. In Physical mode he makes a mistake. A Quick Check gives ≈ *Likely*: "You probably turned R instead of R'." He takes the *Scan all 6 faces* offer, the Guided 6-face Scan gives ✓ *Verified*, and the fix is shown. Only a full scan verifies a mismatch (SCP 2026-11-17).
4. **Climax:** the layer is complete, Stage 3 is marked *Completed*, and Stages 1–2 remain open to visit.

### Flow 5 — Diego asks which OLL this is (UJ-5) *(v1.0, flag-gated)*

1. Library → **Recognize case** (BETA) → a photo of one top corner, then of the opposite corner, with yellow on top ("1 / 2").
2. **Climax:** "OLL 45 · T-shape". He taps it, and the Algorithm detail opens with its Demonstration playing.
3. He taps *Mark as Learning*, an explicit choice that is never automatic.

**Failure paths:**
- If the visible stickers fit several Cases, he sees the candidate tiles and *Scan another angle*.
- If no Case fits, he sees "I couldn't match this to a case. Try another angle or better light." with *Scan another angle* and *Browse the Library*.
- If the first two layers aren't solved, he's offered Placement (*Start from my cube*).

### Flow 6 — Laura's five-minute evaluation (UJ-6)

1. She installs the app and opens it, offline and with no sign-up. Welcome → *I'm new*.
2. She turns the cube with gestures, then rotates the view.
3. Library → an OLL tile → she scrubs the Demonstration back and forth.
4. Solve → *Scan my cube*, and she accepts the camera permission. *(v1.0, flag-gated; while `CAMERA_SCAN` is OFF she opens* Enter colors *and solves.)*
5. **Climax:** smooth 60 FPS turns, an honest scan review, and a clean, consistent visual system in both themes. Nothing crashes and nothing asks for an account.

### Flow 7 — Ana practices to mastery (FR-20)

1. Stage detail for *First cross* reads: Mastery 1 of 2 · 1 with reduced help. Ana taps *Practice this Stage*.
2. Independent practice deals a fresh Scramble. The step card shows only the Objective, and hints are available on request. This is the reduced assistance.
3. She solves the cross with one hint.
4. **Climax:** the Mastery Condition is met. The milestone moment plays, then "First cross mastered."
5. Her trend row on You shows hints per attempt falling across five attempts (3 → 2 → 2 → 1 → 1), drawn as dots with no score.

### Flow 8 — Diego skips ahead (FR-4, FR-17)

*(SCP 2026-11-20, #209: Diego picks his level instead of proving every Stage.)*

1. Welcome → *I can already solve it* → "Where do you want to start?"
2. He solves with the beginner method and wants to go faster, so he taps *I solve with the beginner method*.
3. **Climax:** Stages 1–7 show ◇ *Skipped*, and *Faster last layer* unlocks. None of them is marked mastered, and each stays open to revisit.
4. **Alternative:** he taps *Check me with a scramble instead* → "Solve this scramble your way. Hints are off." He solves it, with no time limit, and gets the same result.
5. **Failure path:** if he gives up the check, "Let's find where to start" offers the level list, Placement or Stage 1, with no penalty.

### Flow 10 — Marco finds his place again (FR-27, SCP 2026-11-20, #251, #252) *(v1.0, flag-gated)*

1. Weeks after finishing *First cross*, Marco practiced away from the app. Learn → *Find my place* → *Scan my cube*: "This keeps your progress. Stages you've finished stay as they are."
2. Guided 6-face Scan → review → Accept.
3. "Your white cross is done, and 3 of 4 first-layer corners are in place." / "*First-layer corners* picks up from here." / "Start at *First-layer corners*?"
4. **Climax:** he accepts. *First cross* stays *Completed*, *First-layer corners* is open, and nothing he did before is lost.

### Flow 11 — Ana drills an Algorithm on her own cube (FR-18, SCP 2026-11-20, #208)

1. Library → *OLL 37* → *I'm doing it on my cube*. "Set it up from solved, or does your cube already show this case?" She picks *Set it up from solved*.
2. Playback opens in Physical-cube Mode on the 2D net, with a *Set-up* segment then the *Algorithm* segment. Each turn advances on *Done*.
3. Halfway through the Algorithm she is unsure, and taps *Quick Check* (camera flag on). ✓ *Verified*, and she carries on.
4. **Climax:** the last *Done*; her last layer is solved. *Again* replays from the set-up.

### Flow 9 — Calibration and resets (FR-19, FR-24)

*Steps 1–3 (calibration) are v1.0, flag-gated. Step 4 (resets) ships while `CAMERA_SCAN` is OFF, without a calibration reset.*

1. On Lucía's stickerless cube the scan flags many uncertain stickers, and the scan offers "Colors look off — calibrate?"
2. Calibration shows each of the six centers in turn: "Show the white center." The app reads the ring around the logo, not the logo. When her white center's print fills too much of it, the prompt changes to "Put a plain white sticker in the middle square." She sees each swatch, confirms it, then taps *Save*.
3. **Climax:** her rescan reads every sticker confidently.
4. Later, in Settings → *Reset progress*, the sheet lists exactly what is erased: Stages, attempts and Algorithm Statuses. Any Lesson or practice in progress also ends, so the Continue card is cleared. Calibration and preferences have their own resets.

*(SCP 2026-11-25 retire-v1-1: v1.1 is retired; v1.0 scope.)*

## Surface Coverage

Every surface in the Information Architecture is reached by at least one Key Flow:

| Surface | Flows |
| --- | --- |
| Welcome | 1, 4, 6, 8 |
| Learn | 1, 2, 4, 10 |
| Stage detail | 7, 8 |
| Lesson | 1, 2, 4 |
| Physical-cube Mode | 1, 4 |
| Independent practice | 7 |
| Solve | 3, 6 |
| Scan | 1, 3, 4, 5, 6, 9 |
| Manual Cube Editor | failure paths of 1 and 3 |
| Solution Playback | 3 |
| Library | 2, 5, 6 |
| Algorithm detail | 2, 5, 6, 11 |
| Algorithm practice | 11 |
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
| `mockups/key-physical-and-scan.html` | **v1.0, flag-gated reference.** Physical-cube Mode after a Guided 6-face Scan (Verified mismatch, recovery prompt; a Quick Check verifies only a match, SCP 2026-11-17) with an inset of the Unknown state; Quick Scan sticker review with glyphs on |
| `mockups/key-library.html` | Library (dark) and Algorithm detail with the playback transport |

The mockups predate the finalize edits, so they don't show a few of them: the cube-stage toolbar (Undo / Redo / Reset), the Assistance line and the SEEN strip. The tablet mockup also shows the virtual Objective-met state; the physical variant uses the "If your cube matches…" wording. [NOTE FOR UX: re-render them if a visual reference is needed for these.]

*(SCP 2026-11-25 retire-v1-1: v1.1 is retired; v1.0 scope.)*

## Open Questions

- **OQ-UX-1 — RESOLVED 2026-11-14 (PO hebelmx).** Decision: adopt the device-neutral copy "Cubentia uses the camera to read your cube's colors. It reads them on this device. Nothing is saved or sent." in State Patterns, replacing "… Pictures stay on this phone. They are never saved or sent." PO reasons: reference devices include tablets; "pictures stay" implied photos are taken, while frames are processed in memory only. Original review follows. UX review of the approved camera-rationale copy (SCP 2026-11-14 Q10). Suggested alternative: "Cubentia uses the camera to read your cube's colors. It reads them on this device. Nothing is saved or sent." Reasons: (1) the minimum and compatibility reference devices are tablets, so "this phone" is inaccurate for part of the audience; (2) "Pictures stay on this phone" followed by "never saved" reads as a contradiction and suggests photos are taken, while frames are only processed in memory (PRD §4.8 Privacy); (3) the alternative keeps the Voice and Tone rules (short declarative sentences, calm, no exclamation) and the same three claims. Accessibility is fine either way: plain language, under 25 words, read in one TalkBack pass.
