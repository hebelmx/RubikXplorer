# Sprint Change Proposal — Test features on the main app build

- **Date:** 2026-11-16
- **Trigger:** Story 10.4 close (PR #165) and its follow-up issue #166; recurring Activity-level test deferral in #158, #161, #164 and #166
- **Mode:** Batch (chosen by the delegated panel; the PO is away)
- **Status:** Approved. The PO's decisions of 2026-11-16 are the approved scope. The remaining questions were answered by a party panel (PM John, Architect Winston, UX Sally, Dev Amelia, QA Murat) under the PO's delegation, with the decisions logged in §6. Questions that need the PO are listed in §7.
- **Scope:** Moderate (one story split, one new story, ACs added to five stories, AD-15/AD-18 rule, AGENTS.md policy line, one sprint action item; no code changes in this proposal)

## 1. Issue Summary

Epic 10's camera features are built and accepted only through debug-only launchers. `MainActivity` never registers `ScanFlowContract`, so nothing in the main app starts a Guided Scan. The only way in is the *Cubentia guided scan (debug)* launcher icon (`app/src/camera/debug/.../GuidedScanDebugActivity.kt`), which was added for device session B. As a result:

- Story 10.4 merged after a **partial** device session. The "Colors look off — calibrate?" offer on review, the logo-center and implausible re-prompts, a rescan after *Save*, and the Lenovo were not tested on a device (#166).
- The `GuidedScanActivity` / `MainActivity` route has no Activity-level test. Four issues in a row deferred this (#158, #161, #164, #166).
- `FeatureFlagLevels` sets every flag to GA in debug (`FeatureFlagLevels.kt`, `"debug" to flags.associateWith { "GA" }`). So Welcome shows *Start from my cube* (`PLACEMENT`), but `AppShell.kt:280-300` does not pass `onStartFromCube`, and tapping it does nothing: a dead button, against UX-DR19.
- Calibration capture is manual only. The PO wants steady-hold auto-capture like the faces in Story 10.14.

**Evidence:** `MainActivity.kt:41,72-83` (only the Settings calibration intent is wired), `AppShell.kt:280-300`, `WelcomeScreen.kt:120-130`, `app/src/camera/debug/AndroidManifest.xml` (two launcher activities), `FeatureFlagLevels.kt` debug map, issue #166, `GuidedScan.kt:391-434` (steady hold).

## 2. Impact Analysis

| Area | Impact |
|---|---|
| Epics | Epic 10: Story 10.5 is split. **10.5 keeps its ID** and becomes *Scan my cube from the Solve tab*. The remainder (solve, playback, BETA label and tooltip) moves to the appended **Story 10.15 *Solve from a scan***, which runs right after 10.5. This follows the Story 1.10/1.11 split precedent (SCP 2026-10-31). ACs are added to 10.7, 10.11 and 11.2; notes are added to 10.4 (done) and the Epic 10 and 11 intros; the Order line gains 10.15 and device session B3. |
| PRD | No change. FR-21/FR-24/FR-25 already describe the behavior; the calibration offer rule decided at session B3 is recorded in the PRD memlog. |
| Architecture | AD-15 (debug build type) and AD-18 (new "debug levels follow wiring" rule). No new module, port or dependency. |
| UX | EXPERIENCE.md Color Calibration row: steady-hold auto-capture of swatches. UX-DR19 is unchanged and is now enforced in debug as well. |
| AGENTS.md | New Policy line (main-app testing and the debug-flag rule). |
| Code (in 10.5) | `MainActivity` (register `ScanFlowContract`), the Solve tab scan entry, the calibration steady hold, `FeatureFlagLevels` debug map plus test, the Stage 6 copy condition, deletion of `GuidedScanDebugActivity`, and one Activity-level route test. |
| Sprint status | `10-5-scan-to-solve` is re-keyed to `10-5-scan-my-cube-from-the-solve-tab` (backlog; it had no story file), `10-15-solve-from-a-scan` is added (backlog), and the cleanup action item is added. |

### Checklist summary

- [x] 1.1 Trigger: Story 10.4 close, #166.
- [x] 1.2 Problem type: failed approach. Features were accepted through debug-only entry points instead of the main app.
- [x] 1.3 Evidence: §1.
- [x] 2.1–2.5 Epic impact: Epic 10 can still be completed. 10.5 is split, and 10.15 is added after it. Epic 11 stories gain a debug-flag wiring note (11.2 gets an AC). No epic is removed or resequenced.
- [x] 3.1 PRD: no conflict. [x] 3.2 Architecture: AD-15 and AD-18 amended. [x] 3.3 UX: one row. [x] 3.4 Other: AGENTS.md, sprint-status.
- [x] 4 Path: Direct Adjustment.
- [x] 5 Proposal components: §4.
- [!] 6 Final review: approved under delegation; the §7 questions go to the PO.

## 3. Recommended Approach

**Direct Adjustment.** Split 10.5 so that its first, thin half wires the main-app entry and becomes the test bed for everything Epic 10 has built so far. Then make "debug levels follow wiring" a binding rule, so no feature is again testable only through a side door.

- **Effort:** Story 10.5 (split half) is about one story: Activity registration, a Solve-tab entry, the 30–60 line steady hold, a flag-map change, a copy condition, one Activity-level test, and deleting one debug activity. 10.15 is the original solve and playback work, now smaller.
- **Risk:** low. The main risk is the Activity-level test (Robolectric with a fake frame source). It has been deferred four times, so the AC now names the exact route.
- **Timeline:** no new device session beyond B3, which replaces the partial session B re-run asked for in #166.

## 4. Detailed Change Proposals

### 4.1 Stories (epics.md) — applied

**S1 — Epic 10 summary, Order line.**
OLD: `… 10.14 → 10.4 → 10.5 → 10.7) … Device sessions: … B = 10.2 and 10.4 acceptance; B2 = 10.14; C = …`
NEW: `… 10.14 → 10.4 → 10.5 → 10.15 → 10.7) … Device sessions: … B = 10.2 and 10.4 acceptance (10.4 partial); B2 = 10.14; B3 = 10.5 (main app; re-runs the 10.4 checks not done on a device, #166); C = …`, followed by a new **Main-app rule** line. A camera story is accepted on the main app build, from its real entry point. A debug-only launcher is never a device-session gate, and debug raises a flag only once its entry point is wired (AD-15, AD-18).
Rationale: PO decisions 1 and 3.

**S2 — Epic 10 intro note.** A note is appended: 10.5 is split; 10.5 keeps the Solve-tab scan entry, and the appended 10.15 takes solve, playback and BETA, running right after 10.5. Rationale: the How-to-read rule on appended IDs.

**S3 — Story 10.4 (done), human gate annotation.** A note is added: the PO accepted a partial session B. The open checks and the offer rule move to Story 10.5's session B3 on the main app. Rationale: PO decision 1; #166.

**S4 — Story 10.5, rewritten** (OLD: *Scan to solve*, 4 ACs on scan→solve→playback, BETA, OFF, permission denied).
NEW: *Scan my cube from the Solve tab*, with these ACs:
1. `MainActivity` registers `ScanFlowContract`. The Solve tab shows *Scan my cube* only with `CAMERA_SCAN` above OFF, a `ScanLauncher` bound and a camera present; otherwise there is no dead or disabled button (UX-DR19).
2. `Accepted` opens the validated `Facelets` in the Manual Cube Editor for the Learner to check. `FellBackToEditor` opens the Editor with a note. `Cancelled` returns to the Solve tab. Only the Solve flow dispatches the result.
3. Permission denied: the Editor opens directly with a one-line note (moved from the old 10.5).
4. One Activity-level test covers Solve → `GuidedScanActivity` → review → calibrate offer → calibration → *Save* → rescan with the saved calibration and the preview rebound → review → *Accept* → Editor. It uses a fake frame source and checks that review edits are kept. This closes #158, #161, #164 and #166.
5. Calibration swatches are auto-captured on the `GuidedScanController.onFrame` steady hold. *Keep* still confirms, *Capture* stays, and the logo and implausible re-prompts are kept.
6. Debug flag map: `CAMERA_SCAN` GA; `QUICK_SCAN`, `PLACEMENT` and `CASE_RECOGNITION` OFF until 11.1, 11.2 and 11.3 wire them. *Start from my cube* is then no longer a dead button. `FeatureFlagLevelsTest` pins the map.
7. Stage 6's first-solve prompt keeps the camera-free copy until the 10.7 camera check exists.
8. At close, `GuidedScanDebugActivity`, its launcher entry and `GuidedScanDebugSummaryTest` are deleted. The fixture recorder stays.
9. Device session B3 on the main app (Lenovo and Galaxy S24 FE; stka and stla). A full scan from the Solve tab reaches the Editor. The #166 checks are re-run (offer, logo and implausible re-prompts, rescan after *Save*, auto-capture, Lenovo). The PO records the calibration offer rule in the PRD memlog, and any rule change is made in this story.

Rationale: PO decisions 1, 2, 4, 5 and 6.

**S5 — Story 10.15 (new), appended after 10.14.** *Solve from a scan*: the old 10.5 ACs for scan → review → validate → solve → playback without leaving the flow, *Enter colors instead* always visible, and the BETA label and tooltip (shared *Test me* component, approved copy). It also gets an AC that device acceptance runs on the main app from the Solve tab. Depends on 10.5. Rationale: PO decision 1 (the remainder of 10.5).

**S6 — Story 10.7, AC4 extended.** Stage 6's first-solve prompt switches to its camera copy only together with the camera check action. Rationale: audit item A9.

**S7 — Story 10.11, new AC.** A raised feature's entry point is wired in the main app and was accepted on the main app build, and no debug-only launcher or source set still holds part of it (developer tools excepted). Rationale: PO decisions 3 and 6.

**S8 — Epic 11 intro note.** Each Epic 11 story raises its own flag in debug in the PR that wires its entry point, and is accepted on the main app build. Rationale: PO decision 3.

**S9 — Story 11.2, new AC.** The PR that wires Welcome's `onStartFromCube` puts `PLACEMENT` back to GA in debug, and an AppShell test taps the button and reaches the scan. Rationale: PO decision 4.

### 4.2 PRD — no change

FR-21, FR-24 and FR-25 already describe the behavior. The calibration offer rule (§7 Q1) is recorded in the PRD memlog at session B3 by Story 10.5.

### 4.3 Architecture (ARCHITECTURE-SPINE.md) — applied

**AD-15, Build types, `debug`:**
OLD: `` `debug` includes StrictMode, every feature flag, and the debug camera source set, which holds the fixture recorder (AD-9). ``
NEW: `` `debug` includes StrictMode, every feature flag whose entry point is wired in the main app (AD-18), and the debug camera source set, which holds the fixture recorder (AD-9). Debug-only source sets hold developer tools only (recorder, diagnostics, benchmark harnesses); a Learner feature never lives only there, and a debug-only launcher is never a story's acceptance path (SCP 2026-11-16 main-app entry points). ``

**AD-18, new rule bullet after the camera-dependency bullet:**
NEW: `**Debug levels follow wiring (SCP 2026-11-16 main-app entry points):** debug raises a flag above OFF (normally to GA) only once that flag's entry point is wired in the main app, in the same PR. A raised flag never shows a dead or unconnected entry point, and a feature is accepted on the main app build, not through a debug-only launcher.`

Ripple: the Story 10.9 TestKit fixtures do not depend on the debug map. `FeatureFlagLevelsTest`'s debug expectation changes in 10.5, and debug still composes the camera source set because `CAMERA_SCAN` stays GA. Memlog line appended.

### 4.4 UX (EXPERIENCE.md) — applied

Color Calibration row, after the plain-sticker sentence: "A swatch is captured automatically once the cube is held steady, as a face is in the scan; the Learner still confirms it with *Keep*, and *Capture* stays available (SCP 2026-11-16 main-app entry points, Story 10.5)." Memlog line appended.

### 4.5 AGENTS.md — applied

New Policy line: "Test features on the main app build, from their real entry point: debug raises a feature flag above OFF only once that flag's entry point is wired in the main app (in the same PR), and a debug-only launcher or debug-only source set is never a story's device-session or acceptance path; debug-only source sets hold developer tools only (AD-15, AD-18)."

### 4.6 Sprint status — applied

- Regenerated from epics.md. `10-5-scan-to-solve` (backlog, no story file) is replaced by `10-5-scan-my-cube-from-the-solve-tab: backlog`, and `10-15-solve-from-a-scan: backlog` is added. The recommender now picks 10.5.
- Action item `scp-2026-11-16-delete-debug-only-launchers` (epic 10): delete each debug-only launcher once its feature is in the main app build. `GuidedScanDebugActivity` goes in 10.5. At the Epic 10 retrospective and in every 10.11 run, check that no Learner feature is still reachable only from debug.

## 5. Debug-only audit

Audit of `app/src/debug`, `app/src/camera/debug`, `app/src/corpusRunner`, `app/src/spike3d`, `app/src/release` (no-op twins), and the debug flag map at bd1738f.

| # | Item | Where | Class | Owner / reason |
|---|---|---|---|---|
| A1 | `GuidedScanDebugActivity` (launcher "Cubentia guided scan (debug)") — the only way to start the Guided Scan today | `app/src/camera/debug/.../scan/` + manifest | **(a) user feature** — Guided Scan entry | **10.5** wires it from the Solve tab and deletes this launcher (AC 8) |
| A2 | Review → "Colors look off — calibrate?" → calibration → rescan inside `GuidedScanActivity` | `app/src/camera` (not debug, but reachable only via A1) | **(a)** | **10.5** route test (AC 4) and session B3 (AC 9) |
| A3 | Settings → Color calibration / Reset calibration | `MainActivity`, `SettingsScreen` | Already in the main app (debug with `CAMERA_SCAN` GA) | No action; tested on device 2026-11-16 (#166) |
| A4 | `PLACEMENT` GA in debug; Welcome *Start from my cube* without a callback (dead button) | `FeatureFlagLevels`, `AppShell.kt:280-300` | **(a)**, flag raised before wiring | **10.5** sets it OFF in debug (AC 6); **11.2** wires it and raises it (new AC) |
| A5 | `QUICK_SCAN` GA in debug, no entry point | `FeatureFlagLevels` | **(a)**, flag raised before wiring | **10.5** OFF in debug; **11.1** raises it with its entry (Epic 11 note) |
| A6 | `CASE_RECOGNITION` GA in debug, no entry point | `FeatureFlagLevels` | **(a)**, flag raised before wiring | **10.5** OFF in debug; **11.3** raises it with its entry (Epic 11 note) |
| A7 | Fixture recorder (`FixtureRecorderActivity`, `FixtureRecorderScreen`, `FixtureExport`, `RecorderRules`; Stories 10.10/10.12) | `app/src/camera/debug/.../recorder/` | **(b) developer tool** | Records ground-truth fixtures for the JVM accuracy suites and OQ-7/OQ-8. It is never Learner-facing, and AD-15 and the 10.10 AC keep it out of release. Stays. |
| A8 | `ScanDiagnostics` (`CubentiaScanDiag` logcat, numbers only) | `app/src/debug` (release no-op twin) | **(b) developer tool** | Diagnoses capture-quality thresholds in device sessions; NFR-8 forbids it in release. Stays. It is a diagnostic, never an acceptance signal. |
| A9 | Stage 6 first-solve prompt says "Check it with the camera" whenever `CAMERA_SCAN` is above OFF, but no camera check action exists yet | `LessonScreen.kt:910-923`, `en.json:73` | **(a)**, promise without an entry | **10.5** keeps the camera-free copy (AC 7); **10.7** switches it with the action (S6) |
| A10 | Free-cube debug toggles (glyphs, objective overlay, *Scramble*) behind `DebugOptions.SHOW_DEBUG_TOGGLES` | `app/src/debug/DebugOptions.kt`, `FreeCubeScreen.kt:144` | **(b) developer tool** | Glyphs is already a Learner setting (`SettingsScreen.kt:97`). The objective overlay and free-cube scramble are test aids, not planned Learner features. Stays. |
| A11 | `DebugStrictMode` | `app/src/debug` | **(b)** | AD-16 requires it in debug only. Stays. |
| A12 | `PruningTablesBenchmarkActivity`, `SolveCorpusBenchmarkActivity` (`corpusRunner`) | `app/src/debug`, `app/src/corpusRunner`, `benchmark` manifest | **(b)** | Benchmark harnesses (Stories 1.10, 3.8, 12.2). Stays. |
| A13 | `spike3d` source set (OQ-3 spike) | `app/src/spike3d`, behind `-Pcubentia.spike3d=true` | **(b)**, throwaway spike | In no default build. Candidate for deletion; the cleanup action item re-checks it at the Epic 10 retro. |
| A14 | `TEST_ME`, `ADAPTIVE_ASSISTANCE` GA in debug | `FeatureFlagLevels` | Already in the main app (release BETA/GA) | No action |

## 6. Decision log

| # | Question | Answer | Decided by |
|---|---|---|---|
| D1 | Mode (incremental or batch) | Batch: approve all edits together; PO decisions are approved scope | Delegation (PO instruction) |
| D2 | Split 10.5: new appended story for the scan entry, or 10.5 keeps the scan entry? | 10.5 keeps its ID as the scan-entry half; the remainder becomes appended 10.15. This follows the 1.10/1.11 precedent, keeps "10.5 = scan entry" as #166 already says, and lets the recommender pick it next. Amelia: the `10-5` key had no story file, so re-keying is free. | Party (John proposed; Amelia, Winston agreed) |
| D3 | Welcome dead button: Winston (no `PLACEMENT` GA in debug until 11.2) or Sally (hide any button without a callback in every build)? | **Winston's rule**, adopted generally: debug levels follow wiring (AD-18). Murat: hiding on a missing callback would mask a wiring bug that a test should catch. Sally dissented ("users don't care which layer hides it") but accepted, because AC 6 pins the debug map in a test. | Party (Winston, Murat; Sally dissent recorded) |
| D4 | Where does the calibration steady-hold auto-capture go? | Story 10.5 AC 5: session B3 re-tests calibration anyway, and the route test covers it. Amelia: 30–60 lines, reusing `GuidedScanController.onFrame`. | Party (Amelia, Murat) |
| D5 | Debug-launcher cleanup: AC, story or action item? | An AC in 10.5 deletes `GuidedScanDebugActivity` (as 10.2 deleted the 1.9 prototype). A 10.11 AC blocks raising any feature still tied to a debug-only launcher. A sprint action item covers the standing check at the Epic 10 retro. No separate story: John said a cleanup-only story would sit in the backlog. | Party (Winston, John; Murat asked for the 10.11 gate) |
| D6 | Calibration offer rule | Not decided here: the PO decides at device session B3 (PO decision 1). Panel recommendation to try at B3: also offer calibration once on the first review when no calibration is saved, as a secondary link, not a modal (Sally). John: it is a product trade-off for the PO. | Deferred to PO (§7 Q1) |
| D7 | Stage 6 "Check it with the camera" with no camera action | 10.5 keeps the camera-free copy; 10.7 switches it together with the action (audit A9). | Party (Sally raised it, Amelia sized it) |
| D8 | Should the PRD change? | No: the FRs already hold; only the memlog records the B3 decision. | Party (John) |
| D9 | Scope classification and handoff | Moderate: backlog reorganization plus doc amendments; build 10.5 next with `/bmad-orchestrate`. | Party (John) |

## 7. Open questions for the PO

1. **Calibration offer rule** (decide at device session B3). A stickerless cube that reads well gets no offer today, but the 10.4 AC expects one on a first stickerless scan. Options: keep today's rule (offer only on uncertain or guarded reads) and amend the 10.4 AC wording, or adopt the panel's suggestion (a one-time secondary "Calibrate for your cube" link on the first review when nothing is saved).
2. **Pastel cubes** (#166, unchanged): record a pastel cube at B3 if one is available. That is a device-session item, not a planning change.

## 8. Implementation Handoff

- **Scope:** Moderate.
- **Now (branch `chore/scp-2026-11-16-main-app-entry-points`):** planning edits applied (§4); open a docs PR.
- **Next build:** Story 10.5 (`10-5-scan-my-cube-from-the-solve-tab`), which the sprint recommender picks. Then 10.15, then 10.7.
- **Issues:** #166 is closed by 10.5's ACs 4, 5 and 9; #158, #161 and #164 lose their "Activity-level test" deferral to 10.5 AC 4.
- **Success criteria:** the Activity-level route test passes in CI; session B3 completes on the main app build on both devices with no debug-only launcher installed for the scan; the debug flag map has no flag above OFF without a wired entry; and the PO's offer-rule decision is in the PRD memlog.
