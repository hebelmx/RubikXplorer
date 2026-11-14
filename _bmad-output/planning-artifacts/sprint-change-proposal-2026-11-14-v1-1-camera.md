---
title: 'Sprint Change Proposal: bring v1.1 camera recognition (Epics 10–11) back into the plan'
date: 2026-11-14
status: draft
approved_by: null  # pending PO (hebelmx) review
trigger: PO decision 2026-11-14
scope: Moderate (backlog reorganization plus PRD, spine and UX updates; no rollback)
mode: batch (PROPOSED; the PO was not present, so every decision point is recorded in §6 with a recommended answer)
branch: plan/v1.1-camera
supersedes_in_part: sprint-change-proposal-2026-11-08-vision-deferral.md (§4.4 "move Epics 10–11 out"; the deferral of camera features from v1.0 still stands)
---

# Sprint Change Proposal (DRAFT): v1.1 camera recognition back into the plan

> **DRAFT. Nothing in this file has been applied.** Every item marked **PROPOSED — pending PO approval** is a recommendation the facilitator made in the PO's absence. The open questions are collected in §6. No file other than this one was changed: `epics.md`, the PRD, the spine, the UX docs, `sprint-status.yaml` and `AGENTS.md` are untouched.

## 1. Issue Summary

**Trigger:** a PO decision on 2026-11-14. v1.0 shipped camera-free, and v0.9.0 is live to internal testers. While the PO waits for tester feedback, the deferred v1.1 camera work starts: Epic 10 "Scan my cube" (10.1–10.8) and Epic 11 "Scan shortcuts" (11.1–11.3), currently parked verbatim in `post-launch/v1.1-camera-recognition.md:13-270`.

**Category:** a new requirement from the stakeholder (re-activating deferred scope). It is not a failure or a pivot.

**Chosen delivery model (PO, given):**
1. This planning branch brings Epics 10–11 back into the plan.
2. Each story is then built on its own short branch and merged to `main` with **every camera flag OFF in release builds**.
3. At the next release, the PO decides **per feature** whether it stays OFF or goes to BETA, using the same BETA badge and note pattern *Test me* now uses (#137, commits `7d14c5f`, `d82d922`).
4. Tester-feedback fixes land on `main` in parallel, and camera work must never block them.

**Evidence and current state (verified on `main` @ `55cfb8e`):**
- Every AD-18 camera flag is OFF in release and benchmark, and GA in debug: `build-logic/src/main/kotlin/dev/cubentia/buildlogic/policy/FeatureFlagLevels.kt:16-40` (release GA = `ADAPTIVE_ASSISTANCE`; release BETA = `TEST_ME`).
- The release manifest gate allows `CAMERA` only on a debuggable variant: `build-logic/.../policy/ManifestPolicy.kt:9-24` (Story 12.10, `epics.md:2008-2028`).
- `docs/privacy-facts.json:5` has `"runtimePermissions": []`, derived from the release manifest by `:app:writePrivacyFacts` and checked by `:app:checkPrivacyFacts` (Story 13.3, `epics.md:2186-2206`). The public policy states "The app asks for no runtime permission. It does not use the camera…" (`hebelmx/cubentia-site` `site/privacy/index.html:34`).
- CameraX 1.6.2 is in `gradle/libs.versions.toml:31,42-44`, but it is wired only as `debugImplementation` behind the opt-in `-Pcubentia.spikeScan=true` property (`app/build.gradle.kts:106-117`). The spike's debug-only `app/src/spikeScan/AndroidManifest.xml` declares `CAMERA` and `uses-feature android.hardware.camera.any required="false"`.
- **Story 1.9's 62.5% was offline JPEG evidence, not a live CameraX measurement.** `docs/spikes/oq-4-guided-scan.md:5` says "The controlled live-device ≥ 99% gate remains unrun". `Fixtures/README.md` (§ Guided scan spike fixtures, § Offline S3/S4) says `labeled/face-samples.json` holds **one synthetic** face sample, the 132 JPEGs have no 54-facelet ground truth, and no fixture was taken on the Lenovo Tab M10 Plus 3rd Gen.
- Domain pieces already exist: `core/domain/.../recognition/FaceSample.kt` (FaceSample, Lab, CaptureQuality, CalibrationCenters, ScanFixtureSet), `PhysicalObservation.kt` (Full or Partial), and `core/application/.../usecase/PlaceFromScan.kt` (the single writer of `StageSkippedByPlacement`).
- The Room database is at **version 7** (`app/src/main/kotlin/dev/cubentia/app/data/RubikDatabase.kt:16`, `app/schemas/.../7.json`), and there is **no calibration entity yet**.
- The release-gate stories are still open: 12.3 (`epics.md:1838-1860`), 12.4 (`:1862-1878`), 12.5 (`:1880-1906`) and 13.6 (`:2254-2274`), all `backlog` in `sprint-status.yaml:136-138,155`.

## 2. Impact Analysis

### 2.1 Epic impact

| Epic | Impact |
|---|---|
| **10 Scan my cube** | **Restored** into `epics.md` (8 stories, revalidated), plus **3 new stories**: 10.9 release-safety build gate, 10.10 live recognition baseline and fixture recorder, 10.11 raise a camera feature above OFF for a release. |
| **11 Scan shortcuts** | **Restored** (3 stories, revalidated). |
| 12 Release-ready | **No change to scope.** 12.3 and 12.4 stay camera-free for any release with every camera flag OFF. An "if a camera flag is above OFF" clause moves into new Story 10.11 instead of editing 12.3 and 12.4 (§4.3). |
| 13 Website and privacy | **No change to 13.6.** Story 13.4's text in `epics.md:2218,2224,2248` is stale against the deployed site (publisher, contact). There is a separate cleanup note in §4.5. The camera wording of the policy is owned by 10.11. |
| 1–9 | None. 1.9 is done; its follow-through is 10.10. |

No epic is made obsolete. Epic numbering is unchanged (10 and 11 come back under their own numbers, between Epic 9 and Epic 12 in the document).

### 2.2 Story impact: revalidation against what changed since 2026-11-08

| Change since 2026-11-08 | Stories affected | Effect |
|---|---|---|
| Cubentia rename; Kotlin package `dev.cubentia.*`, applicationId `com.exxerpro.cubentia` (`AGENTS.md:41`) | 10.2, 10.10, all | "`:app/camera`" becomes the build-time camera source set `app/src/camera/kotlin/dev/cubentia/app/camera/` (§4.3, AD-9/AD-18 updates). `adb` and the device matrix use `com.exxerpro.cubentia`. The Learner text says Cubentia, and planning keeps RubikXplorer (`AGENTS.md:14`). |
| Room schema now 7 | 10.4 | Calibration is a new entity, so schema 8 needs a `7→8` migration and a migration test (AD-10, spine `:285`). If a tester fix also needs a migration, the version number is the merge-order collision point (§5 risks). |
| Flag levels incl. BETA in release (`TEST_ME`, #137) | 10.5, 10.8, 11.1–11.3, 10.11 | The BETA label (DESIGN.md:267) and BETA note (EXPERIENCE.md:135) already exist for *Test me* (`LessonPanel.kt` `ModeBetaBadge`, `TestMeBetaNote`). Camera stories reuse one shared label component, not a second copy. The camera tooltip copy is already approved: "May misread colors in poor light. Manual entry always works." (EXPERIENCE.md:135). |
| Reference devices: Lenovo Tab M10 Plus 3rd Gen = minimum, S24 FE = mid, Tab S7 FE = compatibility (spine `:443`, `epics.md:238`) | 10.2, 10.8, 10.10, 11.1, 11.3 | The OQ-8 matrix is now 3 devices × 2 cubes × 4 lights. The minimum device (a budget tablet camera) sets the gate. The S7 FE is a compatibility row that is reported but does not gate. API 29–30 stays emulator-only, and an emulator camera is never acceptance evidence (`AGENTS.md:58`). |
| Story 1.9 fixtures: one synthetic sample plus offline JPEG labels only; the live ≥ 99% gate was never run | 10.1, 10.8 | 10.1's Given ("`FaceSample` fixtures from the Story 1.9 matrix") **has no data to run on**. New Story 10.10 records live fixtures with ground truth first. 10.1 then becomes device-free. |
| `spikeScan` source set (`app/build.gradle.kts:106-117`, `app/src/spikeScan/`) | 10.2, 10.9, 10.10 | 10.10 turns the spike activity into the debug-only fixture recorder (AD-9 "Frames", spine `:258`). 10.2 then deletes `spikeScan` and its property, the way 1.11 deleted the SceneView spike (`epics.md:514-516`). |
| Stage 6 camera wording already behind `CAMERA_SCAN` (8.7, proven at BETA by 12.10 AC2) | 10.7 | No new copy work. 10.7 adds the Quick Check entry next to the existing wording. |
| Mismatch ladder resolves (12.8), physical progress only when true (12.7), sessions end durably (12.11, 12.13) | 10.6, 10.7 | `AdoptObserved` and the Quick Check rung plug into the ladder 12.8 built. The rung-1 insertion must keep 12.8's tests green. |
| `PlaceFromScan` write side already exists (`core/application/.../PlaceFromScan.kt`) | 11.2 | 11.2 adds the predicate evaluation and the UI; it does not rewrite the writer. |
| Welcome now persists onboarding completion (`ebac87f`, #137) | 11.2 | *Start from my cube* on Welcome must respect the persisted "onboarding complete" state (it is also reachable from Recognize). |
| Public privacy policy + `privacy-facts.json` site check (Epic 13) | 10.9, 10.11 | A camera release changes `runtimePermissions`, and the site's CI fails until the page states it. This is owned by 10.11. |

### 2.3 Artifact conflicts (details in §4)

- **PRD** `prds/prd-RubikXplorer-2026-10-29/prd.md`:
  - §4.8 header `:456-458` (the release becomes "v1.1, behind flags; per-feature OFF/BETA/GA at each release");
  - §4.8 Privacy bullet `:548` (add the rationale and in-memory wording ref);
  - NFR-5 `:570` (the camera is the only runtime permission, **only when a camera flag is above OFF in release**);
  - NFR-11 `:576`;
  - §6.2 out-of-scope bullet `:603-608` (becomes "v1.1 in progress, flag-gated");
  - §7 gate 6 `:674` (N/A while every camera flag is OFF; applies per raised feature);
  - OQ-7 and OQ-8 `:710-711` (now decided by 10.10 and 10.8);
  - §10 `:727` (add the 2026-11-14 decision);
  - memlog append.
- **Spine:** AD-9 `:238-258`, AD-13 `:307-316`, AD-15 `:334-348`, AD-18 `:369-376`, AD-10 Ownership `:406`, module map `:476`, Reference devices `:443`, Deferred `:529-530`. These are listed only, not edited (§4.6).
- **UX:**
  - EXPERIENCE.md `:34,36,40,43` (IA rows: "v1.1" becomes "flag-gated; hidden while OFF");
  - `:133` (camera permission states: add the **pre-request rationale** copy, which is missing today);
  - `:135` (BETA pattern; no change, reused);
  - `:221-223,235,244,251,266,286` (flow annotations);
  - `:326` (the mockup stays "v1.1 reference");
  - DESIGN.md `:171,250-251,267` (no change).
- **Other:**
  - `AGENTS.md:6,24` (the "plan it from post-launch/…" pointer becomes stale once 10–11 are in `epics.md`; needs a `bmad-project-context` refresh);
  - `docs/privacy-facts.json` (changes only at a camera release);
  - `docs/release.md:80` and `docs/play-first-publish.md:29,49` (Data safety and policy facts say "no runtime permissions"; 10.11 updates them at the first camera release);
  - `post-launch/v1.1-camera-recognition.md` (gets a "restored to epics.md" banner; it is not deleted, because it is a historical record).

### 2.4 Technical impact

- **New build mechanism (the core of this proposal):** the camera adapter, CameraX dependencies, the `CAMERA` permission and `uses-feature` become a build-time opt-in source set. `build-logic` compiles it into a variant **only when that build type has at least one camera flag above OFF** in `FeatureFlagLevels`. With today's levels, debug compiles it and release and benchmark do not. Nothing camera-related can reach a release by accident, and the existing `ManifestPolicy` release rule and the `privacy-facts.json` check stay green without edits. Story 10.9 owns this.
- Pure-JVM recognition code (`StickerClassifier`, `ScanAssembler`, `Assess` extensions, `RecognizeCase` matching) lives in `:core:*` as usual and can ship in release as unused code. R8 strips what nothing references. This is allowed because it carries no permission, no CameraX and no ML runtime.
- **Room 7 → 8** for Color Calibration (10.4).
- The debug APK grows by about 16 MB with CameraX (`docs/spikes/oq-4-guided-scan.md:60`). The release size (NFR-11, `:app:checkReleaseSize`) is unaffected while flags are OFF. At a BETA release, the CameraX DEX counts against the 30 MB budget.

## 3. Recommended Approach

**Selected: Option 1, Direct Adjustment.** Restore the epics, add 3 stories, and leave the v1.0 release gates unchanged. **PROPOSED — pending PO approval.**

| Option | Verdict |
|---|---|
| **1. Direct Adjustment:** restore E10/E11, add 10.9–10.11, gate camera code at build time | **Viable. Selected.** Effort: High for the epic as a whole (11 + 3 stories, CV under real light) but Low for this planning change. Risk: Medium (recognition accuracy); release risk Low because of 10.9. |
| 2. Rollback | Not applicable: no camera code was ever merged to `main` outside the opt-in debug spike. |
| 3. MVP Review (cut Epic 11 or ship only Guided Scan) | Kept as a **fallback**, not a plan. The PRD cut order (`prd.md:635`) already says Quick Scan → beta, then Case recognition, then Placement, and AD-18 lets each be left OFF. If 10.10's live baseline misses badly (for example below 90% on the minimum device), the PO should hold a re-plan before 10.2 (§6 Q3). |

**Rationale:**
- The PO's delivery model already matches AD-18 ("ships behind its flag and can be turned OFF without dead UI", `post-launch/…:195`). The missing piece is a **build-time** guarantee that OFF means "absent", not just "hidden". That guarantee is what keeps tester-fix releases safe while camera code accumulates on `main`.
- Starting with a live baseline (10.10) instead of the viewfinder UI (10.2) puts the riskiest unknown first. The only measurement so far is offline JPEG.

**Effort, risk, timeline (indicative):**
- About 14 stories. The device-free stories (10.9, 10.1, 10.3, 10.6, 11.2 and the logic halves of others) can proceed continuously.
- Device- and cube-gated work is batched into about 3 device sessions (§5.2).
- No date is committed. Camera features reach users only when the PO raises a flag at a release (10.11).

## 4. Detailed Change Proposals

### 4.1 Story order, dependencies and human gates

Legend: **[DEVICE]** needs a physical reference device. **[CUBE]** needs the stickered and stickerless test cubes and controlled light. **[PO]** needs a human action in Play Console or the site repo. **[free]** is device-free (JVM tests, Compose or Robolectric tests, emulator smoke only).

| # | Story | Gate | Depends on | Notes |
|---|---|---|---|---|
| 1 | **10.9** Keep camera code out of a release whose camera flags are OFF | [free] | none | **Must merge before any CameraX code lands in `main`.** Build-logic only, plus TestKit fixtures. |
| 2 | **10.10** Live recognition baseline and fixture recorder | [DEVICE][CUBE] | 10.9 | Device session A. Go/no-go input for the rest of Epic 10 (§6 Q3). Produces the fixtures 10.1 needs. |
| 3 | 10.1 Classify stickers and assemble a Scan Result | [free] | 10.10 fixtures | JVM only. |
| 4 | 10.6 Quick Check evidence and Certainty | [free] | 10.1 (types) | JVM scenario suite; can run in parallel with 5–7. |
| 5 | 10.2 Guided 6-face Scan viewfinder | [DEVICE] acceptance; build [free] | 10.9, 10.1 | Deletes `spikeScan`. Device session B (with 10.4's stickerless check). |
| 6 | 10.3 Review and correct a Scan Result | [free] | 10.1 | Compose tests with fake `ScanResult`s. |
| 7 | 10.4 Calibrate colors for my cube | [free] build; [DEVICE][CUBE] stickerless acceptance | 10.1, 10.3 | Room 7→8. |
| 8 | 10.5 Scan to solve | [free] wiring; [DEVICE] smoke | 10.2, 10.3 | Solve tab (3.7). |
| 9 | 10.7 Quick Check in Physical-cube Mode and playback | [free] wiring; [DEVICE] smoke | 10.6, 10.2 | Touches `LessonScreen` (collides with #139; see §4.4). |
| 10 | 11.2 Start the Course from my cube (Placement) | [free] | 10.5 (accepted Full scan) | `PlaceFromScan` writer exists. |
| 11 | 11.3 Recognize an OLL/PLL Case | [free] matcher; [DEVICE][CUBE] capture | 10.2, 9.2 (`LastLayerMatcher`) | |
| 12 | 11.1 Quick Scan from two corners | [DEVICE][CUBE] | 10.2, 10.3 | Riskiest CV item (OQ-7); last on the cut order. |
| 13 | 10.8 Recognition test matrix and GA/BETA decision | [DEVICE][CUBE] | every feature to be measured | Device session C. Produces the per-feature recommendation that 10.11 consumes. |
| 14 | **10.11** Raise a camera feature above OFF for a release | [PO] + site repo | 10.8 for that feature; **not before 12.3/12.4/12.5/13.6 are done** (§6 Q5) | Runs once per release that raises a flag. |

### 4.2 Release safety (requirement 3): new Story 10.9 and what changes at BETA

**With every camera flag OFF (every tester-fix release until 10.11 runs):**
- The release and benchmark variants compile no camera source set and have no `androidx.camera:*` or ML runtime on `releaseRuntimeClasspath`.
- Their merged manifests declare no `CAMERA` and no `uses-feature android.hardware.camera*`.
- `ManifestPolicy` (release: no runtime permission) and `checkPrivacyFacts` (`runtimePermissions: []`) pass **unchanged**.
- 12.3 AC1 and 12.4 gate-6 "N/A" stay true word for word.

**When the PO raises one camera flag to BETA (or GA) in `FeatureFlagLevels.releaseBeta`/`releaseGa`:**
1. The build composes the camera source set into release. `CAMERA` and `uses-feature android.hardware.camera.any required="false"` appear in the release manifest.
2. `ManifestPolicy` allows `CAMERA` on a non-debuggable variant **only because** a camera flag is above OFF for that build type. It still rejects every other runtime permission.
3. `:app:checkPrivacyFacts` fails until `:app:writePrivacyFacts` regenerates `docs/privacy-facts.json` with `"runtimePermissions": ["android.permission.CAMERA"]`. That in turn makes the site's `check` fail until the policy page states it. The privacy chain is therefore build-enforced, not remembered.
4. 10.11's checklist runs: site policy, Data safety re-confirmation, rationale copy, store-listing text, size check, gate-6 evidence from 10.8.

### 4.3 Epics (`epics.md`): exact text, PROPOSED

**(a) Replace `epics.md:227-229`** (the two "Deferred to v1.1" paragraphs) with:

```markdown
### Epic 10: Scan my cube (v1.1, flag-gated)
A Learner can scan a cube face by face, correct and calibrate it, solve it from the scan, and verify physical Moves with a Quick Check. Built on `main` with every camera flag OFF in release; each feature reaches users only when the PO raises its flag at a release (Story 10.11). Restored from `post-launch/v1.1-camera-recognition.md` by `sprint-change-proposal-2026-11-14-v1-1-camera.md`.
**FRs covered:** FR-21, FR-23 – FR-26, plus the Quick Check rung of FR-9 and FR-16, and the calibration reset of FR-19. **Also:** recognition NFRs, NFR-5, NFR-8, NFR-11, NFR-12, AD-9, AD-13, AD-18, AR-13, UX-DR15, UX-DR16, UX-DR19 – UX-DR22, UX-DR25.
**Order:** 10.9 → 10.10 → 10.1 → (10.6 ∥ 10.2 → 10.3 → 10.4 → 10.5 → 10.7) → Epic 11 → 10.8 → 10.11. Device sessions: A = 10.10; B = 10.2 and 10.4 acceptance; C = 11.1, 11.3 capture and 10.8.
**Never blocks** tester-feedback fixes or Stories 12.3, 12.4, 12.5 and 13.6: camera stories merge only with release camera flags OFF, and Story 10.9's gate keeps the release free of camera code and permissions.

### Epic 11: Scan shortcuts: Quick Scan, Placement and Case recognition (v1.1, flag-gated)
First on the PRD cut order (Quick Scan → beta, then Case recognition, then Placement). Each ships behind its AD-18 flag and can be turned OFF without dead UI.
**FRs covered:** FR-22, FR-27, FR-28. **Also:** recognition NFRs, NFR-11, AD-6, AD-9, AD-10, AD-18, AD-19, UX-DR19, UX-DR25.
```

**(b) FR Coverage Map `epics.md:156,163,166,168-175`:** replace "(v1.1)" with "(v1.1, flag-gated)". No other change.

**(c) `epics.md:236`:** append the sentence *"Epics 10 and 11 (v1.1 camera, flag-gated) run in parallel with Epics 12 and 13 and never gate them (`sprint-change-proposal-2026-11-14-v1-1-camera.md`)."*

**(d) Insert the following before `epics.md:1786` (`## Epic 12: …`).** The original text is from `post-launch/v1.1-camera-recognition.md:13-270`. Amendments are marked *(SCP 2026-11-14)*.

```markdown
## Epic 10: Scan my cube

A Learner can scan a cube face by face, correct and calibrate it, solve it from the scan, and verify physical Moves with a Quick Check. P0.4 exit: the Guided Scan meets its accuracy targets on the test matrix, or `CAMERA_SCAN` ships as BETA. *(SCP 2026-11-14: restored from `post-launch/v1.1-camera-recognition.md`. Every story merges to `main` with every camera flag OFF in release; Story 10.9 lands first and Story 10.10 second. Stories 10.9–10.11 are appended IDs that run earlier than their number, as the How-to-read note allows.)*

### Story 10.1: Classify stickers and assemble a Scan Result

As a Learner,
I want stickers read against my own cube's colors with honest uncertainty,
So that the app flags what it isn't sure of instead of guessing.

**Refs:** FR-21, FR-23, FR-24 · recognition NFRs, NFR-12 · AD-2, AD-5, AD-9 · AR-13

**Depends on** Story 10.10 (live fixtures). *(SCP 2026-11-14: the Story 1.9 matrix holds one synthetic sample and offline JPEG labels only, `Fixtures/README.md`.)*

**Acceptance Criteria:**

**Given** the live `FaceSample` fixtures recorded by Story 10.10 (and the existing synthetic `Fixtures/labeled/face-samples.json`)
**When** `StickerClassifier` (calibration as a parameter) and `ScanAssembler` run on the JVM
**Then** they produce `ScanResult.Full(Facelets + StickerConfidence)` or `Partial`, rotating samples into URFDLB order using center and up-neighbor colors, and extend the existing `dev.cubentia.domain.recognition` types (`FaceSample`, `CalibrationCenters`, `PhysicalObservation`) instead of duplicating them

**Given** the fixture suite
**When** accuracy is reported
**Then** per-sticker and per-scan accuracy and the confidently-wrong rate (target ≤ 0.1%) are printed per device (Lenovo Tab M10 Plus 3rd Gen, Galaxy S24 FE, Galaxy Tab S7 FE), and only categorical confidence leaves the module

### Story 10.2: Guided 6-face Scan viewfinder

As a Learner,
I want the camera to guide me face by face and capture on its own,
So that scanning is quick even for a beginner.

**Refs:** FR-21, FR-23 · NFR-5, NFR-8 · AD-9, AD-13, AD-18 · AR-13 · UX-DR15, UX-DR19, UX-DR21, UX-DR25 · UX Flows 4, 6

**Depends on** Stories 10.9 and 10.1. **Human gate:** acceptance on the Lenovo Tab M10 Plus 3rd Gen and the Galaxy S24 FE (device session B).

**Acceptance Criteria:**

**Given** the first scan
**When** the camera permission is requested
**Then** an explanation shows first, using the EXPERIENCE.md camera-rationale copy *(SCP 2026-11-14: copy to be added by the UX update)* from content strings (NFR-10); if denied, an explanation card with *Enter colors instead* (and a Settings deep-link when permanently denied) is shown

**Given** the CameraX adapter in the camera source set (`app/src/camera/kotlin/dev/cubentia/app/camera/`, Story 10.9) *(SCP 2026-11-14: was `:app/camera`)*
**When** the grid is aligned and steady for ~600 ms
**Then** the face is captured automatically (manual shutter also available), the prompt names the center and up colors with a glyph swatch, and the mini net advances

**Given** a wrong orientation, glare, blur or low light
**When** detected
**Then** the Learner is re-prompted with specific coaching ("Too much glare — tilt the cube"), not an error

**Given** frames
**When** processed
**Then** they stay in memory, are never persisted or sent, and release logs contain no samples; a debug-only recorder exports `FaceSample` JSON with ground truth

**Given** a device with no camera, or camera hardware that cannot be bound *(SCP 2026-11-14)*
**When** any scan entry point would render
**Then** it is hidden as if its flag were OFF (UX-DR19), and the Play listing still installs on that device (`uses-feature android.hardware.camera.any` with `required="false"`)

**Given** the story closes *(SCP 2026-11-14)*
**When** the PR merges
**Then** the Story 1.9 prototype is deleted (`app/src/spikeScan/`, the `cubentia.spikeScan` switch in `app/build.gradle.kts`), CameraX is bound off the main thread, and a debug StrictMode run on the Lenovo adds no app-frame main-thread disk violation (#107 lists the platform ones already known)

### Story 10.3: Review and correct a Scan Result

As a Learner,
I want to fix any sticker the camera misread before it's used,
So that a bad read never reaches the Solver or the tutor.

**Refs:** FR-23, FR-14 · AD-3, AD-9 · AR-13 · UX-DR16, UX-DR22, UX-DR25

**Acceptance Criteria:**

**Given** a scan sub-flow returning `ScanOutcome {Accepted, Cancelled, FellBackToEditor}` through a typed navigation result
**When** review opens
**Then** uncertain stickers are pre-selected, corrections use tap-sticker-then-color, and invalid states name the likely bad stickers ("This corner shows two whites.")

**Given** any uncertain sticker unresolved or an invalid state
**When** the Learner looks at Accept
**Then** it is disabled

**Given** the scan flow
**When** it completes
**Then** only the caller dispatches the result, and the flow never touches a session

**Given** the review screen at 200% font on the Lenovo's 600 dp two-pane width *(SCP 2026-11-14: #137 found word breaks at this width)*
**When** it renders
**Then** no word breaks mid-word and nothing is cut off (`docs/accessibility-manual-check.md`)

### Story 10.4: Calibrate colors for my cube

As a Learner with a stickerless or unusual cube,
I want to teach the app my cube's colors,
So that scans read it correctly.

**Refs:** FR-24, FR-19 · AD-9, AD-10 · AR-13, AR-21 · UX-DR25 · UX Flow 9

**Human gate:** the first-stickerless-scan prompt is accepted on a device with the stickerless test cube (device session B).

**Acceptance Criteria:**

**Given** many uncertain stickers in a scan, a first stickerless scan, or Settings
**When** "Colors look off — calibrate?" or Settings → Color Calibration is chosen
**Then** each of the six centers is shown in turn, the Learner confirms each swatch, and *Save* calls `UpdateCalibration`

**Given** `CalibrationRepository` as the only owner
**When** a rescan runs
**Then** the classifier uses the saved calibration, which is never in `SessionState` or a Checkpoint

**Given** *Reset calibration* in Settings
**When** confirmed
**Then** `ResetCalibration` clears only calibration

**Given** the Room database at version 7 *(SCP 2026-11-14)*
**When** the calibration entity is added
**Then** the database moves to version 8 with an exported `8.json` schema, a `7→8` migration and a migration test that preserves every existing table's rows (AD-10), and the Settings calibration rows are hidden while `CAMERA_SCAN` is OFF

### Story 10.5: Scan to solve

As a Learner who just wants it solved,
I want to scan instead of typing colors,
So that I go from scrambled to solving in under a minute.

**Refs:** FR-25, FR-15, FR-16 · AD-8, AD-9, AD-18 · UX-DR19, UX-DR25 · UX Flow 3

**Acceptance Criteria:**

**Given** the Solve tab
**When** *Scan my cube* is chosen
**Then** scan → review → validate → solve → playback runs without leaving the flow, and *Enter colors instead* is always visible

**Given** `CAMERA_SCAN` is BETA
**When** the Solve tab renders
**Then** *Enter colors* is the primary action and scan carries the shared BETA label (the *Test me* component from #137, not a copy) and the camera tooltip "May misread colors in poor light. Manual entry always works." *(SCP 2026-11-14: names the shared component and the approved copy, EXPERIENCE.md:135)*

**Given** `CAMERA_SCAN` is OFF *(SCP 2026-11-14)*
**When** the Solve tab renders
**Then** *Enter colors* is the only action, exactly as today (Story 3.7)

**Given** camera permission denied from Solve
**When** the Learner taps *Scan my cube*
**Then** the Manual Cube Editor opens directly with a one-line note

### Story 10.6: Quick Check evidence and Certainty

As a Learner with a real cube,
I want a quick photo to confirm what I actually did,
So that the tutor's verdict is based on evidence.

**Refs:** FR-26, FR-9, FR-10 · recognition NFRs · AD-3, AD-5, AD-9 · AR-9, AR-13

**Acceptance Criteria:**

**Given** a Quick Check of the faces the recent Moves touched (≤ 2 captures [ASSUMPTION])
**When** it returns a `Partial` observation
**Then** it is checked for internal consistency only and never completed from `expected`

**Given** `Assess`
**When** the observed faces cover every face any Move since the last Full verified observation could touch and exactly one candidate fits
**Then** Certainty is VERIFIED; otherwise LIKELY or UNKNOWN, and a scenario suite yields 0 false *verified*

**Given** a stale result (the Learner confirmed a Move during the scan)
**When** it arrives
**Then** it is discarded, and if the Learner started it, "Your cube changed while scanning — scan again?" is offered

### Story 10.7: Quick Check in Physical-cube Mode and playback

As a Learner,
I want to check my cube at key moments and recover from what the scan shows,
So that I fix the real problem.

**Refs:** FR-26, FR-9, FR-12, FR-16, FR-8 · AD-3, AD-5 · UX-DR4, UX-DR12, UX-DR13, UX-DR20, UX-DR25 · UX Flows 1, 3, 4

**Acceptance Criteria:**

**Given** physical mode
**When** an Objective boundary, a Stage milestone or a mismatch report occurs
**Then** *Quick Check* is offered (never required; *Done* still works), and it is the first rung of the mismatch ladder, inserted above the Story 12.8 rungs without changing their tests *(SCP 2026-11-14)*

**Given** a matching result
**When** shown
**Then** a ✓ *Verified* chip and a VERIFIED frame appear until the next *Done*; a mismatch shows the SEEN strip with differing stickers outlined, never a whole cube from a Partial

**Given** a Full observation in mismatch
**When** *Use my cube as it is now* is chosen
**Then** `AdoptObserved` replaces the Expected State and starts a Guided recovery; a Partial must first be completed by a Guided Scan or the Editor

**Given** physical Solution Playback or the Stage 6 end
**When** the Learner runs a Quick Check
**Then** it is available at any step, and a verified solved cube triggers the first-solve milestone (recorded only when true, Story 12.7); *Scan it* now appears on the Confirm-your-cube card next to the Story 8.7 camera wording

**Given** `CAMERA_SCAN` is OFF *(SCP 2026-11-14)*
**When** any physical-mode screen renders
**Then** no Quick Check entry, chip or rung appears, and the Story 8.7 / 12.10 tests pass unchanged

### Story 10.8: Recognition test matrix and GA/BETA decision

As the developer,
I want the scan features measured against their targets before release,
So that each ships with an honest GA or BETA label.

**Refs:** FR-21, FR-26 · recognition NFRs · AD-14, AD-18 · OQ-8 · PRD §7 gate 6

**Human gate:** physical devices, both test cubes and the four lighting conditions (device session C).

**Acceptance Criteria:**

**Given** the OQ-8 matrix (stickered and stickerless cubes × daylight, warm indoor, cool LED, low light × Lenovo Tab M10 Plus 3rd Gen (minimum, gating), Galaxy S24 FE (mid, gating), Galaxy Tab S7 FE (compatibility, reported)) *(SCP 2026-11-14: devices named)*
**When** Guided 6-face Scan, Quick Check, and each Epic 11 feature that is built are run with recorded ground truth
**Then** accuracy, corrections, time and confidently-wrong rates are reported against the §4.8 targets in `docs/benchmarks.md` (or `docs/spikes/oq-8-recognition-matrix.md`)

**Given** the results
**When** a target is missed
**Then** the story records a per-feature recommendation (OFF, BETA or GA) for the PO in the PRD memlog, and every scan flow is verified to have a working manual fallback; the release level itself is set only by Story 10.11 *(SCP 2026-11-14: was "`CAMERA_SCAN` is set to BETA for release")*

### Story 10.9: Keep camera code out of a release whose camera flags are OFF

As the developer,
I want a release with every camera flag OFF to contain no camera code, dependency or permission,
So that camera work can merge to `main` while tester-fix releases stay exactly as camera-free as v1.0.

**Refs:** NFR-5, NFR-8, NFR-11 · AD-9, AD-13, AD-14, AD-15, AD-18 · Stories 12.10, 13.3 · `sprint-change-proposal-2026-11-14-v1-1-camera.md` §4.2

**Runs first** in Epic 10; no CameraX code merges to `main` before it.

**Acceptance Criteria:**

**Given** `FeatureFlagLevels.byBuildType` as the only source of flag levels
**When** a build type has at least one of `CAMERA_SCAN`, `QUICK_SCAN`, `PLACEMENT`, `CASE_RECOGNITION` above OFF
**Then** `build-logic` adds the camera source set (`app/src/camera/kotlin`, `app/src/camera/AndroidManifest.xml` with `CAMERA` and `uses-feature android.hardware.camera.any required="false"`) and the CameraX dependencies to that build type only; otherwise neither is added

**Given** today's levels (debug GA; release and benchmark OFF)
**When** `./gradlew spotlessCheck build` runs
**Then** the release and benchmark merged manifests declare no `CAMERA` and no camera `uses-feature`, `releaseRuntimeClasspath` contains no `androidx.camera:*` and no ML or CV runtime (`com.google.mlkit:*`, `com.google.ai.edge.litert*`, `org.tensorflow*`, `org.opencv*`), `docs/privacy-facts.json` is unchanged, and CI's "Verify build wires the gates" step lists the new check

**Given** `ManifestPolicy`
**When** a non-debuggable variant's build type has a camera flag above OFF
**Then** it allows `CAMERA` and nothing else; with every camera flag OFF it still allows nothing (Story 12.10 behavior unchanged), and the rule still keys on `variant.debuggable`

**Given** Gradle TestKit fixtures
**When** `./gradlew -p build-logic test` runs
**Then** (a) all camera flags OFF plus a `CAMERA` in the release manifest fails, (b) all OFF plus `androidx.camera` on the release classpath fails, (c) `CAMERA_SCAN=BETA` in release passes with `CAMERA` and produces privacy facts with `runtimePermissions: ["android.permission.CAMERA"]`, (d) `PLACEMENT` or `CASE_RECOGNITION` above OFF while `CAMERA_SCAN` is OFF fails with a message naming the flag (§6 Q6)

**Given** `:app` main code
**When** it needs a scan
**Then** it reaches the camera only through a port with an optional Hilt binding (`ScanLauncher` or equivalent, named in the AD-9 update) that the camera source set provides; with no binding, every scan entry point is hidden, and a unit test proves a flag above OFF without a binding fails at startup in debug

### Story 10.10: Live recognition baseline and fixture recorder

As the developer,
I want the live CameraX pipeline measured on the reference devices with recorded ground truth,
So that Epic 10 builds on real numbers, not the offline JPEG estimate.

**Refs:** FR-21, FR-24 · recognition NFRs, NFR-11 · AD-9, AD-14, AD-15 · OQ-7, OQ-8 · Story 1.9 · `docs/spikes/oq-4-guided-scan.md`

**Depends on** Story 10.9. **Human gate:** the Lenovo Tab M10 Plus 3rd Gen and the Galaxy S24 FE (Galaxy Tab S7 FE optional), the stickered and the stickerless test cube, normal indoor light, and the PO's go/no-go on the result (device session A).

**Acceptance Criteria:**

**Given** the Story 1.9 `GuidedScanActivity` moved into the debug-only camera source set as the fixture recorder (AD-9 "Frames", AD-15)
**When** a face is captured
**Then** it exports `FaceSample` JSON plus independently entered nine-sticker ground truth, device model, OS build, cube and light description into `Fixtures/live/<device>_<cube>_<light>/`, and no release variant contains the recorder

**Given** both cubes × normal indoor light × the two gating devices, six faces each
**When** the prototype nearest-calibrated-center CIELAB classifier runs on the recorded samples
**Then** per-sticker accuracy, per-face accuracy and confidently-wrong counts are written to `docs/spikes/oq-4-guided-scan.md` § Live baseline, and the Story 1.9 ≥ 99% live gate is recorded as met or missed

**Given** the result
**When** the story closes
**Then** the PO records go (continue with 10.1), go-as-BETA-only, or re-plan in the PRD memlog, and OQ-7's Kotlin-first Quick Scan recommendation is confirmed or revised with its size cost

### Story 10.11: Raise a camera feature above OFF for a release

As the product owner,
I want one checklist that takes a camera feature from OFF to BETA (or GA) for a release,
So that the app, the public privacy policy, Play Console and the store listing all say the same thing on the day it ships.

**Refs:** NFR-5, NFR-8, NFR-11 · AD-13, AD-15, AD-18 · PRD §7 gate 6 · Stories 10.8, 12.3, 12.4, 13.3, 13.4, 13.6 · `docs/release.md`, `docs/play-first-publish.md`

**Runs** once per release in which at least one camera flag leaves OFF; it is reopened (or copied as 10.11-n) for each such release. **Depends on** Story 10.8's recommendation for every raised feature, and on Stories 12.3, 12.4, 12.5 and 13.6 being done (§6 Q5). **Human gates:** the PO's per-feature level decision; the site-repository PR; Play Console.

**Acceptance Criteria:**

**Given** the PO's decision recorded in the PRD memlog
**When** the release-prep PR moves the features into `FeatureFlagLevels.releaseBeta` (or `releaseGa`)
**Then** the build composes the camera source set into release, `:app:writePrivacyFacts` regenerates `docs/privacy-facts.json` with `runtimePermissions: ["android.permission.CAMERA"]`, `FeatureFlagLevelsTest`'s release expectation is updated, `:app:checkReleaseSize` stays below 30,000,000 bytes, and the PR is the last one merged before the `vX.Y.Z` tag

**Given** the site repository `hebelmx/cubentia-site` (never renamed; publisher "Exxerpro Solutions, S.A. de C.V."; contact `exxerpro@exxerpro.com`)
**When** its privacy page is updated right after the app PR merges
**Then** `/privacy/` states, in plain language and with a new effective date: the app asks for one runtime permission, the camera, only when the Learner first opens a scan; camera frames are processed in memory on the device and are never stored, saved as photos or sent anywhere; the app still has no internet permission; every scan feature has a manual alternative; and the site's `check` job passes against the new `privacy-facts.json` before `deploy`

**Given** Play Console
**When** the release is prepared
**Then** the PO re-confirms Data safety as "No data collected" (camera frames are processed only on the device and never leave it), re-saves it if Console asks after the new permission, and records the date in the release issue; `docs/play-first-publish.md:29,49` and `docs/release.md` say "no runtime permission except the camera when a camera feature ships"

**Given** the release build
**When** Learners see a raised feature
**Then** the camera-rationale copy and the camera BETA tooltip come from content strings, the store listing's description names the feature with "(beta)" and says photos never leave the phone, and PRD §7 gate 6 is checked for each raised feature (manual fallback works; no unvalidated Scan Result is used), linking 10.8's evidence

**Given** a feature that stays OFF
**When** the release ships
**Then** it has no entry point, string or permission rationale visible to Learners (UX-DR19)

## Epic 11: Scan shortcuts: Quick Scan, Placement and Case recognition

These are first on the PRD cut order (Quick Scan → beta, then Case recognition, then Placement). Each ships behind its AD-18 flag and can be turned OFF without dead UI. *(SCP 2026-11-14: restored; each merges with its flag OFF in release, and Story 10.11 raises it.)*

### Story 11.1: Quick Scan from two corners

As a Learner,
I want to scan my cube in two photos,
So that scanning takes seconds.

**Refs:** FR-22, FR-23 · recognition NFRs, NFR-11 · AD-9, AD-18 · OQ-7 · UX-DR15, UX-DR19 · UX Flow 3

**Depends on** Stories 10.2 and 10.3. **Human gate:** devices and both test cubes (device session C).

**Acceptance Criteria:**

**Given** `QUICK_SCAN` is BETA or GA
**When** the Learner holds the cube by a corner and captures twice ("1 / 2")
**Then** the adapter's corner detection and perspective unwarp (method from OQ-7, as confirmed by Story 10.10) emit `FaceSample`s, and two opposite-corner captures yield 54 stickers

**Given** faces that are uncertain or an invalid state
**When** assembly finishes
**Then** the Guided Scan grid asks for only those faces, never a guessed state

**Given** the test matrix
**When** Quick Scan is measured
**Then** it is recommended GA only at ≥ 95% valid after ≤ 3 corrections and < 15 s, otherwise BETA; its size cost fits NFR-11, and any OpenCV or LiteRT runtime is added only in the camera source set *(SCP 2026-11-14)*

### Story 11.2: Start the Course from my cube (Placement)

As a Learner who already knows some steps,
I want the app to look at my cube and suggest where to start,
So that I continue from where I really am.

**Refs:** FR-27, FR-4 · AD-6, AD-10, AD-18 · AR-21 · UX-DR19, UX-DR25 · UX Flow 4

**Acceptance Criteria:**

**Given** `PLACEMENT` is BETA or GA
**When** Welcome → *Start from my cube* (or Recognize's link) scans an accepted Full state
**Then** `PlaceFromScan` evaluates each Stage Predicate and suggests the first unmet Stage, with *Choose another Stage*; the existing `PlaceFromScan` writer (`core/application/.../usecase/PlaceFromScan.kt`) is reused *(SCP 2026-11-14)*

**Given** acceptance
**When** recorded
**Then** that Stage unlocks and earlier Stages get `StageSkippedByPlacement` (◇ *Skipped by placement*, never mastered, always revisitable)

**Given** edge outcomes
**When** the cube is unsolved, has a non-white cross, is at the LL, or is solved
**Then** the EXPERIENCE.md Placement-outcome copy and options show

**Given** `PLACEMENT` is OFF
**When** Welcome and Recognize render
**Then** *Start from my cube* is absent everywhere, and the persisted onboarding-complete behavior (#137) is unchanged *(SCP 2026-11-14)*

### Story 11.3: Recognize an OLL/PLL Case

As a Learner practicing the last layer,
I want to photograph my top layer and be told which Case it is,
So that I can look up the right Algorithm.

**Refs:** FR-28, FR-18, FR-27 · recognition NFRs · AD-9, AD-18, AD-19 · UX-DR19, UX-DR25 · UX Flow 5

**Human gate:** capture acceptance on a device with the stickered cube (device session C); matching is JVM-tested.

**Acceptance Criteria:**

**Given** `CASE_RECOGNITION` is BETA or GA
**When** Library → *Recognize case* captures one top-corner view (U + two sides)
**Then** `RecognizeCase` matches the visible stickers through `LastLayerMatcher` under AUF and returns the Case within 1 s

**Given** a unique match
**When** shown
**Then** "OLL 45 · T-shape" opens the Algorithm detail with its Demonstration playing, and *Mark as Learning* is offered, never automatic

**Given** several candidate Cases
**When** shown
**Then** "This could be one of 3 cases" lists candidate tiles with *Scan another angle*, never a silent pick

**Given** the first two layers are unsolved
**When** recognized
**Then** "The first two layers aren't solved yet." shows with *Start from my cube* when `PLACEMENT` is on
```

**(e) `epics.md` frontmatter `updated:`** becomes `2026-11-14`.

**(f) `post-launch/v1.1-camera-recognition.md:9-11`:** add a banner line: *"2026-11-14: restored to `epics.md` by `sprint-change-proposal-2026-11-14-v1-1-camera.md`; this file is now a historical record."* The body is unchanged.

### 4.4 Interplay with open issues and release-gate stories (requirement 5)

| Item | Interplay | Rule (PROPOSED) |
|---|---|---|
| **#104** Solver preparation ~8 s per process start on the old minimum device | 10.5's "scrambled to solving in under a minute" pays this cost after the scan. | Independent. 10.5 starts solver preparation when the scan sub-flow opens (it is already off the main thread, AD-8), so scan time hides it. #104's fix stays a v1.1 tester-track item, not a camera dependency. |
| **#105** Solve corpus 1 timeout in 10,101 | Scanned states are random legal states, the same as the corpus. | Independent; no camera AC depends on it. |
| **#106** 2D net NFR-2 baseline not measured | The scan review and mini net are 2D-net surfaces. | Optional: retarget #106's Macrobenchmark to the 10.3 review screen **only if** it lands first. Never a dependency either way. |
| **#107** Debug StrictMode platform violations | CameraX init can add main-thread disk reads. | 10.2 AC: no *new app-frame* violations; #107's platform list is the baseline. |
| **#139** Phone lesson cube size; tablet learning path | 10.7 touches `LessonScreen.kt`. 11.2 suggests a Stage on the Learn path. | #139 is `pre-release` and has priority. Camera branches rebase on it and never hold it. If #139 changes the stage proportions (its own correct-course), 10.7's chip placement follows the new layout. |
| **12.3** Stability, size, privacy | AC1 "no runtime permissions (v1.0 is camera-free)" (`epics.md:1852`). | Unchanged. It stays true for every release whose camera flags are all OFF, which 10.9 enforces. A raised flag is handled by 10.11, after 12.3 is done (§6 Q5). |
| **12.4** Pilot findings and gates | Gate 6 "N/A for v1.0" (`epics.md:1878`). | Unchanged for v1.0. 10.11 checks gate 6 per raised feature. |
| **12.5** Store listing, public repo | Screenshots and video must show only what ships. | Unchanged. Listing text for camera features is added only by 10.11. |
| **13.6** Register privacy policy in Play | Data safety "no data collected". | Unchanged. 10.11 re-confirms it at a camera release. |
| **Tester-feedback fixes** | Shared files (`AppShell.kt`, `LessonScreen.kt`, `strings/en.json`, `RubikDatabase.kt`). | Camera stories use short branches off `main`, each merged within its story. Tester fixes always merge first when both are ready. A Room version bump goes to whichever merges first, and the other rebases and renumbers. |

### 4.5 PRD (`prd.md`, through `bmad-prd` update), PROPOSED

- **§4.8 header, `:458`.** Replace it with: *"**Release: v1.1, flag-gated.** Restored to the plan on 2026-11-14 (`sprint-change-proposal-2026-11-14-v1-1-camera`). Built on `main` with every camera flag OFF in release; at each release the PO sets each feature OFF, BETA or GA from the test-matrix evidence. Requirements unchanged."*
- **§4.8 Privacy bullet, `:548`.** Append: *"Camera frames are never saved as photos. The rationale shown before the permission request says this in plain words."*
- **NFR-5, `:570`.** *"… v1.0 declares no runtime permissions. From v1.1, the camera is the only one, and a release declares it only when a camera feature's flag is above OFF."*
- **NFR-11, `:576`.** No text change. Add a note: *"Measured at each release that raises a camera flag (Story 10.11)."*
- **§6.2 out-of-scope bullet, `:603-608`.** *"Deferred by PO decision on 2026-11-08; restored to the plan on 2026-11-14 as v1.1, flag-gated (Epics 10–11)."*
- **§7 gate 6, `:674`.** *"… **For any release in which every camera flag is OFF, this gate is N/A**. For each raised feature, it is checked by Story 10.11 with Story 10.8's evidence."*
- **OQ-7, `:710`.** Append *"Initial: Kotlin-first classical CV (Story 1.9); confirmed by Story 10.10."* **OQ-8, `:711`.** Append *"Matrix: 2 cubes × 4 lights × Lenovo Tab M10 Plus 3rd Gen, Galaxy S24 FE (gating) and Galaxy Tab S7 FE (reported); live fixtures by Story 10.10."*
- **§10, `:727`.** Add *"2026-11-14: camera recognition restored to the plan as v1.1, flag-gated; per-feature release levels set by the PO at each release."* Append the same through `memlog.py append`.
- **§2.3 UJ annotations, `:67-68,93,95,104,112`:** no change; "v1.1" stays accurate.

**Related cleanup (not camera; flagged for the PO):** Stories 13.4 and 13.5 in `epics.md:2218,2224,2248` still name publisher "ExxerPro" and contact `abel.briones@exxerpro.com`. The deployed policy (`site/privacy/index.html:18,52`) says "Exxerpro Solutions, S.A. de C.V." and `exxerpro@exxerpro.com`. Recommend that the same epics edit aligns those three lines to the deployed text (§6 Q9).

### 4.6 Architecture spine: updates needed (list only; through `bmad-architecture` update)

1. **AD-9 (`:238-258`).**
   - **Status** `:241`: "v1.1 — active from Story 10.9; every camera story binds it".
   - **Camera adapter** `:245`: it lives in the build-time camera source set `app/src/camera/` (not the `:app` main source). `:app` main reaches it only through a scan port with an optional DI binding.
   - **Frames** `:258`: the fixture recorder is the former `spikeScan` activity, in the debug camera source set. Live fixtures go to `Fixtures/live/`.
2. **AD-13 (`:312`).**
   - A release declares `CAMERA` **iff** its build type has a camera flag above OFF, and then also `uses-feature android.hardware.camera.any required="false"`.
   - With all camera flags OFF, `releaseRuntimeClasspath` has no `androidx.camera:*` and no ML or CV runtime.
   - **Add ML Kit (`com.google.mlkit:*`, `com.google.android.gms:play-services-mlkit*`) to the SDK denylist** (`SdkDenylistPolicy.kt:8-31`), because ML Kit components can send usage data through Google Play services, which conflicts with NFR-8 (§6 Q7).
   - Optionally, privacy facts `schemaVersion: 2` adds a fixed fact `cameraFramesLeaveDevice: false`, emitted only when `CAMERA` is present, so the site check forces the policy sentence (§6 Q8).
3. **AD-18 (`:369-376`).**
   - Flag levels now also **compose the build**: a camera flag above OFF for a build type adds the camera source set and its dependencies to that build type.
   - `PLACEMENT`, `QUICK_SCAN` and `CASE_RECOGNITION` may be above OFF only if `CAMERA_SCAN` is above OFF (§6 Q6).
   - There is **one shared BETA label component** (introduced for *Test me*, #137).
4. **AD-15 (`:340-342`).** "`debug` includes … the fixture recorder": the recorder is the debug camera source set. Release and benchmark carry release levels (already true in code, `FeatureFlagLevels.kt:31-40`).
5. **AD-10 / Ownership Matrix (`:406`).** The Color Calibration Room entity arrives in schema 8 (Story 10.4) with a migration test. The reset is a Learner-scoped delete (already allowed, `:283`).
6. **Module map (`:476`).** Move `camera/` from `app/src/main` to `app/src/camera/`.
7. **Reference devices (`:443`).** Add: recognition acceptance gates on the Lenovo Tab M10 Plus 3rd Gen and the Galaxy S24 FE. The Galaxy Tab S7 FE is reported only. Emulator cameras are never acceptance evidence.
8. **Deferred (`:529-530`).** Replace "ships in v1.1" with "in progress for v1.1 behind flags (SCP 2026-11-14)". Quick Scan's method is confirmed by 10.10 and decided by 11.1.

### 4.7 UX (`EXPERIENCE.md`, through `bmad-ux` update), PROPOSED

- **IA rows `:36,40,43`:** replace "*(v1.1: hidden while the flag is OFF, UX-DR19)*" with "*(v1.1, flag-gated: hidden while the flag is OFF, UX-DR19)*". **`:34` Solve row:** "v1.0" becomes "any release with `CAMERA_SCAN` OFF".
- **State pattern `:133` (Camera permission):** add a **"Before the first request"** row with rationale copy. Proposed copy: *"Cubentia uses the camera to read your cube's colors. Pictures stay on this phone. They are never saved or sent."* It has two actions: *Continue* (shows the system dialog) and *Enter colors instead*. This copy is **PROPOSED — pending PO and UX approval**, and its content key is chosen by 10.2.
- **`:135` BETA pattern:** no change. Add one sentence: "one shared label component across features".
- **Flow annotations `:221,235,244,251,266,286`:** "v1.0 (camera-free)" becomes "while `CAMERA_SCAN` is OFF". No flow logic changes.
- **DESIGN.md:** no change (`:171,250-251,267` already specify the scan grid, viewfinder and beta label).

### 4.8 Sprint status (`sprint-status.yaml`): keys to add, not applied

Insert between `epic-9-retrospective: done` (`:131`) and `epic-12: in-progress` (`:133`). The keys follow `sprint_plan.py` `_slug(maxlen=60)`. Re-generate them with the sprint planner after `epics.md` is edited; do not hand-type them.

```yaml
  epic-10: backlog
  10-1-classify-stickers-and-assemble-a-scan-result: backlog
  10-2-guided-6-face-scan-viewfinder: backlog
  10-3-review-and-correct-a-scan-result: backlog
  10-4-calibrate-colors-for-my-cube: backlog
  10-5-scan-to-solve: backlog
  10-6-quick-check-evidence-and-certainty: backlog
  10-7-quick-check-in-physical-cube-mode-and-playback: backlog
  10-8-recognition-test-matrix-and-ga-beta-decision: backlog
  10-9-keep-camera-code-out-of-a-release-whose-camera-flags-are-off: backlog
  10-10-live-recognition-baseline-and-fixture-recorder: backlog
  10-11-raise-a-camera-feature-above-off-for-a-release: backlog
  epic-10-retrospective: optional

  epic-11: backlog
  11-1-quick-scan-from-two-corners: backlog
  11-2-start-the-course-from-my-cube-placement: backlog
  11-3-recognize-an-oll-pll-case: backlog
  epic-11-retrospective: optional
```

Header comment at `:31-32`: add *"# 2026-11-14: Epics 10 and 11 restored (v1.1, flag-gated). See planning-artifacts/sprint-change-proposal-2026-11-14-v1-1-camera.md"*.

**Sprint recommender caution:** the recommender should pick 10.9 and then 10.10 first, and never 10.11 while 12.3, 12.4, 12.5 or 13.6 are open. If the orchestrator orders by numeric ID, 10.9 and 10.10 need an explicit ordering note, as in the How-to-read rule at `epics.md:31`.

### 4.9 Other artifacts

- **`AGENTS.md:6`:** "on-device camera recognition is planned for v1.1" becomes "is in progress for v1.1 behind flags". **`:24`:** "plan it from `post-launch/…`" becomes "plan it from Epics 10–11 in `epics.md`; keep the four camera flags OFF for release in `FeatureFlagLevels.kt` until Story 10.11 raises one". Apply this through `bmad-project-context` refresh after merge (`AGENTS.md:2`).
- **`docs/privacy-facts.json`:** no change now. It changes only through 10.11 (generated, never hand-edited).
- **`docs/release.md:80`** ("data safety: no data collected") and **`docs/play-first-publish.md:29,49`:** no change now. 10.11 updates them at the first camera release.
- **Site repo `hebelmx/cubentia-site`:** no change now. The policy update is in 10.11 AC2. Its `check` job reads `privacy-facts.json` from the app repo's `main`, so the app flag-raise PR and the site PR must merge back to back (§6 Q8 has an alternative).

## 5. Implementation Handoff

**Scope: Moderate.** This is a backlog reorganization plus PRD, spine and UX updates. Patterns already exist (AD-9, AD-18, UX-DR19). The one new mechanism is 10.9's build-time composition.

### 5.1 Who does what (after PO approval)

| Who | Does | Done when |
|---|---|---|
| PO (hebelmx) | Answers §6; approves or edits this draft. | `status: approved` in frontmatter. |
| Developer (this workflow) | Applies §4.3 to `epics.md` (and the §4.5 13.4/13.5 cleanup if Q9 = yes), the banner in §4.3(f), and the sprint keys in §4.8 via `bmad-sprint-planning`; appends the PRD memlog. Same branch `plan/v1.1-camera`, PR to `main`. | Merged with green CI; the sprint planner validates. |
| PM (`bmad-prd` update) | §4.5. | PRD §4.8, NFR-5, §7 gate 6, OQ-7/8 and §10 updated. |
| Architect (`bmad-architecture` update) | §4.6 items 1–8. **Must merge before Story 10.9 starts**, because 10.9 implements AD-13/AD-18 changes. | Spine updated. |
| UX (`bmad-ux` update) | §4.7, especially the rationale copy. | Before 10.2. |
| Developer (`bmad-orchestrate`) | Stories in §4.1 order, one short branch each, release flags OFF. | Per story. |
| After merge | `bmad-project-context` refresh (§4.9). | AGENTS.md accurate. |

### 5.2 Human-gated device sessions (batch them)

- **A (10.10):** Lenovo + S24 FE (+ S7 FE optional), both cubes, normal indoor light.
- **B (10.2, 10.4 acceptance; 10.5 and 10.7 smoke):** Lenovo + S24 FE, stickerless cube.
- **C (11.1, 11.3 capture, 10.8 full matrix):** three devices, both cubes, four lighting conditions.
- **Release (10.11):** PO in Play Console and the site-repo PR.

### 5.3 Success criteria

- Every release cut while camera work is in progress has release and benchmark manifests with no `CAMERA`, `privacy-facts.json` unchanged, and no `androidx.camera` on the release classpath. This is proven by CI, not by review.
- No tester-feedback PR waits on a camera PR.
- Each camera feature reaches Learners only through 10.11, with the site policy, Data safety and listing aligned on the same day.
- Every planning artifact agrees that camera recognition is "v1.1, flag-gated".

### 5.4 Risks

- Live recognition on the budget minimum tablet may fall well short of 99%. In that case Epic 10 ships BETA at best (10.10 is the early warning).
- Story 10.9's build-time source-set composition is new build-logic. If the AGP 9 per-build-type wiring proves fragile, the fallback is a manifest `tools:node="remove"` on release, but that does not keep CameraX out of the classpath.
- The site check reads facts from the app's `main`, so a flag-raise merged long before a release would make the public policy claim the camera before the shipped app uses it (§6 Q8).
- Merge collisions with tester fixes in `AppShell.kt`, `LessonScreen.kt`, `en.json` and the Room version (schema 8).
- ML Kit or any Play-services ML would violate NFR-8 "no data collected". It must stay denylisted.
- Story 13.4 and 13.5's epics text is stale against the deployed policy (publisher and contact).

## 6. Open questions for the PO (each answer below is PROPOSED — pending PO approval)

| # | Question | Recommended | Why |
|---|---|---|---|
| Q1 | Mode: review all edits at once (batch) or one by one (incremental)? | Batch (this draft). | The PO is away, and the edits are mostly a verbatim restore. |
| Q2 | Approve Direct Adjustment: restore 10.1–10.8 and 11.1–11.3 with the marked amendments, and add 10.9, 10.10 and 10.11? | Yes. | It matches the chosen delivery model. 10.9 is what makes "flags OFF" safe on `main`. |
| Q3 | Should a live baseline (10.10) run before any viewfinder UI, with a go/no-go? Threshold for a re-plan? | Yes. If live per-sticker accuracy is below 90% on the Lenovo, re-plan before 10.2; at 90–99%, continue as BETA-only. | The 62.5% was offline JPEG; the live gate has never been run. |
| Q4 | Gate camera code at **build time** (absent from release while OFF) rather than only hiding it at runtime? | Yes, build time (Story 10.9). | Runtime hiding would still ship `CAMERA` and CameraX, break the 12.10 gate, change privacy facts, and need a policy update for an unused feature. |
| Q5 | Can a camera flag go above OFF before 12.3, 12.4, 12.5 and 13.6 are done (that is, before v1.0.0 production)? | No. The first BETA is at the first release after those gates pass. | It keeps v1.0's privacy, Data safety and gate-6 evidence simple and stops camera work from touching the release path. |
| Q6 | Must `PLACEMENT`, `QUICK_SCAN` and `CASE_RECOGNITION` require `CAMERA_SCAN` above OFF? | Yes, enforced in build-logic (10.9 AC4d). | They all use the scan pipeline, so one camera switch keeps the permission story coherent. |
| Q7 | Add ML Kit and Play-services ML to the SDK denylist? | Yes. | NFR-8 says "no data collected"; ML Kit can send usage data through Google Play services. OQ-7 is Kotlin-first anyway. |
| Q8 | How should the site's `privacy-facts.json` check be kept truthful at a camera release? | Merge the flag-raise PR as the last PR before the tag, then the site PR immediately after. Optionally add a `cameraFramesLeaveDevice:false` fact (schema 2), or point the site at the release tag (`APP_REF`) instead of `main`. | Back-to-back merges need no site change. The extras make the policy sentence build-enforced. |
| Q9 | Align Stories 13.4 and 13.5's text (publisher "ExxerPro", `abel.briones@`) with the deployed policy ("Exxerpro Solutions, S.A. de C.V.", `exxerpro@exxerpro.com`) in the same epics edit? | Yes. | The plan should match the shipped site; it is a three-line text edit. |
| Q10 | Approve the camera-rationale copy: "Cubentia uses the camera to read your cube's colors. Pictures stay on this phone. They are never saved or sent."? | Yes, with UX review. | EXPERIENCE.md has denied-state copy (`:133`) but no pre-request rationale, and PRD §4.8 requires the explanation. |
| Q11 | Should #139 (phone cube size, tablet learning path) land before 10.7 and 11.2? | Yes. #139 has priority, and camera stories rebase onto it. | It is labeled `pre-release`; camera must not block tester-facing work. |
| Q12 | Does the Galaxy Tab S7 FE gate recognition, or only report results? | Report only. Lenovo (minimum) and S24 FE (mid) gate. | This matches SCP 2026-11-12's device roles. |

## Checklist record

| Item | Status | Note |
|---|---|---|
| 1.1 Trigger | [x] | PO decision 2026-11-14 (no triggering story; re-activation of deferred scope) |
| 1.2 Problem | [x] | New requirement from the stakeholder (deferred scope re-activated) |
| 1.3 Evidence | [x] | Flag levels, manifest policy, privacy facts, spike status, Room v7, open gate stories (§1) |
| 2.1 Current epic | [N/A] | No epic in progress is changed; Epic 12/13 stay as-is |
| 2.2 Epic changes | [x] | Restore E10/E11; add 10.9–10.11 |
| 2.3 Remaining epics | [x] | E12, E13 unchanged (Q5); 13.4/13.5 text cleanup flagged (Q9) |
| 2.4 Obsolete or new epics | [x] | None obsolete; no new epic |
| 2.5 Order | [x] | §4.1; camera never gates E12/E13 |
| 3.1 PRD | [!] | §4.5 via bmad-prd |
| 3.2 Architecture | [!] | §4.6 via bmad-architecture (before 10.9) |
| 3.3 UX | [!] | §4.7 via bmad-ux (rationale copy before 10.2) |
| 3.4 Other | [!] | AGENTS.md refresh; release/play docs and site at 10.11; CI gains the 10.9 check |
| 4.1 Direct Adjustment | Viable | Selected (PROPOSED) |
| 4.2 Rollback | Not viable | Nothing to roll back |
| 4.3 MVP Review | Fallback only | Cut order via AD-18 if 10.10/10.8 miss |
| 4.4 Path | [x] | Option 1 |
| 5.1–5.5 | [x] | Sections 1–5 |
| 6.1–6.2 | [x] | Reviewed by facilitator |
| 6.3 Approval | [ ] | **Pending PO** (draft) |
| 6.4 sprint-status | [ ] | Keys prepared in §4.8; not applied |
| 6.5 Handoff | [x] | §5 |
