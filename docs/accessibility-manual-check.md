# Accessibility device check

Story 12.1 [AC3](../_bmad-output/implementation-artifacts/spec-12-1-accessibility-audit-and-fixes.md#tasks--acceptance) requires this physical pass after the PR opens. Record actual results below; automated checks do not establish a device pass.

Current integration limitation: AppShell’s Solve tab currently opens the free cube without an Enter colors route; the editor is covered directly in the automated audit. Record that route as unavailable in Flows 3 and 6 until integration ships. This story does not wire SolutionPlayback into AppShell.

Setup: use the reference phone and tablet. Enable TalkBack; repeat with Switch Access and two switches (next/select). Set font size to 200%, enable reduce motion, and repeat in light and dark themes. Work offline.

For every flow below, reach every step by TalkBack swipe navigation and switch scanning. Check each control's spoken label and activation; focus order is frame label → cube description → Move key bar → lesson panel → mode switcher. Check Certainty changes are announced, Read faces reads the actual face colors, Move keys speak plain names, and View Rotation works through buttons. Confirm text wraps, controls remain reachable, color-blind glyphs remain legible, and reduce motion removes moving demonstrations/bursts. No shake, red flash, countdown, streak, points, badge, notification, carousel, or automatic advance past a Notice.

- **Flow 1, first physical solve:** Welcome → I'm new → fundamentals → First cross → Physical mode → It matches → Done. Trigger mismatch and use the ladder starting at rung 2, including one-face answers and recovery. Finish with Yes, it's solved and the first-solve milestone. No Quick Check in v1.0.
- **Flow 2, 2-look:** unlock Faster last layer, open a Lesson, read its recognition diagram, play its Demonstration and use Guide me. Accept/decline Add to Learning; complete fresh last-layer practice and inspect mastery and Library status filters.
- **Flow 3, solve:** Solve → Enter colors; select stickers by spoken face/position, paint with labelled swatches, inspect invalid states, Accept valid colors. Check available playback controls where exposed; record any unavailable route rather than assuming it passed.
- **Flow 6, evaluation:** fresh offline Welcome → I'm new; turn via Move keys and rotate via buttons; open a Library OLL and scrub its Demonstration; enter colors from Solve where available. Check both themes without account prompts.
- **Flow 7, mastery practice:** Stage detail → Practice this Stage; request a hint, finish the Objective, inspect the mastery milestone and the You trend's spoken description.
- **Flow 8, skip ahead:** Welcome → I can already solve it; complete the untimed scramble. Check Skipped labels and Faster last layer unlock. Repeat giving up and return to Stage 1 without a penalty.
- **Flow 9, step 4 only, resets:** Settings → Reset progress; read what is erased and that live practice ends. Test cancel and confirm; verify Continue clears, then cold start. Check preference reset separately.

Flows 4–5 and Flow 9 steps 1–3 require v1.1 camera functionality and are outside this v1.0 pass.

| Flow | Device / Android | TalkBack pass/fail | Two-switch pass/fail | Theme / font / reduce motion | Notes |
| --- | --- | --- | --- | --- | --- |
| 1 | | | | | |
| 2 | | | | | |
| 3 | | | | | |
| 6 | | | | | |
| 7 | | | | | |
| 8 | | | | | |
| 9 step 4 | | | | | |
