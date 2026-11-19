# Sprint Change Proposal: the scan review's Accept goes straight to Solution Playback (2026-11-19)

- **Trigger:** GitHub issue #203, "Guided Scan: the cube is confirmed twice (scan review, then a second net screen)"
- **Mode:** Batch
- **Scope:** Minor (planning text only, then a build of #203)
- **Product owner decisions:** hebelmx, 2026-11-19:
  1. The scan review is the one and only confirmation. From the Solve tab, the review's *Accept* goes straight to solving and Solution Playback, with no Manual Cube Editor in between.
  2. When the accepted scan is already a solved cube, *Accept* shows the existing `AlreadySolved` state ("Your cube is already solved. Scramble it and try again, or learn to solve it yourself."). This is the state the Editor's *Accept* shows for a solved cube, and the Solver is not called.
  3. The Manual Cube Editor stays the fallback for *Enter colors instead*, an invalid or incomplete scan (`FellBackToEditor`) and denied camera permission.
  4. (Review round, 2026-11-19) When a solve started from a scan falls back to the Editor (cancel, or a solver timeout or failure), the Editor shows the scanned cube in the home frame, and its playback still counts as the Full verified baseline, even if the Learner corrects colors there. This is the 2026-11-17 Story 10.7 rule, kept (`spec-10-7-quick-check-in-physical-cube-mode-and-playback.md`, PO decision "baseline"). The scan stops counting (`scanned` is cleared) only on `closeEditor` or when the Learner leaves the Solve tab.

## 1. Issue summary

From the Solve tab, after the Guided 6-face Scan's review screen (where the Learner corrects colors and taps *Accept*), the app opens the Manual Cube Editor. It shows the same net again, with another *Accept* that starts the solve. The Learner checks and accepts the same cube twice. The PO called it "redundant".

This was found in device session C (Story 10.8) on 2026-11-18, on the Lenovo Tab M10 Plus 3rd Gen and the Galaxy S24 FE, in warm indoor light, with cubes stka (Rubik's, stickered) and stla (GAN, stickerless). The raw logs are in `Fixtures/matrix/` on branch `feat/10-8-recognition-test-matrix-and-ga-beta-decision`.

The expected result is one review step: the review's *Accept* goes straight to solving and Solution Playback. The Editor is only the fallback.

The code does what the plan says. Story 10.5 (done) requires `Accepted` to open the validated `Facelets` in the Manual Cube Editor "for the Learner to check and confirm before anything uses them" (epics.md:1938). Its route test (epics.md:1946) and device session B3 (epics.md:1966) end at the Editor. `sprint-change-proposal-2026-11-16-main-app-entry-points.md` (line 67) set this rule. Routing *Accept* straight to solving therefore needs a planning change before #203 can be built.

## 2. Impact analysis

### Checklist

| # | Item | Status | Note |
|---|---|---|---|
| 1 | Trigger understood | [x] | #203, with device evidence from session C |
| 2 | Epic impact | [x] | Epic 10 only: Story 10.5 (done) and Story 10.15 (done) get AC wording changes. No epic is added, removed or resequenced. |
| 3 | PRD | [N/A] | FR-25 already says "scan → correct → validate → solve → playback without leaving the flow", and "camera permission denied → Manual Cube Editor". FR-14 keeps the Editor as the camera fallback and applies its validation to every Scan Result, which the review already does (Story 10.3, UX-DR16). The new flow matches the PRD more closely than the old one. |
| 4 | Architecture | [N/A] | AD-9 is unchanged: `ScanOutcome {Accepted, Cancelled, FellBackToEditor}`, and `ScanFlowContract.decode` returns `Accepted` only for a Cube State that `CubeValidator` has validated. The Solver still never sees an unvalidated Scan Result. Only the Solve flow dispatches the result. AD-3: neither the Editor nor the Scan review is a session. |
| 5 | UX documents | [x] | EXPERIENCE.md Flow 3 already goes review → *Accept* → Playback with no Editor in between. The Manual Cube Editor row ("Solve, scan fallback, mode switch"), the Solution Playback row ("After a valid scan or entry") and the camera-permission rows (lines 134–135) already agree. Flow 3 step 3 now states the rule outright, the "Another session started" row says where the pause note shows, and the frontmatter `updated:` is 2026-11-19. DESIGN.md has no rule on this. |
| 6 | Code | [!] | `SolveFlowViewModel.onScanOutcome(Accepted)` sets `SolvePhase.Editing(...)`. The #203 build replaces that path (see section 5 for the wiring). These tests assert `Accepted → SolvePhase.Editing` or tap `editor:accept` after an accepted scan, and must change: `SolveFlowViewModelTest` (Accepted cases near lines 89 and 106), `SolveFromScanRouteTest.acceptedScanReachesPlaybackOnlyAfterValidatingInTheEditor` and `.solvedScanShowsAlreadySolvedAfterValidatingInTheEditor`, `ScanRouteTest.scanCalibrationRescanReturnsCorrectedStickersToSolveEditor` (`app/src/testDebug/kotlin/dev/cubentia/app/ScanRouteTest.kt:83`, asserts `SolvePhase.Editing` at line 202), `QuickScanRouteTest` (asserts `SolvePhase.Editing` at line 132), `ScreenAudit` (`app/src/test/kotlin/dev/cubentia/app/ui/a11y/ScreenAudit.kt:201`, "Solve Enter colors scanned", then taps `editor:accept`) and `AppShellSolveTest` (lines 291 and 494). |
| 7 | Path forward | [x] | Direct adjustment |
| 8 | Sprint status | [N/A] | No story is added. Stories 10.5 and 10.15 stay `done`. #203 stays a GitHub issue built after this proposal merges. |

### Planning hits

- epics.md:1936–1938, Story 10.5: "`Accepted` … open in the Manual Cube Editor for the Learner to check and confirm before anything uses them". **Conflicts.** Edited to hand off to Story 10.15.
- epics.md:1944–1946, Story 10.5 route test: "review → *Accept* → Manual Cube Editor". **Conflicts.** Edited.
- epics.md:1964–1966, Story 10.5 device session B3: "a full scan from the Solve tab reaches the Editor". It was accepted as written on 2026-11-18. Annotated, not rewritten, because it is a record of a completed session.
- epics.md:2240–2242, Story 10.15: "scan → review → validate → solve → playback runs without leaving the flow". It never said the Editor was skipped, and it reads validation as a step after review, so an Editor step fit between them. Reworded, and three ACs are added: the already-solved case, the fallbacks, and the fallback after a cancelled or failed solve.
- `sprint-change-proposal-2026-11-16-main-app-entry-points.md`:67: "`Accepted` opens the validated `Facelets` in the Manual Cube Editor for the Learner to check." This is a historical record and is not edited. This proposal supersedes that one sentence.
- UX docs (`ux-designs/ux-RubikXplorer-2026-10-29/`): no line requires the Editor after an accepted scan. EXPERIENCE.md Flow 3 already shows the single review.

### Effects worth knowing

- **Verified baseline:** today the Editor's *Accept* after an accepted scan starts physical playback with that state as the Full verified baseline (`isAcceptedScan`). With no Editor, the accepted scan itself is the baseline. Nothing about Quick Check changes for the Learner.
- **Corrections:** the Learner still fixes stickers on the review net (Story 10.3). The review's *Accept* is disabled until every uncertain sticker is resolved and the state is valid (UX-DR16), so the review checks what the Editor checks.
- **Fallback after a cancelled or failed solve (checked against the code):** `SolveFlowViewModel.accept` stores `accepted = state.toFacelets()`, and `cancel()`, `TimedOut` and `Failed` reopen `SolvePhase.Editing(accepted)`. `toFacelets()` renders the state through its own `orientation`, and the Editor overwrites the centers with the home-frame colors (`ManualCubeEditor`: "Starts from [initial] (centers overwritten)"). Only the solved case is reoriented today (`confirm` and `onScanOutcome`). So a scan held in any frame other than home would reach the Editor with the wrong centers. This is **not** guaranteed today, so the build must hand the Editor `state.reoriented(Orientation.HOME).toFacelets()` (same pieces). `scanned` is also **not** cleared deterministically in that fallback: it survives `cancel`, `TimedOut` and `Failed`, and is cleared only on a successful solve, `closeEditor`, `enterColors`, `scanDenied` or a solved `confirm`. PO decision 4 keeps the Story 10.7 rule: an entry corrected after the fallback still counts as the Full verified baseline. The build makes the clearing deterministic: `scanned` is cleared on `closeEditor` and on leaving the Solve tab, and is kept through cancel, `TimedOut`, `Failed` and edits.
- **"Starting a solve pauses your lesson." (Story 3.7 AC 1, EXPERIENCE.md:110):** today `SolveScreen` shows it only in `SolvePhase.Editing` and `SolvePhase.Solving`, not on the Solve tab's Home with *Scan my cube*. With Editing skipped, a scan from Home with a Lesson open would show it only during Solving, which can last a fraction of a second. Story 3.7 AC 1 already asks for it when the Solve tab opens, so the build also shows it on the Solve tab Home next to *Scan my cube* and *Enter colors* while a Lesson is open, and keeps it while solving. The scan review runs in `GuidedScanActivity`, which knows nothing about sessions, so it does not show the note. EXPERIENCE.md's "Another session started" row now says where the note shows.
- **Placement and Physical-cube Mode:** unchanged. Placement (Story 11.2) and Quick Check (Story 10.7) handle `Accepted` in their own callers and never opened the Solve Editor.
- **Sequencing with Story 10.8:** #203 does not wait for Story 10.8. It is built after this proposal merges and verified on a device in the next device session on the main app build.

## 3. Recommended approach

**Direct adjustment**, following PO decisions 1–4 at the top. The code change is a build that follows this proposal. No new story is needed.

Effort is moderate for a Minor change. The build changes the `Accepted` path, adds a pending hand-off from `SolveFlowViewModel` to `AppShell` (section 5), fixes the fallback orientation, makes the `scanned` clearing deterministic, adds the pause note to the Solve tab Home, and updates seven test files. Risk is low: `Accepted` is already validated, and the solve, cancel and already-solved paths already exist for the Editor's *Accept*. There is no timeline impact.

## 4. Detailed change proposals (applied)

### Epics: epics.md

**Story 10.5, `Accepted` AC (line 1938)**

- OLD: "**Then** the validated `Facelets` open in the Manual Cube Editor for the Learner to check and confirm before anything uses them; `FellBackToEditor` opens the Editor empty with a one-line note, …"
- NEW: "**Then** the validated Cube State is handed to the Solve flow without opening the Manual Cube Editor, and Story 10.15 owns what follows (solve and Solution Playback, or the already-solved state) *(SCP 2026-11-19 scan Accept skips the Editor, #203; until then `Accepted` opened the Editor for a second check)*; `FellBackToEditor` (*Enter colors instead*, or an invalid or incomplete scan) opens the Editor empty with a one-line note, …"
- Rationale: PO decisions 1 and 3. Story 10.5 keeps the scan entry; the solve and playback contract lives only in Story 10.15.

**Story 10.5, Activity-level route test AC (line 1946)**

- OLD: "… review → *Accept* → Manual Cube Editor, using a fake frame source, …"
- NEW: "… review → *Accept* → solving and Solution Playback without the Manual Cube Editor *(SCP 2026-11-19, #203; until then → Manual Cube Editor)*, using a fake frame source, …"
- Rationale: the route test must cover the single-review flow (#203 done-when 3).

**Story 10.5, device session B3 AC (line 1966)**

- OLD: "**Then** a full scan from the Solve tab reaches the Editor on both devices, …"
- NEW: "**Then** a full scan from the Solve tab reaches the Editor on both devices *(as accepted on 2026-11-18; since SCP 2026-11-19, #203, a full scan reaches Solution Playback directly)*, …"
- Rationale: B3 was accepted as written. The annotation keeps the record and says what the next device session expects.

**Story 10.15, flow AC (line 2242)**

- OLD: "**Then** scan → review → validate → solve → playback runs without leaving the flow, and *Enter colors instead* is always visible"
- NEW: "**Then** scan → review (live validation) → *Accept* → solve → playback runs without leaving the flow, and *Enter colors instead* is always visible; validation gates the review's *Accept* (UX-DR16) and is not a separate step after it, and *Accept* is the only confirmation, so no Manual Cube Editor or second net opens between the review and Solution Playback *(SCP 2026-11-19, #203; until then the flow read "scan → review → validate → solve → playback")*"
- Rationale: PO decision 1. Validation happens on the review, not after it.

**Story 10.15, three new ACs (after line 2242)**

- ADDED: "**Given** the Solve tab and an accepted scan that is already solved / **When** the review's *Accept* is tapped / **Then** the existing already-solved state shows ("Your cube is already solved. Scramble it and try again, or learn to solve it yourself."), the same one the Editor's *Accept* shows, and the Solver is not called"
- ADDED: "**Given** *Enter colors instead*, an invalid or incomplete scan (`FellBackToEditor`), or denied camera permission / **When** the scan flow returns to the Solve tab / **Then** the Manual Cube Editor opens as the fallback, as Story 10.5 says"
- ADDED: "**Given** a solve started from an accepted scan / **When** the Learner cancels it, or the Solver times out or fails / **Then** the Manual Cube Editor opens with the scanned cube held in the home frame (`Orientation.HOME`, the same pieces), and its playback still counts as the Full verified baseline even if the Learner corrects colors there (the Story 10.7 rule); the scan stops counting only when the Editor is closed or the Learner leaves the Solve tab"
- Rationale: PO decisions 2–4, stated in the story that owns solve and playback from a scan.

### UX: EXPERIENCE.md

**Frontmatter (line 5):** `updated: 2026-11-16` → `updated: 2026-11-19`.

**"Another session started" row (line 110)**

- OLD: "\"Starting a solve pauses your lesson.\" The Lesson resumes from its Checkpoint. …"
- NEW: "\"Starting a solve pauses your lesson.\" It shows on the Solve tab next to *Scan my cube* and *Enter colors*, in the Editor and while solving, so a scan whose review goes straight to Solution Playback still shows it before the Lesson is paused (SCP 2026-11-19, #203). The Lesson resumes from its Checkpoint. …"
- Rationale: with the Editor skipped, the note would otherwise flash only during Solving.

**Flow 3, step 3 (line 254)**

- OLD: "3. Playback opens. She taps *I'm following on my cube*, …"
- NEW: "3. Playback opens straight from the review: its *Accept* is her one and only confirmation, so no Editor or second net comes in between, after a Quick Scan or a Guided 6-face Scan alike. A scan of a cube that is already solved shows the Solve tab's already-solved message instead. The Manual Cube Editor is only the fallback: *Enter colors instead*, a scan that can't be made valid or complete, or a denied camera permission (SCP 2026-11-19, #203). She taps *I'm following on my cube*, …"
- Rationale: the flow already implied this. Stating it outright stops the Editor step from coming back.

The UX `.memlog.md` gets one appended entry for these changes (through `uv run _bmad/scripts/memlog.py append`, as earlier SCPs did).

No other planning line requires the Editor after an accepted scan. The PRD (FR-14, FR-25) and the architecture spine (AD-9) need no change. Story 3.7 is unchanged: its AC 1 already asks for the pause note when the Solve tab opens.

## 5. Implementation handoff

- **Scope:** Minor. The Developer builds GitHub issue #203 after this proposal merges. Device verification is in the next device session on the main app build; Story 10.8 does not block it.
- **Wiring:** `solveFlow.onScanOutcome` is called from `MainActivity.kt:150–151` (the `ScanFlowContract` result). The solve's `onSolved` callback lives in `AppShell.kt:831–837`, where it dispatches `SessionEvent.SolvePlaybackStarted(state, moves, it, ready.bundle.tutorConfig, scanned)`. `ready.bundle` (the loaded `LessonCatalog`) is not in scope in `MainActivity`. So `onScanOutcome(Accepted)` must not solve directly. Use the same shape as `PlacementFlowViewModel.acceptedScan`:
  1. `SolveFlowViewModel` stores the accepted scan as a pending value (for example `pendingScan: StateFlow<CubeState?>`), keeps `scanned` set, and leaves the phase out of `Editing`.
  2. `AppShell`, in the Solve tab branch where `ready` exists, collects it. Once content is ready it consumes it exactly once and calls the existing `solveFlow.confirm(state, onSolved)`: an unsolved cube is solved and opens Solution Playback, and a solved cube shows `SolvePhase.AlreadySolved` without calling the Solver.
  3. While `LessonCatalog` is still loading (or has failed), the pending scan stays pending. No `SessionEvent` is dispatched, and nothing reads `ready.bundle`. The Solve tab does not open the Editor in the meantime. Recreation must not consume it twice.
- **Fallback state:** in the cancel, `TimedOut` and `Failed` paths of a scan-started solve, hand the Editor `state.reoriented(Orientation.HOME).toFacelets()`, because today `accepted = state.toFacelets()` keeps the scan's hold. Keep `scanned` (so `isAcceptedScan()` stays true) through the fallback, including edits in the Editor (the Story 10.7 rule). Clear it only on `closeEditor` and on leaving the Solve tab.
- **Pause note:** show "Starting a solve pauses your lesson." (`msg.solve.pauses-lesson`, tag `solve:pauseNote`) on the Solve tab Home next to *Scan my cube* and *Enter colors* when `pausesLesson` is true, and keep it in `Solving` and `Editing`.
- **Leave unchanged:** `FellBackToEditor`, `scanDenied` and `enterColors` open the Editor as they do now. Do not change Placement, Quick Check or `ScanFlowContract`.
- **Success criteria:**
  1. From the Solve tab, tapping *Accept* on the scan review goes straight to solving and Solution Playback, without opening the Manual Cube Editor.
  2. An accepted scan of a solved cube shows the existing already-solved state, the same one the Editor's *Accept* shows.
  3. *Enter colors instead*, an invalid or incomplete scan (`FellBackToEditor`) and denied camera permission still open the Manual Cube Editor, with their notes.
  4. A scan accepted while `LessonCatalog` is still loading is solved once content is ready, exactly once, with no crash and no Editor.
  5. After a scan-started solve is cancelled, times out or fails, the Editor shows the scanned cube in the home frame. Its playback is still the Full verified baseline, with or without corrections, and a later manual entry after *Close* or leaving the Solve tab is not.
  6. With a Lesson open, the pause note shows on the Solve tab before *Scan my cube* is tapped.
  7. Tests verify the single-review flow (accepted → playback; accepted solved → already solved; the fallbacks → Editor; pending while loading; the fallback orientation and baseline rule). These are updated to match: `SolveFlowViewModelTest`, `SolveFromScanRouteTest` (its two "…AfterValidatingInTheEditor" tests are replaced), `ScanRouteTest.scanCalibrationRescanReturnsCorrectedStickersToSolveEditor`, `QuickScanRouteTest` (line 132), `ScreenAudit` (line 201) and `AppShellSolveTest`.
  8. The next device session on the main app build confirms that a full scan from the Solve tab reaches Solution Playback with one review.
