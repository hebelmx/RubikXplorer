# RubikXplorer sprint change proposal: retire the fixture recorder, record from the real Quick Scan

**26 November 2026 · Approved by the product owner on 26 November 2026, after an adversarial review whose 18 verified findings are applied · Scope: Moderate (backlog reorganization, Epic 10)**

The separate debug fixture recorder failed the Story 10.34 device gate because it drove the camera differently from the app. This proposal retires that recorder and the Quick-Scan-only two-corner code first (Story 10.35). It then rebuilds Story 10.34 so that continuous sessions are recorded from the debug build of the real Solve-tab Quick Scan: one camera setup, one pipeline, one UI to maintain.

## 1. Issue summary

**Trigger.** On 26 November 2026, Story 10.34 (PR #396, open) failed its human gate on the Lenovo TB128XU. The code had passed review, the acceptance gate (Claude, round 2) and CI. Evidence was pulled from the device to `RubikXplorer-10-34/build/continuous-sessions/lenovo/`. The run is logged in the orchestrator ledger, run `202611261108-4e8a`.

| Evidence | Finding |
| --- | --- |
| Session geometry (all 4 sessions) | The recorder's own `ImageAnalysis` set-up (`FixtureRecorderActivity.kt:541-547` on the PR #396 branch: `setOutputImageRotationEnabled(true)`, no `ResolutionSelector`) gave 480×640 frames with a **206×640 crop**. The model saw a narrow vertical strip, and half the cube was cut off (frame-00237.png). The Solve-tab `ScanFrameSource` is not affected. |
| Mapping | 3 of 4 sessions committed **0 stickers** and stayed at 2–4/6 sides. 1 reached 6/6 with 36 committed and 4 conflicts. |
| Capture cost | Baseline p50 40.7 ms; capture p50 49.8–60.9 ms. Every session was **defective** (more than 1.10× baseline). |
| Operator feedback (PO) | The recorder shows no quad outlines and no growing net, so at 6/6 the operator could not tell what was happening. Its first screen (mode picker, cube and light fields) duplicates the session screen. The torch is still wanted. |

**Problem type:** failed approach. A second camera screen duplicated the Quick Scan UI and set the camera up differently, so recordings did not represent what Learners see. Keeping two UIs means every fix has to be made twice.

**PO decisions (party, 26 November 2026):**
1. First retire the **whole** separate recorder (Guided face, corner/Case and Continuous modes) and the Quick-Scan-only corner code. Case recognition is finished and tested across several sessions (Epic 11 done) and needs no capture tool.
2. Then rebuild Story 10.34 as a debug-only recording mode of the real Quick Scan.
3. A Learner-facing "support export" in release builds is **parked**. It would need its own change to AD-13 and NFR-8, the privacy policy and the Play data-safety form ("no data collected").

## 2. Impact analysis

### Checklist

| Item | Status | Note |
| --- | --- | --- |
| 1.1–1.3 Trigger, problem, evidence | [x] | Section 1 above. |
| 2.1 Epic 10 can still finish | [x] | Yes, with 10.34 and 10.35 re-scoped and re-ordered. |
| 2.2 Epic changes | [x] | Edit the acceptance criteria of 10.34, 10.35 and 10.36. No new story. Titles are kept so sprint keys stay stable. |
| 2.3–2.4 Other epics | [x] | Epic 11 (Case recognition) is done. Its runtime path is kept and only the recorder's corner capture goes. No epic becomes obsolete. |
| 2.5 Order | [!] | New order: **10.35 → 10.34 → 10.36**. 10.33 and 10.11 are unchanged. |
| 3.1 PRD | [x] | One parenthetical in the privacy NFR (line 563) changes "developer fixture recorder" to "debug-only Quick Scan session recording". MVP unaffected. |
| 3.2 Architecture | [!] | AD-9 Frames text, the AD-15 build-types bullet and one layout comment (Section 4.2). |
| 3.3 UX | N/A | Debug-only developer tooling (AD-15). No Learner-visible change; EXPERIENCE.md untouched. |
| 3.4 Other artifacts | [!] | `epic-10-context.md` (recompiled by 10.35), `tools/cube-session.sh`, build-logic policies and tests, `Fixtures/continuous/README.md` (PR #396), open action items that name the recorder. |
| 4.x Path | [x] | Direct adjustment, plus dropping 10.34's unmerged recorder UI (Section 3). |

### Epic and story impact

- **Story 10.35** now runs **first** and no longer depends on a passed 10.34. It retires the whole `FixtureRecorderActivity` package (≈3.7k lines on main plus tests), not only its two-corner paths. Case recognition's runtime stays.
- **Story 10.34** depends on 10.35 and is rebuilt on a **fresh branch from `main`**. PR #396 also edits `FixtureRecorderActivity.kt`, `FixtureRecorderScreen.kt` and `RecorderRules.kt`, which 10.35 deletes, so merging `main` into it would only produce modify/delete conflicts. Recording becomes a debug-only mode of the Solve-tab Quick Scan.
  - **Cherry-picked from PR #396:** `ContinuousFrameTap` and the neutral ports; the writer behind the optional binding; state and image replay; `:checkFixtureSize`; `FixtureWriterPolicy` and `check<Variant>FixtureWriter`; the synthetic golden sessions.
  - **Moves package:** the writer moves out of `recorder/session/` into a new debug-only package, `dev.cubentia.app.camera.session`.
  - **Rebuilt in that package:** the labelling screen and its `truthRefusal`/`colorLabel` helpers (now in `RecorderRules.kt` and `ContinuousRecorderScreen.kt`), with `TruthRefusalTest`.
  - **Dropped:** the `ContinuousRecorder*` UI and the activity wiring.
  - **Added:** a latency fix.
  - **PR #396:** closed as superseded when the new branch opens its PR.
- **Story 10.36** records with the 10.34 mode instead of "the continuous recorder". Its labels pass `StickerTruthCheck`.
- **Story 10.33** and **Story 10.11** are unchanged.
- **Historical stories** (10.10, 10.12, 10.20, 10.28) are done and not edited. Their fixtures in `Fixtures/live`, `corner`, `matrix` and `labeled` stay readable by the `:core:domain` evaluation readers, which never compiled against the recorder.

### Technical impact

- **Removed (10.35):**
  - `app/src/camera/debug/kotlin/dev/cubentia/app/camera/recorder/`: `FixtureRecorderActivity`, `FixtureRecorderScreen`, `RecorderRules`, `RecorderBurst`, `FixtureExport`
  - the debug manifest entry, the tests and test resources
  - `cube-session.sh recorder`
  - `CenterCheck` (already dead)
  - the `ScanFrameSource` lock and settle helpers `ExposureLocks`, `SettleTracker`, `lockSupport` and `applyLocks` (confirmed: their only callers are the recorder and the lock and settle cases of `ScanFrameSourcePolicyTest`, which go too), plus the recorder lock-word constant at `ScanFrameSource.kt:506`
  - the `pull` command of `cube-session.sh` for `Fixtures/{live,corner}` and the recorder lines of its usage header
- **Kept for Case recognition:** `CaseCaptureController`, `CornerHold`, `CornerDetector` / `CornerLocator` / `CornerReader` / `CornerGuide` / `CornerFrame` / `CornerPoint`, `LiteRtCornerDetector`, `CornerViewfinder`, `QuickScanPlan` as Case uses it, and `CornerSettle.CAP_MS`.
- **Moved to `main` by 10.35:** `StickerTruthCheck` and its test, cherry-picked from PR #396, so the Story 10.28 nine-of-each-colour rule keeps an owner once `RecorderRules.kt` is deleted.
- **Kept as evaluation readers.** They stay in `core/domain/src/main` (moving them into the evaluation source set is a separate clean-up), and their owner is `:core:domain` evaluation:
  - `QuickScanClassification`, `GridReason`, `SideLight`, `LiveFixture` decoding
  - `exportKotlinStickerReads`, `evaluateScanAccuracy`, `evaluateLiveBaseline`, `evaluateRecognitionApproaches`, `evaluateMatrix`, `evaluateQuickScan`
  - `ScanDiagnostics.onQuickScanRun`
- **Build-logic:** `CameraSizePolicy.debugOnlyClassGlobs` and the tests that use recorder classes as sample paths (`ShadowReaderPolicyTest`, `CameraSizePolicyTest`, `CameraCompositionFunctionalTest`) are updated. The release guards stay.
- **PR #396:** closed as superseded on 26 November 2026, with its branch kept. The rebuilt 10.34 starts from a fresh branch that cherry-picks the kept infrastructure from that branch. Its spec (`spec-10-34-…`, written for the old recorder ACs) and its `review` status stay on that branch; the rebuild writes a new spec. On `main`, 10.34 is still `backlog`, so no status downgrade is needed.

### Supersedes

This proposal supersedes these rows of `sprint-change-proposal-2026-11-25-continuous-fixture-recorder.md`:
- **Row 46** ("Preserve Guided single-face capture and the corner-mode capture Case recognition uses"): both captures are retired.
- **Row 51** ("Corner-mode capture stays while `CASE_RECOGNITION` exists"; "Retire a legacy command only after Story A's demonstrations pass"): 10.35 now runs first. The safety net is the 10.35 reader and Case-test ACs, not a 10.34 device pass.

`CASE_RECOGNITION` still exists as a flag (OFF in 0.11.0 and v1.0.0). Raising it later has **no fixture-capture tool**, and this is recorded as an action item (Section 4.5).

## 3. Recommended approach

**Direct adjustment, plus dropping 10.34's unmerged recorder UI.** No merged work is reverted.

- **Why:** one camera configuration removes the class of bug that failed the gate. The operator sees exactly what the Learner sees (outlines, net, sides mapped, torch). Fixes are made once. The PR #396 infrastructure, which passed its gate, is kept.
- **Alternatives rejected:**
  - Fix the recorder's `ImageAnalysis` and add outlines and net to it: still two UIs that will drift again.
  - Keep a Case-only recorder: one more screen for a feature that is already finished.
  - Release "support mode" now: conflicts with AD-13 and NFR-8, so it is parked behind its own proposal.
- **Effort:** 10.35 medium (deletion and inventory, device-free). 10.34 rebuild medium to high: the Rec control, a session sheet, a rebuilt debug labeller, the package move and the latency fix, cherry-picked onto a fresh branch.
- **Risk:** low to medium. The main risk is removing a helper Case still needs; 10.35 keeps the Case capture tests as its gate.
- **Timeline:** about the same as before. 10.35 no longer waits on a device; 10.34 has one more Lenovo gate.

## 4. Detailed change proposals

### 4.1 Stories (`epics.md`)

#### Story 10.35: Retire the Quick-Scan-only two-corner recorder paths (title kept)

**OLD** (summary): retire only the Quick-Scan-only two-corner code; keep the recorder's corner mode for Case recognition; **Depends on** Story 10.34 with its capture and replay demonstrations passed on a physical device.

**NEW** (full replacement of the story body):

> As the developer maintaining recognition,
> I want the separate debug fixture recorder and the two-corner Quick Scan code that no longer has a consumer removed, and every remaining consumer given an owner,
> So that the camera code carries one Quick Scan pipeline and one capture UI, without losing Case recognition or historical evidence.
>
> **Refs:** FR-22, FR-28 · AD-9, AD-15, AD-18 · Stories 10.10, 10.12, 10.20, 10.28, 10.32 · `sprint-change-proposal-2026-11-25-continuous-fixture-recorder.md`, `sprint-change-proposal-2026-11-26-retire-recorder.md`
>
> **Depends on** nothing open (Story 10.32 is done); **runs before Story 10.34**. Device-free. Not release scope. *(SCP 2026-11-26 retire-recorder: the whole `FixtureRecorderActivity` goes, including its Guided face mode and its corner/Case mode. Case recognition is finished, Epic 11 is done, and the PO accepted losing its recorder capture.)*
>
> **Acceptance Criteria:**
>
> **Given** the tree on `main`
> **When** the caller inventory is taken
> **Then** `docs/evidence/10-35/inventory.md` lists every Android, domain, evaluation, Gradle, build-logic, script, fixture-decoder and test caller of the `dev.cubentia.app.camera.recorder` package, `QuickScanPlan`, `CenterCheck`, `GridReason`, `SideLight`, `QuickScanClassification`, `CornerHold`, `RecorderBurst`, the recorder's two-hold helpers and the `ScanFrameSource` lock and settle helpers (`ExposureLocks`, `SettleTracker`, `lockSupport`, `applyLocks`). Each is marked removed, or kept with a named owner. Every kept reader records whether it stays in `core/domain/src/main` or moves to the evaluation source set (default: stays).
>
> **Given** the inventory
> **When** it is applied
> **Then** the recorder package (all modes), its debug-manifest activity, its tests and test resources, `RecorderBurst`, `CenterCheck`, the `ScanFrameSource` lock and settle helpers with their `ScanFrameSourcePolicyTest` cases and the `ScanFrameSource.kt:506` recorder constant, the `recorder` and `Fixtures/{live,corner}` `pull` commands of `tools/cube-session.sh` with their usage lines, and every other helper left with no production caller are removed. A disabled release flag alone never counts as evidence of dead code.
>
> **Given** Case recognition (`CASE_RECOGNITION`)
> **When** this story merges
> **Then** `CaseCaptureController`, `CornerHold` as Case capture uses it, `CornerDetector` / `CornerLocator` / `CornerReader` / `CornerGuide` / `CornerFrame` / `CornerPoint`, `LiteRtCornerDetector`, `GuidedScanPhase.CornerViewfinder`, `QuickScanPlan` as Case uses it and `CornerSettle.CAP_MS` are kept, and Case capture tests pass.
>
> **Given** the historical readers
> **When** the removal is done
> **Then** `QuickScanClassification`, `GridReason`, `SideLight`, `LiveFixture` decoding, `exportKotlinStickerReads`, `evaluateScanAccuracy`, `evaluateLiveBaseline`, `evaluateRecognitionApproaches`, `evaluateMatrix`, `evaluateQuickScan` and `ScanDiagnostics.onQuickScanRun` are kept (owner: `:core:domain` evaluation). Each still runs on `Fixtures/live`, `corner` (with `tools/sticker-model/holdout.json`), `matrix`, `labeled` and the photo groups (`tools/evaluate_fixture_photos.py`). `docs/evidence/10-35/legacy-replay.md` lists each command and its result, and names the last revision that contains the recorder, with the command to check it out. This file is the single legacy-replay record: Story 10.34 appends its continuous-session replay to it instead of creating `docs/evidence/10-34/legacy-replay.md`.

> **Given** the Story 10.28 nine-of-each-colour rule
> **When** `RecorderRules.kt` is deleted
> **Then** `StickerTruthCheck` (54 stickers, nine of each colour, review provenance, override only with a non-empty reason) and its JVM test are moved to `core/domain` main, cherry-picked from PR #396, so the rule keeps an owner and Story 10.34 calls it.
>
> **Given** build-logic
> **When** the recorder is gone
> **Then** `CameraSizePolicy.debugOnlyClassGlobs`, `ShadowReaderPolicyTest`, `CameraSizePolicyTest`, `CameraCompositionFunctionalTest` and `functional/AndroidFixture.kt` no longer name recorder classes. A test asserts that the debug manifest declares no recorder activity. `CameraCompositionFunctionalTest` uses a replacement debug-only marker, so it still proves the debug camera source set was composed rather than passing trivially. Every release guard still fails on its planted violation.
>
> **Given** a change to release `ScanDiagnostics` or an `app/src/main` caller
> **When** it is part of the removal
> **Then** it is reviewed as product code, and the release build and its checks pass.
>
> **Given** `./gradlew spotlessCheck build`, `./gradlew -p build-logic test`, `:app:checkCameraSize`, `:app:compileDebugAndroidTestKotlin` and the `cornerR8` assemble (the CI build job)
> **When** they run after the removal
> **Then** all pass, Guided capture and old fixture decoding still pass, and the class-byte change is recorded in `docs/evidence/10-35/`.
>
> **Given** open `action_items` that need the recorder
> **When** this story closes
> **Then** each is closed as obsolete (citing this SCP) or re-pointed to Story 10.34, and the PR lists them. The live-tool mentions of the recorder in `epic-10-context.md` are recompiled away; historical evidence docs stay.

#### Story 10.34: Record and replay a continuous Quick Scan session in the developer build's Quick Scan (key unchanged)

**OLD:** "…in the developer fixture recorder". The recorder had a Continuous session mode. **Depends on** Stories 10.30–10.33.

**NEW** (title: *Record and replay a continuous Quick Scan session in the developer build's Quick Scan*). The generated key is unchanged only because `sprint_plan.py` cuts the slug to 60 characters (both titles give `record-and-replay-a-continuous-quick-scan-session-in-the-dev`). Any later edit inside those first 60 characters changes the key.

> As the developer investigating a Quick Scan failure,
> I want to record a scan from the real Solve-tab Quick Scan in the debug build while I turn the cube, with the order of observations kept, and replay it offline against labels I confirmed,
> So that I can reproduce where a continuous scan went wrong, on exactly the camera set-up and UI a Learner uses, and test a new detector on the same pixels.
>
> **Refs:** FR-22 · recognition NFRs (Confidence calibration), NFR-11 · AD-9 (Frames, amended SCP 2026-11-25, SCP 2026-11-26), AD-15, AD-18 · Stories 10.28, 10.30–10.33, 10.35 · `sprint-change-proposal-2026-11-25-continuous-fixture-recorder.md`, `sprint-change-proposal-2026-11-26-retire-recorder.md`
>
> **Depends on** Story 10.35 (recorder retired, `StickerTruthCheck` on `main`). Built on a **fresh branch from `main`** that cherry-picks from the unmerged PR #396 the frame tap and neutral ports, the writer, replay, `:checkFixtureSize`, the writer guards and the synthetic golden sessions. PR #396 is then closed as superseded. The writer, Rec control, session sheet and labeller live in the debug-only package `dev.cubentia.app.camera.session`. The labeller and its `truthRefusal`/`colorLabel` helpers are rebuilt there with `TruthRefusalTest`. A new spec is written; the PR #396 spec is not reused. Story 10.33 does not block it. The recorder is debug-only (AD-15), so this story is not release scope and gates neither Story 10.11 nor any tag. **Human gate:** one recorded session on the Lenovo TB128XU (gating minimum), recorded from the Solve-tab Quick Scan of the debug build, with the PO choosing one complete and one incomplete example.
>
> **Acceptance Criteria:**
>
> **Given** the debug build's Solve-tab Quick Scan
> **When** the operator turns on recording with a debug-only **Rec** control on the Quick Scan camera area, scans, and stops
> **Then** every frame the screen's own pipeline processes is captured or counted in `captureSkips`, and frames come from the same `ScanFrameSource` camera configuration, the same detector, lattice fitter, sampler and stateful mapper, and the same outlines, growing net, Sides mapped count and torch the Learner sees. The recorded crop and model-input geometry equal those Quick Scan uses for that frame size (`testDebug` test). The Rec control and writer are reached only through optional bindings that the debug camera source set provides, and the release, benchmark, `cornerR8` and `release011` builds show no Rec control (test). While a session records, a visible recording indicator is always on screen.
>
> **Given** recording is stopped
> **When** the operator acts on it
> **Then** a debug-only sheet takes cube, lighting and partition (`TUNING` | `HELDOUT` | `UNASSIGNED`) and offers **Save** or **Discard**. The adb trigger (start, stop, save, discard, baseline) is kept. Its broadcast receiver is registered only through the debug-source-set binding, is absent from release, benchmark, `cornerR8` and `release011` (test), and accepts commands only from the shell or the app itself (a signature permission or an equivalent sender check), never from another installed app. A recording started over adb shows the same on-screen indicator. A no-capture baseline run is available from the same sheet.
>
> **Given** a session in progress on the analyzer thread
> **When** a frame is captured
> **Then** the analyzer only copies the frame's data into a bounded writer queue (8 frames), and PNG encoding and file I/O run on a separate writer executor. A frame that finds the queue full is not captured and counts in `captureSkips`. Any skip, or a capture p50 more than 10% above the no-capture p50 on the same device, marks the session defective. `docs/evidence/10-34/latency.md` records p50/p95 with and without capture on the Lenovo TB128XU, and **a non-defective Lenovo session is part of the human gate**.
>
> *(Kept unchanged from the current story text:)* the versioned manifest and per-frame data AC; the build-type writer-guard AC, where `FixtureWriterPolicy.CLASS_PACKAGE`, `CameraSizePolicy.debugOnlyClassGlobs` and their tests are updated together to `dev/cubentia/app/camera/session/`, and a planted writer class in that package still fails every non-debuggable variant; the labels AC, using `StickerTruthCheck` with review provenance, in a debug-only labelling screen opened from the saved-session notice; the `:checkFixtureSize` AC; the `replayContinuousSession` AC; the golden state-replay AC; the `ContinuousImageReplayDeviceTest` AC; and the documentation AC. The legacy-fixture evidence goes into `docs/evidence/10-35/legacy-replay.md`.
>
> **Given** the existing fixtures after Story 10.35
> **When** this story merges
> **Then** every fixture root keeps its reader and still runs (`Fixtures/live`, `corner`, `matrix`, `labeled`), and at least one old fixture and one new continuous session replay successfully.

#### Story 10.36 (title kept: "…on the continuous recorder…", meaning the Story 10.34 recording mode)

| Section | OLD | NEW |
| --- | --- | --- |
| Refs | …Stories 10.30–10.35, 10.11 · `…continuous-scan-enhancement.md` | …Stories 10.30–10.35, 10.11 · `…continuous-scan-enhancement.md`, `sprint-change-proposal-2026-11-26-retire-recorder.md` |
| Depends | Story 10.34 (continuous capture and replay) being done. Sequenced after Story 10.35. | Story 10.34 (Quick Scan session recording and replay) being done; Story 10.35 runs before 10.34. |
| AC1 Given | the debug fixture recorder from Story 10.34 on the Lenovo… | the Story 10.34 recording mode in the debug build's Solve-tab Quick Scan on the Lenovo… |
| AC1 Then | …labels come from its scramble string and pass the recorder's nine-of-each-colour check… | …labels come from its scramble string and pass `StickerTruthCheck` (nine of each colour)… |

**Rationale:** the term "continuous recorder" now means the recording mode in the real Quick Scan.

### 4.2 Architecture (`ARCHITECTURE-SPINE.md`)

**AD-9 Frames (line 272).**

OLD:
> The **debug-only** fixture recorder (the former `spikeScan` activity, moved into the debug camera source set by Story 10.10) exports `FaceSample` JSON … *(SCP 2026-11-25 continuous-fixture-recorder, Story 10.34:)* on operator request the recorder may also keep one bounded continuous session …

NEW:
> Until Story 10.35, a **debug-only** fixture recorder (the former `spikeScan` activity) exported `FaceSample` JSON with ground truth, and from Story 10.12 a downscaled frame per face, to `Fixtures/live/` (SCP 2026-11-15). It is retired (SCP 2026-11-26 retire-recorder), and its fixtures stay as read-only evidence for the `:core:domain` evaluation readers. *(SCP 2026-11-25 continuous-fixture-recorder, amended SCP 2026-11-26, Story 10.34:)* On operator request, the **debug build's Quick Scan**, with the same screen, camera configuration and pipeline a Learner uses, may keep one bounded continuous session (the ordered detector inputs, plus the sampled observations and a manifest) in app-specific debug storage. An operator copies curated sessions into `Fixtures/continuous/` under a CI-enforced fixture size limit. The writer and its Rec control are reached only through optional bindings that the debug camera source set provides. Shipped `continuous/` code holds no writer, and a source-set test fails if a frame writer reaches a release or benchmark build. *(The rest of the paragraph, on state replay and image replay, is unchanged.)*

**AD-15 build types (line 358).**

| OLD | NEW |
| --- | --- |
| …the debug camera source set, which holds the fixture recorder (AD-9). Debug-only source sets hold developer tools only (recorder, diagnostics, benchmark harnesses)… | …the debug camera source set, which holds the Quick Scan session recording mode (AD-9). Debug-only source sets hold developer tools only (session recording, diagnostics, benchmark harnesses)… |

**Layout comment (line 517).**

| OLD | NEW |
| --- | --- |
| `# CameraX adapter → FaceSample; CornerDetector seam; scan port binding; debug fixture recorder` | `# CameraX adapter → FaceSample; CornerDetector seam; scan port binding (debug/: Quick Scan session recording)` |

Architect confirmation of this AD-9 amendment is recorded in the architecture `.memlog.md` on approval. That clears the start gate of the rebuilt 10.34.

### 4.3 PRD (`prd.md` line 563, privacy NFR parenthetical)

| OLD | NEW |
| --- | --- |
| …The only exception is the debug-only developer fixture recorder, which saves frames on an operator's own device on request… | …The only exception is the debug-only Quick Scan session recording (SCP 2026-11-26), which saves frames on an operator's own device on request… |

The release promise is unchanged. A Learner-facing support export is **out of scope** and needs its own proposal against AD-13 and NFR-8.

### 4.4 UX

N/A. No Learner-visible change. The Rec control and the session sheet are debug-only developer tooling (AD-15) and are not added to EXPERIENCE.md.

### 4.5 Sprint status and other artifacts

- **`sprint-status.yaml` keys:** no changes. 10.34 stays `backlog` on `main`.
- **New `action_items`, added when this proposal is applied:**
  - `scp-2026-11-26-pr-396-superseded`: PR #396 is closed and its branch kept for cherry-picks; delete the branch and the `RubikXplorer-10-34` worktree once the rebuilt 10.34 merges. Owner: orchestrator.
  - `scp-2026-11-26-case-recognition-no-capture-tool`: before `CASE_RECOGNITION` is raised above OFF, decide how Case fixtures are captured (for example a debug recording mode of Case capture, like 10.34). Owner: PO (BMad).
- **Open action items that name the recorder** (the 10.35 AC disposes of them; listed here so nothing is dropped):

| Item | Disposition |
| --- | --- |
| `issue-318-deferred-missing-jpeg-says-no-cube`, `-missed-hold-untested`, `-missed-hold-reset-race`, `-status-retake-clause-cut` | Close as obsolete: recorder UI behaviour. |
| `issue-329-deferred-glare-gets-ev-plus-hint`, `-no-jpeg-says-detector-missed` | Close as obsolete: recorder UI. |
| `issue-329-deferred-evidence-floor-constant-drift` | **Keep** (`CornerReader` / `CornerLocator.kt:497`, Case code). Owner: Dev. |
| `issue-330-deferred-evindex-wiring-untested`, `-evindex-without-ev-step` | Close as obsolete: recorder hold JSON. |
| `issue-330-deferred-settle-null-holds-refit` | Close as obsolete: no further corner recording; the existing holds stay as recorded evidence. |
| `epic-10-deferred-10.28-recorder-screen-wiring-tests`, `-count-check-write-retry`, `-recorder-ui-device-check` | Close as obsolete: recorder retired. The count rule lives on in `StickerTruthCheck`. |
| `epic-10-deferred-10.28-epic-10-context-drift` | Re-point to 10.35, which recompiles `epic-10-context.md`. |
| `epic-10-deferred-10.20-coverage-check-strength`, `-missing-groups-json` | **Keep**: they concern the `Fixtures/corner` index and `evaluate_model.py`, which stay. Owner: Dev. |
| `epic-10-deferred-10.32-retire-kept-2-corner-code` | Close **in part** (`CenterCheck` and the `SideLight` members only the recorder used). Re-word the rest: `QuickScanClassification` stays as an evaluation reader with owner `:core:domain` evaluation, and moving it to the evaluation source set is optional future clean-up. |
| `scp-2026-11-16-delete-debug-only-launchers` | Re-point: 10.35 deletes the recorder launcher; close it if no other debug-only launcher remains. |

- **`epic-10-context.md`:** recompiled during 10.35.
- **Parked idea:** open a GitHub issue, "Support export in release builds (needs an AD-13/NFR-8 SCP)".

## 5. Implementation handoff

**Scope: Moderate.** The PO approves this proposal. The changes are applied on branch `docs/scp-2026-11-26-retire-recorder` and merged through a PR.

| Role | Responsibility |
| --- | --- |
| PO (BMad) | Approve. Run the rebuilt 10.34's Lenovo gate. Later, decide whether the support export is worth its own proposal. |
| Architect (Winston) | Confirm the AD-9 amendment in the architecture `.memlog.md` (part of applying this proposal). |
| Orchestrator / Dev (Amelia) | Run Story 10.35 (including moving `StickerTruthCheck` to `main`), then Story 10.34 rebuilt on a fresh branch that cherry-picks from PR #396, then 10.36. PR #396 is closed; keep its branch until the rebuilt 10.34 merges. Close or re-point the recorder action items. |

**Success criteria:**
- After 10.35 there is no `FixtureRecorderActivity`; Case capture, Guided capture and every fixture reader pass.
- After 10.34, a Lenovo session recorded from the Solve-tab Quick Scan replays exactly, is not defective, and shows the PO the same outlines and net as the app.
