---
title: Remaining work plan — path to v1 release
date: 2026-11-17
status: approved
decided_by: hebelmx (PO), party-mode round table (Mary, John, Sally, Winston, Amelia)
baseline: main 385a594
---

# Remaining work plan — path to v1 release

## Decisions locked (PO, 2026-11-17)

1. **Process first.** Group D (orchestrator/process items) runs right after the Group A verify pass and before #175, so deferral routing is in place before the feature sprint. Condition (John): one sitting, skill update + duplicate merge only.
2. **Camera stays in v1.** The PO expects GA. Story 10.8 is the evidence that signs it off; if the matrix is below the bar on any fixture cube (stla, stlb, stka, stkb), the verdict is BETA, not "GA with an asterisk". Order after 10.8: 10.11, Epic 11, 12.4.
3. **#175 and #176 are high priority** and release scope (they feed the store screenshots). #178 rides along (same screen).
4. **Autonomous work goes first.** Work is split into four piles by what it needs from a human.

## The four piles

| Pile | Needs | Runs via |
|---|---|---|
| 1. Autonomous | Nothing; orchestrator to merge (auto-merge only all-green) | `/bmad-orchestrate`, `/bmad-orchestrate-issues` |
| 2. Device only | Phone/tablet on adb, screenshots; **no cube** | One short device session |
| 3. Device + cube | Physical cube(s) under real lighting + device | One long cube session |
| 4. PO decisions and clicks | hebelmx; no device | PO, async |

## Execution order

### Phase 1 — Group A verify pass (Pile 1)

Check each item against code/tests; mark `done` in `sprint-status.yaml` only with evidence (file:line or test name). Anything not actually closed returns to its bucket below.

| Item | Expected closer |
|---|---|
| epic-8-retro-item-33 (one source for physical copy) | 12.9 |
| epic-8-retro-item-34 (skip-ahead self-report path) | 12.9 |
| epic-8-retro-item-37 (release-permission gate end to end) | 12.10 |
| epic-2-retro-item-4 (TestKit for checkLearnerText) | 12.10 |
| epic-9-retro-item-40 (Course Lesson after practice) | 12.11 |
| epic-9-retro-item-42 (2-look Continue after reset) | 12.11 |
| epic-9-retro-item-41 (2-look offer never downgrades) | 12.12 |
| epic-9-retro-item-44 (shell recognition diagram test) | 12.12 |
| epic-9-retro-item-45 (2-look copy to en.json) | 12.12 |
| epic-9-deferred-9-3-rtl-net-mirroring | 12.1 |
| epic-1-retro-item-55 (Net2DFrameTimingBenchmark) | 12.2 |
| epic-3-retro-item-63 (narrow #105) | 12.2 |
| epic-1-retro-item-58 (mark epic-1 done) | already `done` in sprint-status |
| epic-3-retro-item-65 (tidy Epic 3 artifacts) | partly: epic-3 already `done`; check plan specs + stray patch |

### Phase 2 — Group D process update (Pile 1, one sitting)

Single orchestrator/skill update covering:

- **Deferral routing (the one that stops the bleeding):** epic-2-retro-item-1, epic-4-retro-item-13, epic-9-retro-item-47, epic-3-retro-item-64 — gate-surviving mediums and spec `deferred` entries are copied into `action_items` at story close. epic-3-retro-item-64 also routes its four Epic 3 deferrals now; "editor drafts lost on tab switch" (3.7) is learner-visible → Phase 4.
- **Epic-boundary cross-story review:** epic-8-retro-item-38 + epic-9-retro-item-48 (duplicates — merge into one), epic-7-retro-item-70 (interim retro when the last story is gated on another epic).
- **Spec authoring:** epic-2-retro-item-5 (binding-AD ids verbatim), epic-4-retro-item-14 (specs settle UI behavior choices), epic-5-retro-item-22 (pre-build scope check with recorded PO decision).
- **Commit/close hygiene:** epic-5-retro-item-20 (story id in every commit subject), epic-1-retro-item-57 (story close sets spec and sprint status together).

### Phase 3 — High-priority playback issues (Pile 1)

- **#175** Solution Playback: switch between the 2D net and the 3D cube.
- **#176** Move glyph next to each move label, show/hide in Settings.
- **#178** Solve from a scan: solved cube says "already solved", not empty 0/0 playback.

Gate note: #175 touches the renderer → `Cube3DRenderSyncTest` (epic-5-retro-item-21) runs on emulator if available; otherwise it is queued for the Phase 5 device session. Visual acceptance of #175/#176 is Pile 2.

### Phase 4 — Story 12.3 with Group B code items (Pile 1)

12.3 Stability, size and privacy hardening, absorbing:

- epic-6-retro-item-1 — PreferenceStore must not overwrite saved prefs with defaults after a read error (data loss).
- story-1-10-deferred-delete-sceneview-spike — delete `spike3d*` source sets and the build switch (size).
- scp-2026-11-16-delete-debug-only-launchers — no Learner feature reachable only from debug (privacy).
- epic-2-retro-item-3 — remaining Learner copy out of `strings.xml`; extend the Learner-text gate.
- epic-4-retro-item-9 — auto-demonstration replays after Lesson close/reopen.
- epic-4-retro-item-11 — Moves after a Planned Guided recovery are not adaptive-assistance mistakes.
- epic-9-deferred-9-8-rotation-regenerates-course-scramble.
- epic-9-deferred-9-8-physical-result-line-before-solved.
- 3.7 editor drafts lost on tab switch (from Phase 2 routing).

Then the release-pipeline code items (Pile 1, can run as one issue batch):

- epic-7-retro-item-67 — `docs/release.md` merged-first flow.
- epic-7-retro-item-68 — release-APK install-and-launch smoke in `release.yml` (closes epic-2-retro-item-6 on first green run).
- epic-5-retro-item-23 — Robolectric android-all without live Maven fetch.
- epic-6-retro-item-2 — Compose clock stabilization in CI.

Also Pile 1: the text side of **12.5** (store listing copy, public portfolio repo prep).

### Phase 5 — Device-only session (Pile 2, no cube)

- `Cube3DRenderSyncTest` (if not run in Phase 3) and visual acceptance of #175, #176, #178.
- epic-8-retro-item-35 — instrumented `EndToEndSyncVerificationTest` + fix its self-referential resume check.
- epic-5-retro-item-17 / epic-6-retro-item-3 — Tab S7 FE turn timing, touch-to-tick, emphasis/arrow overlay, TalkBack, real-touch gestures.
- epic-2-retro-item-6 — release build smoke (12 Lessons, R8 decode, Stage 2 Guide me, switcher at 411dp), unless 7-68 already covers it.
- Re-check #137 UI defects and #139 design decisions on device.

### Phase 6 — Device + cube session (Pile 3)

In this order:

1. **10.8** Recognition test matrix → GA/BETA decision (add the cube-A/cube-B scenario via correct-course first). Target: GA; below bar on any fixture cube → BETA.
2. **10.11** Raise the camera feature above OFF for the release (incl. the debug-only-launcher check from the SCP item).
3. **Epic 11**: 11.2 (start the course from my cube placement), 11.1 (quick scan from two corners), 11.3 (recognize an OLL/PLL case). Each builds autonomously; its device gate joins this session.
4. **12.4** Pilot findings fixed and release gates passed (with #127 pilot install).

Camera-related follow-ups to fold in where they fit: #166, #172, #173, #174, #158, #161, #164.

### Phase 7 — Store and release

- **12.5** store screenshots (after #175/#176 and camera GA, so the scan flow is in the listing).
- **13.6** register the privacy policy in Play Console.

## Pile 4 — PO items (hebelmx, async, before 12.4)

- epic-4-retro-item-12 — release levels for TEST_ME and ADAPTIVE_ASSISTANCE.
- epic-1-retro-item-54 — make CI a required status check on main.
- 13.6 — Play Console privacy policy.
- epic-7-retro-item-66 / #127 — first vX.Y.Z tag, Play App Signing, closed-testing promotion, pilot install.

## After release (v1.1 / tech debt)

- epic-4-retro-item-15 + epic-7-retro-item-69 — split `LessonScreen.kt` / `AppShell.kt`.
- epic-5-retro-item-19 — split `Cube3DRenderer.kt`.
- epic-4-retro-item-10 — ContentValidator moveSet guard (matters once new content is authored).
- epic-8-retro-item-36 — tighter certainty tests.
- epic-9-deferred-9-1-9-2 — Case family membership + identity oracle.
- epic-5-retro-item-18 — double-tap reset checks tap distance.
- epic-9-deferred-9-8-physical-stage-mastery-milestone (low).
- epic-5-retro-item-16 — free-cube correct-course; confirm still relevant first.
- Issues #104, #105, #106, #107, #119 (v1.1).

Move any of these into Phase 4 if they show up in pilot findings.
