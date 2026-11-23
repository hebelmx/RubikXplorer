# Release device session: run sheet

**Bottom line:** one sitting (or three short ones) on the two reference devices that settles everything still waiting on hardware before the first Play release: the release build smoke, the learning flows from Epics 14 and 15, gestures and 3D, the merged fixes behind 9 open GitHub issues, and the Quick Scan timings. Blocks are in priority order: blocking and high-impact work first, cosmetic checks last. If the session has to stop early, stop at the end of a block.

- **Story:** [Verification Story: Release device session](spec-release-device-session.md) (detailed steps for the issue checks are there, sections 1–8).
- **Who:** the PO holds the devices and the cube; an agent (Claude Code) drives: it installs, says the next step in plain words, takes screenshots, pulls logs, reads them and records results.
- **Devices:** **Lenovo** = Lenovo Tab M10 Plus 3rd Gen (TB128XU, USB serial `HA1S28MM`; minimum device; no flash, no vibration motor). **Galaxy** = Galaxy S24 FE (SM-S721U1; mid device; wireless adb, find it with `adb mdns services` because the port changes). Older items that name the Galaxy Tab S7 FE run on the Lenovo, which replaced it as the minimum device on 2026-11-12.
- **Cubes:** `stka` (Rubik's, stickered, logo centre) and `stla` (GAN, stickerless). `stkb`/`stlb` only where a row says so.
- **Results go in:** `docs/evidence/release-device-session/README.md` (one record per check, template at the end of the story). Tick the **Result** column here as you go.

**Result codes:** ✅ PASS · ❌ FAIL · ⛔ BLOCKED (could not run) · ⏭ NOT RUN. Nothing counts as a pass unless it was actually exercised.

---

## 1. How the agent works with you

### Communication

| Rule | What it means in practice |
|---|---|
| Bottom line first | Every message starts with one or two plain sentences: what just happened and what you do next. Details after. |
| Plain names | IDs always come with their meaning: "Story 14.2 (practice an algorithm on your real cube)", "#276 (Quick Scan reopens at the wrong step)". |
| One step at a time | The agent tells you exactly where to tap from the screen you are on ("Solve tab → *Quick Scan*"). If you are lost, say "where am I?" and it screenshots and re-orients you. |
| You say "done" | After each "done" the agent takes an adb screenshot, pulls the logs (`CubentiaScanDiag` for camera work), reads them, and only then records the result or proposes a cause. It does not guess from the screen alone. |
| Never interrupt a test | The agent never reinstalls, restarts the app or toggles a setting while you are mid-test; it asks first. |
| Git stays out of the way | Branch names and commits are mentioned once at the end, not during the session. |

### Escalation

| Severity | Example | What the agent does |
|---|---|---|
| **S1 Blocker** | Crash, lost progress, cannot finish a core flow (Lesson, solve, practice), release build will not start | Stops the block. Records ❌ with screenshot + logs. Asks you one decision with a recommendation: *A [Recommended] skip to the next block and open a fix issue now*, *B try a workaround*, *C stop the session*. Opens the GitHub issue with you before moving on. |
| **S2 Major** | Wrong result shown to the Learner, TalkBack misses key information, a check that closes an issue fails | Records ❌ with evidence, finishes the rest of the block, then asks whether to open an issue or attach it to an existing one. |
| **S3 Minor** | Layout touches an edge at 200% font, a slow but working step | Records it, keeps going, lists it at the end for one batched decision. |
| **Doubt** | "Is this what it should do?" — the expected behaviour is unclear | The agent quotes what the story or issue promised. If still unclear, it runs a short party-mode panel at the end of the block and gives you a recommendation. |
| **New idea / out of scope** | "It would be nice if…" | Party-mode panel at a break, then a GitHub issue. The current block does not grow. |

### Hard rules for the agent

- No code changes during the session. Fixes go to issues or stories and are built later in their own worktree.
- Install on the devices only for this session; the approval does not carry over.
- Keep no camera frames. Screenshots of app screens and diagnostic logs are fine.
- A GitHub issue is closed only after you review its evidence at the end. Merge, green tests or one good run are not enough on their own.
- Only one person drives a device at a time. The agent may run automated tests on the device you are not holding (see Block 5), never on the one in your hand.
- Another session is building Story 10.20 (recording corner fixtures on the devices). Before starting, the agent checks no other session is using adb; if one is, agree who has which device.

---

## 2. Setup (agent, about 20 min; you only plug in and unlock)

| # | Step | Done |
|---|---|---|
| S1 | Confirm no other Claude session is using the devices (`adb devices`, ask in the other session if needed). | ☐ |
| S2 | Pull `main`, note the commit SHA. Build the **main-app debug** build with the camera flags raised in debug (`CAMERA_SCAN`, `QUICK_SCAN`, `PLACEMENT`, `CASE_RECOGNITION`) and the **benchmark** build (release code, R8, debug-signed). | ☐ |
| S3 | Install debug on both devices; on the Lenovo uninstall first if the signature differs. Record Android version, model and flag levels per device. | ☐ |
| S4 | Galaxy: connect wireless adb (`adb mdns services`). Lenovo: USB `HA1S28MM`. | ☐ |
| S5 | You prepare: both cubes, a plain light matte surface, a dark glossy surface with a visible reflection, steady indoor light, a lamp. Battery above 50%. | ☐ |
| S6 | Colour calibration saved for the cube you start with on each device (the agent tells you when to recalibrate after a cube change; see #173). | ☐ |
| S7 | Agent creates `docs/evidence/release-device-session/README.md` with the device header and empty records. | ☐ |

Shortcuts the agent uses (you don't need them): TalkBack and 200% font are set through Settings with you; the shadow sticker reader toggles with `adb shell am broadcast -p com.exxerpro.cubentia -a dev.cubentia.debug.SHADOW_READER --ez on true|false`.

---

## 3. Priority overview

| Block | What | Why it matters / what it unblocks | Devices | Your time | Blocking? |
|---|---|---|---|---|---|
| **1** | Release build smoke | The first Play tag (#127, first real `release.yml` run) should not ship an R8-broken build. | Galaxy (+ Lenovo if time) | 20 min | **Yes: blocks #127** |
| **2** | Learning on the real cube (Epic 14) | Entry level, algorithm practice and Physical-cube Lessons are v1.0 core; merged without a device check. | Both | 60 min | **Yes: retro says before release Stories 12.3–12.5** |
| **3** | Solve styles and timing (Epic 15) | A cold first solve may overrun its 3 s limit on the minimum device; 2-look / full OLL+PLL playback never ran on a device. | Lenovo (timing), Galaxy (playback) | 30 min | **Yes: same batched session as Block 2** |
| **4** | Gestures, 3D and move pictures | Turning the cube by drag is how Learners play Lessons; no unintended Move, frame rate on the Lenovo. | Both | 30 min | High impact |
| **5** | Automated device tests (agent only) | Render-sync, end-to-end sync and frame benchmarks that only run on hardware; also lets Story 1.11 delete the SceneView spike. | The device you are not holding | 0 min (runs during Blocks 2–4) | High impact |
| **6** | Merged fixes behind 9 GitHub issues | Close #195–#198, #249, #251, #253, #276 with evidence, or keep them open with a next step. | Both | 90 min | Medium (Quick Scan ships as BETA) |
| **7** | Quick Scan timing and retakes | Finishes Story 10.22's acceptance (#300) and the 10.23 shadow-reader timing gate in the same scans; retake and re-ask checks. | Both | 60 min | Medium (feeds the BETA call) |
| **8** | Quick looks (cosmetic and low risk) | Fits at 200% font, contrast, small flows. Each is a one-minute look. | Galaxy mostly | 20 min | No |

Suggested sittings: **A** = Setup + Blocks 1–4 (Block 5 in the background) · **B** = Block 6 · **C** = Blocks 7–8.

---

## Block 1: Release build smoke (blocks #127)

Benchmark build = the same code and shrinking as release, signed with the debug key. Install it fresh; airplane mode on for 1.3.

| # | Check | How | Pass when | Affects | Result |
|---|---|---|---|---|---|
| 1.1 | App starts, content decodes under R8 | Fresh install of the benchmark build, open the app | Welcome shows; no crash; Learn lists all 12 Lessons | `epic-2-retro-item-6` | ☐ |
| 1.2 | A Stage 2 Lesson completes | Learn → a Stage 2 Lesson → *Guide me* → finish it | Lesson completes, progress saved after app restart; Stage switcher fits at 411 dp (Galaxy portrait) | `epic-2-retro-item-6` | ☐ |
| 1.3 | Works offline on release code | Airplane mode on: Course, Library, cube entry (*Enter colors*), solve and playback | Every flow works with no network message or hang | `epic-12-deferred-12.3-release-build-offline-run` | ☐ |
| 1.4 | No camera in release | Settings → Apps → Cubentia → Permissions | No camera permission listed; no camera entry points visible | release gate (flags OFF) | ☐ |

If 1.1 or 1.2 fails: **S1**, stop and decide before going further; the release tag waits for the fix.

---

## Block 2: Learning on the real cube (Epic 14)

Use the debug build. For each row do the Lenovo first, then the Galaxy. TalkBack and 200% font pass once per device at the end of the block (rows 2.9–2.10).

| # | Check | How | Pass when | Affects | Result |
|---|---|---|---|---|---|
| 2.1 | Entry level picker opens | Welcome → *I can already solve it*; and Learn → *Find my place* | Both open the level picker | 14.1 (choose my entry level) | ☐ |
| 2.2 | Each level skips the right Stages | Pick each level in turn (reset progress between) | Earlier Stages show the *Skipped* badge, nothing else is skipped | 14.1 | ☐ |
| 2.3 | 2-look entry | Pick the 2-look level | Library opens on the removable OLL chip; the *Faster last layer* badge does not say *In progress* when both 2-look Stages are skipped | 14.1, `epic-14-deferred-14.1-two-look-badge-ignores-skips` | ☐ |
| 2.4 | Practice an OLL and a PLL on the real cube | Library → an OLL → *Practice on my cube*, both ways: *set up from solved* and *already shows this case*; then *Again*; then close | Steps match the cube; *Again* restarts; close returns to the Algorithm detail; no flash of the virtual player before Physical-cube Mode | 14.2 (practice an algorithm), `epic-14-deferred-14.2-practice-start-virtual-frame` | ☐ |
| 2.5 | Quick Check mid-Algorithm | During 2.4 tap Quick Check halfway | Capture works and the result matches the cube | 14.2 | ☐ |
| 2.6 | Lessons in Physical-cube Mode with Quick Check | A Course Lesson and a 2-look Lesson in Physical-cube Mode; Quick Check mid-step, at a step boundary, and after a deliberate wrong move | Plain icon mid-step; accent icon at a boundary and after a mismatch; light and dark theme | 14.3 (every Lesson step on my real cube) | ☐ |
| 2.7 | Practice exit paths | Start a Stage practice, then open a Library practice (warning shows), finish it; also *I did something else* inside a practice | The pause warning shows; note whether the Stage practice is restored or lost (known gap) | `epic-14-retro-item-231`, `epic-14-deferred-14.3-suspended-practice-not-restored`, `epic-14-retro-item-226` | ☐ |
| 2.8 | Scramble check with a live Lesson | With a Lesson open: Learn picker → *Check me with a scramble instead* | Note whether the open Lesson is warned about or silently lost (known gap R1) | `epic-14-retro-item-224` | ☐ |
| 2.9 | Camera off variant | (Agent installs a build with `CAMERA_SCAN` OFF, after asking) Lesson in Physical-cube Mode | Only *Done* is offered, no Quick Check | 14.3 | ☐ |
| 2.10 | TalkBack + 200% font | Repeat 2.1, 2.4 and 2.6 briefly with TalkBack, then at 200% font on the Lenovo (600 dp) | TalkBack reads the picker and says "Quick Check"; nothing clipped at 200% | 14.1–14.3 | ☐ |

2.7 and 2.8 are known gaps; the point is to see how bad they feel, so you can decide whether they block v1.0.

---

## Block 3: Solve styles and timing (Epic 15)

The agent measures timing from logs; you only tap.

| # | Check | How | Pass when | Affects | Result |
|---|---|---|---|---|---|
| 3.1 | Cold first solve on the Lenovo | Force-stop the app, open Solve → *Enter colors* → a scrambled cube → solve with each style | No timeout message; the agent records the time of the first (cold) solve | `epic-15-deferred-15.1-cold-start-table-build-timeout` | ☐ |
| 3.2 | Solve timing per style | 10 solves on the Lenovo across the three styles; one chip switch while a cold build is running | Agent records median, p95, memory and failures per style (NFR-3) | `epic-15-retro-item-208` | ☐ |
| 3.3 | 2-look and full OLL+PLL playback on the real cube | From an F2L-solved cube: play the 2-look and the full OLL+PLL solution in Physical-cube Mode, with Done, Back and Quick Check | Rotations (z2), wide and slice moves show correctly and the cube ends solved | `epic-15-deferred-15.3-two-look-full-physical-playback-test` | ☐ |
| 3.4 | Style chips after rotate | During playback rotate the device, then tap a style chip | Chips still work | `epic-15-retro-item-214` | ☐ |

---

## Block 4: Gestures, 3D and move pictures

| # | Check | How | Pass when | Affects | Result |
|---|---|---|---|---|---|
| 4.1 | Play Lessons by gesture | Stage 1 and Stage 2 Lessons, turning the cube by drag only, on both devices | No unintended Move; a drag cancelled mid-way does not commit; the ghost-layer outline is visible without a pulsing tint | 12.16 (see the turn while dragging), 12.17 (view rotated / reset), #206 | ☐ |
| 4.2 | Drag frame time | Agent logs frame time during 4.1 on the Lenovo | ≥ 30 FPS on the Lenovo (12.2 baseline) | 12.16, NFR-2 | ☐ |
| 4.3 | Arrows over every colour | Look at the Move arrow and the ghost arrow on each sticker colour | Arrow clear on every colour; the notation label stays readable and clear of the arrowhead | #260 | ☐ |
| 4.4 | Cube angle and fit | Virtual Lesson cube on the Galaxy at normal and 200% font | Cube settles at 34°, fully visible, arrow and face labels aligned | #139 | ☐ |
| 4.5 | Move pictures and the Move strip | Look at S, wide moves, x/y/z, B behind the grid; TalkBack on the Move strip | Pictures read clearly; TalkBack wording makes sense | 12.15 | ☐ |
| 4.6 | Demonstration with TalkBack | A Lesson Demonstration with TalkBack | The generated note is read after each Move | `ai-b4-deferred-device-demo-movenote-talkback` | ☐ |
| 4.7 | Earlier hardware gaps | Touch-to-tick feel, emphasis and arrow overlay on the GPU, TalkBack reading faces and Certainty | Nothing feels laggy; overlays draw; TalkBack speaks them | `epic-5-retro-item-17`, `epic-6-retro-item-3` | ☐ |

---

## Block 5: Automated device tests (agent only, runs in the background)

The agent runs these on whichever device you are not using, one at a time, and reports pass/fail at each block break. Commands are in `docs/benchmarks.md` and `docs/ARCHITECTURE.md`.

| # | Test | Pass when | Affects | Result |
|---|---|---|---|---|
| 5.1 | `Cube3DRenderSyncTest` (all cases, incl. Solution Playback) on both devices | Green | `epic-6-retro-item-3`, 12.14, #200 | ☐ |
| 5.2 | `EndToEndSyncVerificationTest` | Green; the agent also notes the self-referential resume check to fix later | `epic-8-retro-item-35` | ☐ |
| 5.3 | 3D and 2D playback Macrobenchmarks | ≥ 30 FPS Lenovo, ≥ 60 FPS Galaxy, input ≤ 100 ms | 12.14 | ☐ |
| 5.4 | Benchmark harness self-checks | `MinFrameCountMetric` fails a capture below the floor; UiAutomator finds the net on Solution Playback | `epic-1-retro-item-55` (two items), #106 | ☐ |
| 5.5 | Renderer p90 confirmation | p90 frame ≤ 33.3 ms on both | Unblocks the SceneView spike deletion (`story-1-10-deferred-delete-sceneview-spike`) | ☐ |

A failure here is recorded and turned into an issue at the end; it does not stop your blocks.

---

## Block 6: Merged fixes behind 9 GitHub issues

Detailed steps and the "close only if" rule for each issue are in the story, section numbers below. Run #276 first (2 minutes). Camera issues need both cubes and both surfaces.

| # | Issue (plain meaning) | Fixed by | What you check | Story § | Close when | Result |
|---|---|---|---|---|---|---|
| 6.1 | #276 Quick Scan reopens at a stale face-by-face step after Close | PR #283 | Fall through to face-by-face, Close, tap Quick Scan right away (Lenovo) | §8 | Preview is live and auto-captures at hold 1, three times in a row | ☐ |
| 6.2 | #198 Switch to face-by-face and keep captured faces | Story 10.17, PR #269 | All handoffs, Back, fill-only retake, Discard/Cancel (Galaxy is new) | §4 | Both devices pass every path | ☐ |
| 6.3 | #197 No clear sign a corner photo was taken | Story 10.18, PR #266 | Flash, thumbnail, counter, sound and TalkBack on both corners | §3 | Unmistakable on both, especially the Lenovo | ☐ |
| 6.4 | #195 Corner guide can't fit a real camera view | Story 10.18, PR #266 | Outline follows the real cube at several angles; faces align in review | §1 | Both devices, both cubes | ☐ |
| 6.5 | #196 Fails on dark glossy surfaces | Story 10.18 + 10.19 | Reflection never taken as the cube; coaching and recovery | §2 | Detect or coach, and recover, on both | ☐ |
| 6.6 | #249 Paint mode to fix many stickers | PR #263 | Long-press a colour + 9 taps, TalkBack "Painting …/off", 200% font; also in the Editor | §5 | Both device classes; note the duplicate TalkBack action (`issue-249-deferred-swatch-duplicate-a11y-action`) | ☐ |
| 6.7 | #251 Placement says what it read and why | PR #265 (+ #252 re-placement) | Six prepared cube states, TalkBack order: reading → reason → suggestion → set-up | §6 | All six cases on both devices | ☐ |
| 6.8 | #253 Editor accepts any face orientation | PR #258 | Rotated faces, ambiguous and impossible input, cues, TalkBack, 200%; time a full invalid paint on the Lenovo | §7 | UI and TalkBack smoke pass; no dropped frame on the invalid paint (`issue-253-deferred-editor-turn-search-main-thread`) | ☐ |
| 6.9 | Editor TalkBack live regions | #204 fix | Paint a sticker that creates then clears a problem with TalkBack | §7 | Both announcements spoken, none dropped | ☐ |

#300 (finish Story 10.22's acceptance) is closed by Block 7.

---

## Block 7: Quick Scan timing and retakes

7.1 and 7.2 are the same scans: 20 per device gives Story 10.22's hold times and Story 10.23's shadow-reader timing at once. The agent toggles the reader and reads every number from the logs; you just scan.

| # | Check | How | Pass when | Affects | Result |
|---|---|---|---|---|---|
| 7.1 | Hand-held hold times | Lenovo: 10 hand-held scans. Galaxy: 10 still + 10 hand-held, torch on, calibration taken with the torch on | Agent records median and worst per hold and the gate that held any hold over 10 s; one exposure level per hold in a recorder burst | #300, 10.22 (captures promptly) | ☐ |
| 7.2 | Shadow reader does not slow capture | Of the scans above: 10 with the reader on, 10 off, per device | Hold times similar on and off; inference times filled in `docs/evidence/10-23/human-gate.md` H2 | `epic-10-deferred-10.23-device-session-h1-h2` (H2 only) | ☐ |
| 7.3 | Re-ask held off its named top | Trigger a Quick Scan re-ask and hold the cube turned from the named top | Faces merge correctly; review not rotated | `issue-194-deferred-device-reask-off-named-top` | ☐ |
| 7.4 | Retake faces from review | Retake 1 face, then several; back out after 1 of 2; *Enter colors* from the retake view; 200% font; TalkBack face labels | Title stays visible; back-out returns to the normal review; kept faces' hints re-rank | `epic-10-deferred-10.16-device-session` | ☐ |
| 7.5 | Face-by-face scan edge cases | Rotate during the last face freeze; Retake N faces (watch for a flash of the review); shutter audible on a real capture | One haptic tick (Galaxy) and Review shows; no flash; click audible | `issue-158-*` (rotate, shutter), `ai-b1-deferred-device-retake-no-flash` | ☐ |
| 7.6 | Guided Scan time with the logo cube | Guided scan on the Lenovo with `stka`, then `stla` | Agent compares times (was 72–83 s vs 23.5 s) and pulls logs for the cause | `epic-10-deferred-10.8-lenovo-stka-guided-time` | ☐ |
| 7.7 | Light-clear window decision | Watch a hold that clears the light check while the cube moves | You decide: movement may be ignored in that 0.5 s window, or restore the restart (feeds Story 10.21) | `epic-10-deferred-10.19-light-clear-steadiness` | ☐ |

---

## Block 8: Quick looks

One-minute checks. Screenshot each; the agent judges against the stated expectation.

| # | Check | Pass when | Affects | Result |
|---|---|---|---|---|
| 8.1 | Lenovo: You → Settings | Labels not clipped at the start edge | #275 | ☐ |
| 8.2 | Solution Playback header at 200% font, narrow phone | "N moves" header and counter fit, don't touch the right edge; no "0 moves" playback | #201, `ai-b4-deferred-device-header-wrap-edge` | ☐ |
| 8.3 | Scan review → Accept | Goes straight to Solution Playback (*Already solved* for a solved cube); Editor fallback after cancel shows the home frame; pause note on Solve Home with a Lesson open | #203 | ☐ |
| 8.4 | Placement status bar, light and dark (Galaxy) | Icons readable | #205 | ☐ |
| 8.5 | Physical-cube playback with TalkBack (Galaxy portrait) | Only the certainty chip; nothing overlaps the net | #200 | ☐ |
| 8.6 | Rotate while Placement saves | Lands on Learn at the accepted Stage, no second save | `ai-b2-deferred-device-placement-accept-rotate` | ☐ |
| 8.7 | Full Quick Check after one wrong move (ordinary scramble) | Shows VERIFIED | `epic-10-deferred-10.8-full-check-single-wrong-move` | ☐ |
| 8.8 | Suspect stickers when a colour is missing | Note whether clearly different colours are still suggested (you decided on 2026-11-20 they should not be); if they are, open the fix issue | #174 | ☐ |
| 8.9 | Scan with a non-home orientation | Note whether the guided scan ever opens the editor with rotated stickers | `issue-178-deferred-scrambled-non-home-scan-centers` | ☐ |

---

## Not in this session

| Item | Why not now | When |
|---|---|---|
| #127 first release tag, Play App Signing, closed testing, pilot | Needs Block 1 to pass first; it's Play Console work, not device work | Right after this session |
| Play vitals review (`epic-12-deferred-12.3-play-vitals-review`) | Needs closed-testing data (internal track said "not enough data" on 2026-11-20) | After the pilot runs |
| Offline install from the Play track (`epic-12-deferred-12.3-phys-offline-release`) | Needs the build on a Play track | After #127 |
| Story 10.20 corner-fixture recording, 10.23 H1 and H3 | Another session is running Story 10.20 | In that session |
| Camera GA/BETA session C (`epic-11-deferred-11.1-device-session-c`, `11.3-device-capture-and-sticker-order`), full OQ-8 matrix | A separate, longer camera session; required before Story 10.11 turns any camera feature on for release | Before 10.11 |

---

## After the session (agent, then you)

| # | Step | Who |
|---|---|---|
| A1 | Fill `docs/evidence/release-device-session/README.md` (one record per check, screenshots under `docs/evidence/release-device-session/shots/`). | Agent |
| A2 | Draft one evidence comment per GitHub issue with a proposed CLOSE / KEEP OPEN / FOLLOW-UP. | Agent |
| A3 | Review the drafts; approve closures. | You |
| A4 | Post the comments, close the approved issues, mark the settled action items `done` in `sprint-status.yaml`, open issues for every ❌. | Agent |
| A5 | List the S3 findings for one batched decision; recommend which block v1.0. | Agent → you |
| A6 | Commit the evidence on a branch in its own worktree and merge when CI is free. | Agent |
