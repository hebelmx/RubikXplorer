# Release device session: evidence

Session 2026-11-23, main `dfc5c3d6`, worktree `RubikXplorer-device-session-2` (branch `docs/device-session-2026-11`). Sitting order changed by the PO: D (Story 10.20 recording, Lenovo done, Galaxy pending) → Block 1 + Block 5 → rest. Agent drove both devices through adb (taps, UI dumps, screenshots, logcat); PO watched.

## Devices and builds

| Device | Android | Build under test | Notes |
|---|---|---|---|
| Lenovo TB128XU (USB `HA1S28MM`) | 13 | benchmark (release code, R8, debug-signed), `app-benchmark.apk` from `dfc5c3d6` | app data cleared by the Block 5 run (test APKs uninstall the app); files backed up first |
| Galaxy S24 FE (SM-S721U1, wireless adb) | 17 | benchmark, fresh install (uninstall + install, then `pm clear` before 1.1) | screen timeout and lock delay set to 24 h for the session (was 30 s / never-set); restore at the end |

Benchmark build checks: not debuggable (`pkgFlags=[HAS_CODE ALLOW_CLEAR_USER_DATA]`); `aapt2 dump permissions` lists only the app's own `DYNAMIC_RECEIVER_NOT_EXPORTED_PERMISSION`.

## Block 1: Release build smoke (blocks #127)

| # | Result | Evidence |
|---|---|---|
| 1.1 App starts, content decodes under R8 | ✅ PASS (Galaxy) | Welcome after `pm clear` (`shots/1-1-galaxy-welcome-fresh.png`); Learn lists 7 Stages, 23 Lessons (3/5/4/4/4/3 + practice Stage 7), equal to `core/domain/src/main/resources/content/stages/*.json`. The run sheet's "12 Lessons" is stale. No crash buffer entries. |
| 1.2 A Stage 2 Lesson completes, progress kept after restart | ✅ PASS | Lenovo: Stage 1 *I already know this* check (scramble fixed with F) → Stage 2 *First cross* Lesson 1, *Guide me*, F2 → "Objective met" → ticked; after `am force-stop` + relaunch Stage 1 *Skipped*, Stage 2 *In progress*, Lesson 1 ticked, Lesson 2 unlocked. Galaxy portrait (1080 px @ 450 dpi = 384 dp, narrower than 411 dp): teaching-mode switcher fits inside the margins (`shots/1-2-galaxy-mode-switcher.png`); *Let me try* wraps to two lines. |
| 1.3 Works offline on release code | ✅ PASS (Lenovo, airplane mode, `Active default network: none`) | Learn, a Lesson check, Stage 2 Lesson, Library (OLL cards draw), *Enter colors* → one-move state → Accept → Solution Playback "1 move", U' plays to solved. No network message, no hang. Run on the Lenovo because the Galaxy's adb is wireless. |
| 1.4 No camera in release | ✅ PASS (both) | No Android permission requested at all (`dumpsys package`: none; `aapt2`: none but the app's own); Solve shows only *Enter colors*. |

S3 notes from Block 1: (a) Solution Playback's 3D view is blank for ~1–3 s on the Lenovo before the cube draws; (b) after a restart, Learn's *Continue* card points to the Lesson already finished instead of the next one; (c) the run sheet's Lesson count needs updating.

## Block 5: Automated device tests

| # | Result | Evidence |
|---|---|---|
| 5.1 `Cube3DRenderSyncTest` (Lenovo) | ❌ 1 of 4 fails | `tenThousandRandomStepsKeepTheCubeEqualToTheVirtualState` times out at 300 s twice (first run and isolated re-run). `physicalSwitchKeepsTheStepControlsAndQuickCheckEvidence` failed once ("No compose hierarchies found") and passed in isolation: flaky. The other two pass. JUnit: `block5/5-1-2-lenovo-junit.xml`, `block5/5-1-rerun-lenovo-junit.xml`. Galaxy not run yet. |
| 5.2 `EndToEndSyncVerificationTest` (Lenovo) | ✅ PASS | same JUnit file |
| 5.3–5.5 | ⏭ NOT RUN | |

## Block 6 (partial)

| # | Result | Evidence |
|---|---|---|
| 6.1 #276 Quick Scan reopens at a stale step | ✅ PASS (navigation), PO review pending | Lenovo debug: 4 Quick Scan openings 10:25–10:27, every one `quickScan viewfinder hold=1`, first frame 1.2 s (cold) then 0.41–0.59 s; frames keep streaming after each reopen (no late unbind seen, indirect: `CubentiaScanDiag` logs no bind/unbind lines). *Face 1 of 6* never shown on reopen. PO confirmed the face-by-face header does read *Face n of 6* on both devices (top right; far from *Close* on the tablet). Separate observation: from Welcome → *Start from my cube*, *Close* in the face-by-face scan with 2 faces captured cancelled without a *Discard* confirmation (check in 6.2 / #198). |

## Block 8: Quick looks

| # | Result | Evidence |
|---|---|---|
| 8.1 #275 Lenovo Settings labels | ✅ PASS | `shots/8-1-lenovo-settings.png`; S3: tight start margin, radio labels touch their circles |
| 8.2 #201 Playback header at 200% font (Galaxy) | ✅ PASS | `shots/8-2-galaxy-playback-200pct.png`: "1 move" header and "0 / 1" counter fit; real 1-move solution. S3: 3D view shrinks to ~¼ width at 200% |
| 8.4 #205 Placement status bar light/dark (Galaxy) | ❌ FAIL (light) | Dark ✅ `shots/8-4-galaxy-placement-result-dark.png`; light: status-bar icons stay white on light grey, unreadable (`shots/8-4-galaxy-placement-result-light.png`). Theme switched with `cmd uimode night` (app theme = System), restored. |
| 8.5 #200 Physical-cube playback + TalkBack (Galaxy portrait) | ✅ PASS (overlap); certainty chip not shown until a Quick Check | `shots/8-5-galaxy-physical-playback-talkback.png`; reading order Close → title → styles → 3D toggle → Quick Check → counter → step → Move strip → Step back → Done → Physical mode. TalkBack could not be toggled by `settings put` (Android 17 refuses); PO switched it on, agent stopped it with `am force-stop`. |
| 8.6 Rotate while Placement saves (Galaxy) | ✅ PASS | Run 1 (fully mixed cube): rotation on result screen kept state; *Open Stage* + rotate → Stage 1, 0 progress events (nothing to skip). Run 2 (first layer + 2 middle edges): "Start at Middle layer?" → *Start* + rotate → Learn, Stage 4 *Middle layer*; `progress_event`: 3 × `STAGE_SKIPPED_BY_PLACEMENT` (stages 1–3) + 1 × `STAGE_UNLOCKED` (stage 4), one timestamp, no duplicates (read from a copy of the debug app's DB via `run-as`). |
| 8.3 #203 Scan review → Accept; Editor fallback; pause note | ✅ PASS | Accept (11:46:33 `outcome=ACCEPTED`) → straight to Solution Playback "25 moves" (`shots/8-3b-galaxy-review.png` → `shots/8-3b-galaxy-after-accept.png`); Close on review → `FELL_BACK_TO_EDITOR`, Editor in home frame (white up, bars correct) with "Starting a solve pauses your lesson." (`shots/8-3-galaxy-done.png`). PO: the fallback Editor drops the valid scanned colours (starts empty), see issue 8. |
| 8.5 addendum | ✅ | Certainty chip "✓ Verified" shown beside EXPECTED in a Lesson's Physical mode without overlap (`shots/8-7-galaxy-quickcheck-result.png`). |
| 8.7 Full Quick Check after one wrong move | ⏭ moved to Block 2.5–2.6 | Attempt read a cube 6 turns from EXPECTED (`U2 R F D2 L B`, 0 of 20 pieces match), so "I can't tell what changed" was correct; the single-wrong-move setup was not reached. PO: run it with 2.5–2.6. |
| 8.8 #174 Suspects when a colour count is off | ❌ FAIL | Two hand-painted errors (Up 3 orange→blue; Up 9 white→blue). Both times Problem 1 (impossible corner) outlined the changed piece correctly, but the "likely misreads" ranking put a genuine blue read (Up 2) first: "This sticker may be orange/white — check it" (`shots/8-8-galaxy-review-counts-off.png`, `shots/8-8b-galaxy-review-white-to-blue.png`). Clearly different colours are suggested, against the PO decision of 2026-11-20. Caveat: hand-painted, not a camera misread; hand-set stickers are excluded from the ranking. → issue 9. |
| 8.9 Non-home orientation scan | ✅ not reproducible | The guided scan prompts by centre + top neighbour, so it normalises orientation: every accepted scan today opened "Cube, White on top, Green in front"; the Editor fallback opened in the home frame. PO agreed; propose closing `issue-178-deferred-scrambled-non-home-scan-centers` after review. |

### Placement runs (evidence for 6.7 #251, Galaxy, PO confirmed every reading)

| Run | Cube | Placement said | Saved after Start/Open Stage |
|---|---|---|---|
| 1 | fully mixed | "Nothing is solved yet" → Stage 1 | nothing |
| 2 | first layer + 2 middle edges | "Start at Middle layer?" | stages 1–3 skipped, 4 unlocked |
| 3 | F2L, top not oriented | "It looks like OLL 51" → Last-layer orientation / Faster last layer | stage 4 skipped, 5 unlocked |
| 4 | F2L, top oriented | "It looks like Y perm" → Last-layer completion | (not checked) |
| 5 | solved | "Your cube is solved. Scramble it and scan again, or start Stage 1." | – |

Doubt for the PO panel: a solved-cube scan offers only Stage 1.

## Block 5 (continued)

- Galaxy: `Cube3DRenderSyncTest` 4/4 and `EndToEndSyncVerificationTest` ✅ (`block5/5-1-2-galaxy-junit.xml`); 10,000 steps in 183 s.
- Lenovo 10,000 steps with a temporary local 15-minute timeout (never committed, file restored): ✅ 464.6 s (`block5/5-1-10k-15min-lenovo-junit.xml`). The 300 s limit is too tight for the minimum device, not a sync fault. PO: tier the limit by device speed, 2–3 tiers max.

## Issues to open at the break (drafts pending PO review)

1. Face-by-face scan: move *Close* next to the other controls (hard to find on the tablet).
2. `Cube3DRenderSyncTest` 10k-step timeout: 2–3 device tiers.
3. Corner keypoint labelling script outside the app (Story 10.20 F1).
4. Fixture recorder: settle cap on hold 1, dialog carries light/direction, *Use corners* silently saves keypoints only (10.20 F2–F4).
5. Lenovo touch sometimes unresponsive (also outside the app; lock/unlock restores); track, bug only if it appears app-specific.
6. #205 light-theme status bar (if the PO wants a new issue rather than reopening/commenting #205).
7. Physical-mode switch hard to discover in dark theme (off track dark grey on dark); the label does not toggle it — make the whole row toggle.
8. Scan fallback to the Editor should keep the scanned colours so the user only corrects the rest.

Doubts for a short panel at the end of the block: solved-cube Placement → only Stage 1; Close on a face-by-face scan with captured faces has no Discard confirmation (6.2 / #198); Solve-tab physical playback vs Lesson physical mode differ in Quick Check flow.
9. Scan review "likely misreads" (#174 follow-up): list only stickers whose measured colour is near the under-count colour; if none, give no "may be X" suggestion.

## Issues opened 2026-11-23 (after an adversarial review, PO approved)

#313 Close placement · #314 10k-step timeout tiers · #315 corner labelling script · #316 recorder settle cap · #317 recorder dialog carry-over · #318 recorder Use corners keypoints-only · #319 Lenovo touch (watch) · #320 Physical mode switch discoverability · #321 Editor fallback drops scanned colours · #322 Placement review status bar in light theme (re #205) · #323 likely-misread suggestions (re #174).

## Issue comments posted 2026-11-23

#276 verified and **closed** (indirect unbind evidence, accepted by the PO). Verification comments on the already-closed #275, #201, #203. #200: partly verified; the end of a Solution Playback after a verifying Quick Check is still to be seen (with Block 2).

6.3 #197: ✅ closed on the PO's judgment (capture feedback always clear on both devices today); TalkBack announcement not checked.
