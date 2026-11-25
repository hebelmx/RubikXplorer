---
title: 'Sprint Change Proposal: camera release timeline (Guided Scan GA, new Quick Scan BETA, both in 0.11.0)'
date: 2026-11-24
status: approved
mode: batch
trigger: PO request after v0.10.1 (camera OFF) was sent for closed-testing review
scope: Moderate
resolves: epic-12-deferred-12.4-release-order-10-11-vs-12-5
---

# Sprint Change Proposal: camera release timeline

## 1. Issue summary

**Trigger.** On 2026-11-24, v0.10.1 (versionCode 3, signed tag `v0.10.1` at `f53f676ef58a92e0727daccd72bcd2e1af270631`, every camera flag OFF) passed its Release pilot run ([45531395685](https://github.com/hebelmx/RubikXplorer/actions/runs/45531395685)), was uploaded to internal testing (2026-11-25 02:46 UTC), and the PO sent it for closed-testing review. The PO wants camera features in front of testers, and then in production, on a different timeline from the current plan:

- The **Guided 6-face Scan** (`CAMERA_SCAN`) ships as **GA**, with no beta label. The PO reports near error-free device use.
- A **new Quick Scan** (`QUICK_SCAN`) ships as **BETA**, with the beta label, so Learners know it may be unstable and can be patient. It replaces the parked pipeline. The PO no longer wants to keep testing the old Quick Scan.
- **Both go out in one build, 0.11.0**, first to closed testing, then on to production as v1.0.0.

**Where the current plan blocks this:**

1. **Order.** Story 10.11, the only path that raises a camera flag for release, *depends on 12.5 being done*, and 12.5's last AC promotes the build to production. So no camera build can reach testers before production. Story 12.4 routed exactly this as action item `epic-12-deferred-12.4-release-order-10-11-vs-12-5`.
2. **Guided Scan level.** Story 10.8 already recommended the Guided 6-face Scan as **GA** (PRD `.memlog.md` 2026-11-18 entry; `docs/spikes/oq-8-recognition-matrix.md`: device session C, Lenovo TB128XU and Galaxy S24 FE, 240/240 stickers, 0 confidently wrong), with Placement BETA, Quick Scan and Case recognition OFF. That recommendation covered **warm light only**. What is missing is a release-level decision: the memlog's 2026-11-15 entry (Story 10.10 AC3, operator-dependent calibration) still holds `CAMERA_SCAN` at BETA-only, and release levels are set only by Story 10.11. `docs/scan-accuracy.md` (Story 10.1, A2 fixtures, studio light) supports GA: the Kotlin reader read 432/432 stickers, 8/8 scans `Full`, 0 confidently wrong on the Lenovo TB128XU and the Galaxy S24 FE.
3. **Quick Scan.** The 10.21 BETA call (2026-11-24) left `QUICK_SCAN` OFF, and 10.24–10.27 are parked pending a pipeline rethink. The rethink exists: the continuous facelet scan with clean-room LiteRT v2 (PR #363, `_bmad-output/planning-artifacts/research/technical-continuous-facelet-scan-2026-11-24/`). But it is PC research plus an Android spike being tested in another session, not app code, and it changes the Quick Scan architecture and PRD (see §2).

## 2. Impact analysis

**Epic impact**
- **Epic 10 (camera):** 10.11 changes its dependency and its first-run scope. 10.24–10.27 are superseded by the new approach. New Quick Scan stories are needed, and their content is defined by the spike session (TBA).
- **Epic 12 (release):** 12.5's production promotion now carries the camera build, as a new v1.0.0 tag, so it waits for 10.11. 12.3's "camera-free" and 12.4's gate-6 N/A were true for the 0.10.x builds they were accepted on and stop being true for v1.0.
- **Epic 7 / #127 (pilot):** the pilot protocol says "do not imply camera recognition". The gate-4 pilot sessions run on 0.11.0 and cover both scans, so SM-8 (scans valid on the first attempt) becomes measurable. 0.10.1 is install and smoke only.

**Story impact**
- **10.11:** dependency, first-run scope, annotation, AC1 (tag), AC2 (site order), AC4 (beta label per level, pilot protocol) (§4.1).
- **12.3, 12.4:** annotations on the camera-free and gate-6 N/A ACs (§4.1).
- **12.5:** production AC (§4.1).
- **10.21, 10.24, 10.25, 10.26, 10.27:** 10.21's last AC records the OFF call; the others are marked superseded (§4.1). Their status stays `backlog` until the spike session's SCP replaces or drops them.
- **New (TBA):** the Quick Scan port stories, written by the spike session through its own correct-course. This SCP fixes only their place in the timeline: they must be done, with a recorded BETA call, before the 10.11 run that raises `QUICK_SCAN` merges.

**Artifact conflicts**
- **PRD §7 gate 6:** "v1.0 included" in the N/A sentence becomes false for v1.0 (§4.2). The PRD memlog needs the PO's level decisions (10.11 AC1 requires them there), a line superseding Q5/Q8 of 2026-11-14 and the 2026-11-15 BETA-only entry, and the 10.21 OFF call.
- **Architecture spine and PRD for Quick Scan:** the new approach changes AD-9's reader of record, AD-18's composition, `ShadowReaderPolicy`, `CameraSizePolicy`, PRD FR-22 and §4.8's out-of-scope "continuous video". The spike session's SCP amends them; **this SCP does not edit the spine**. Those amendments, plus measured `:app:checkCameraSize` / `:app:checkReleaseSize` headroom (research.md estimates about 2.0–2.5 MB), are preconditions for raising `QUICK_SCAN` in 10.11. For `CAMERA_SCAN` and `PLACEMENT`, AD-13, AD-15 and AD-18 already describe a feature raised by 10.11.
- **AGENTS.md:** "v1.0 ships camera-free" and "camera work is v1.1" are now wrong (§4.3).
- **UX:** none. The beta tooltip (UX-DR19) and the camera rationale already exist as content strings, and 10.11 AC4 checks them.
- **Site order:** 10.11 AC2 ("updated right after the app PR merges") conflicts with `docs/release.md:25`, which requires the site camera sentence before merging a flag raise while `APP_REF` is `main`. Fixed in the 10.11 edit.
- **Docs, handled inside 10.11:** `docs/pilot-session-protocol.md`, `docs/release.md`, `docs/play-first-publish.md`, and `docs/release-gates-v1.0.md` gate 6.

**Technical impact.** No code changes in this SCP. 10.11 run 1 does the site privacy PR (camera sentence) first, or relies on `APP_REF` staying pinned to `v0.10.1`; then the release-prep PR (flag levels, the camera source set in release, `CAMERA` permission, privacy facts schema 2) as the last PR before `v0.11.0`; then the tag; then `APP_REF` → `v0.11.0` after the release run passes; then Play Console (Data safety re-confirmation, permission declaration). Size budget: `:app:checkReleaseSize` must stay under 30,000,000 bytes with the camera and LiteRT models in.

## 3. Recommended approach

**Direct adjustment.** Change the order and scope of existing stories, record the PO decisions, and leave the Quick Scan port (and its spine and PRD amendments) to the spike session. No rollback, and no MVP cut.

**Timeline:**

| Build | Track | Content |
| --- | --- | --- |
| 0.10.1 (in review now) | closed testing | Course, Library and Solve, camera OFF. Install and smoke only; not the gate-4 pilot build. |
| **0.11.0** | closed testing | `CAMERA_SCAN` **GA**, `PLACEMENT` **BETA**, `QUICK_SCAN` **BETA** (new approach, only if its preconditions hold), `CASE_RECOGNITION` OFF. Produced by **10.11 run 1**. The gate-4 pilot sessions and SM-8 run on this build; its pilot record clears 12.5. |
| **1.0.0** | closed testing → production | A new `v1.0.0` tag cut from `main` with the same flag levels as 0.11.0. Gates 3 and 6 are re-run on that tag, and Story 12.5 records gates 1–6 on the 1.0.0 build before promoting it. `1.0.0` stays reserved for the public launch (`docs/release.md`). |

**Rationale.** Testers give the most useful feedback on the camera, and the Guided Scan has Story 10.8's GA recommendation plus fixture evidence. Shipping both scans in one build avoids a second Data safety and privacy-policy change. Putting the Quick Scan beta label in the same build sets Learners' expectations. Cutting production as its own tag keeps 10.11's "last PR before the tag" rule true for 0.11.0 and gives 12.5 a build of its own to gate.

**Effort.** Small for the planning edits. 10.11 run 1 is about a day of release prep plus the site PR and Play Console steps. The Quick Scan port is the long pole, and its size is set by the spike session.

**Risks**
- **Quick Scan readiness gates 0.11.0.** If the port, its spine and PRD amendments or the size measurement slip, the fallback is to run 10.11 with `CAMERA_SCAN` + `PLACEMENT` only (0.11.0) and raise `QUICK_SCAN` in a 10.11-n (0.11.x). The PO decides at 10.11 start. The Lenovo TB128XU stays the gating minimum for the Quick Scan BETA call unless a separate PO decision changes it.
- **GA on narrow evidence.** Story 10.8 measured warm light only, and `docs/scan-accuracy.md` is fixture-based (A2, studio light); neither is the full OQ-8 four-light matrix. GA rests on 10.8's recommendation, the PO's decision and real usage. The 0.11.0 pilot records SM-8, and a drop below target sends `CAMERA_SCAN` back to BETA through a 10.11-n.
- **PLACEMENT at BETA** follows Story 10.8 (no scanned end-to-end Placement run yet). Raising it to GA needs that run and a new PO decision.

## 4. Detailed change proposals

Every OLD block quotes the file verbatim; every NEW block keeps the OLD text and adds to it unless it says otherwise.

### 4.1 Epics (`_bmad-output/planning-artifacts/epics.md`)

**Epic 10 order line (line 236), after the SCP 2026-11-21 annotation**

OLD:
> *(SCP 2026-11-21 quick-scan-side-face-accuracy, #274: 10.20 and 10.21 do not block Story 10.11, which raises `CAMERA_SCAN` and `PLACEMENT` with `QUICK_SCAN` OFF; Quick Scan is raised in a later 10.11-n after 10.21's BETA call)*

NEW:
> *(SCP 2026-11-21 quick-scan-side-face-accuracy, #274: 10.20 and 10.21 do not block Story 10.11, which raises `CAMERA_SCAN` and `PLACEMENT` with `QUICK_SCAN` OFF; Quick Scan is raised in a later 10.11-n after 10.21's BETA call)* *(SCP 2026-11-24 camera-release-timeline: 10.21's BETA call (2026-11-24) left `QUICK_SCAN` OFF on this pipeline; 10.24–10.27 are superseded by the continuous facelet scan approach (PR #363) and do not start. The new Quick Scan port stories, written by the spike session's SCP, come before the 10.11 run that raises `QUICK_SCAN`. 10.11 no longer waits for 12.5: its first run, release 0.11.0, raises `CAMERA_SCAN` to GA and `PLACEMENT` to BETA, and `QUICK_SCAN` to BETA only when its preconditions hold (Story 10.11); 12.5 then promotes a new v1.0.0 tag with the same levels.)*

**Epic 10 "Never blocks" line (line 238)**

OLD:
> *(SCP 2026-11-21 quick-scan-side-face-accuracy: `QUICK_SCAN` leaves this first raise and follows Story 10.21.)*

NEW:
> *(SCP 2026-11-21 quick-scan-side-face-accuracy: `QUICK_SCAN` leaves this first raise and follows Story 10.21.)* *(SCP 2026-11-24 camera-release-timeline: Story 10.11 now runs after Stories 12.3, 12.4 and 13.6 and before 12.5. Its first run is release 0.11.0, and Story 12.5 promotes a v1.0.0 tag cut from main with the same flag levels.)*

**Epic 12 "Epic 13" line (line 249)**

OLD:
> *(Superseded by SCP 2026-11-20: Stories 10.11, 10.17, 10.18 and Epics 14–15, plus the #249/#251/#252/#253 fixes, are v1.0 scope and run before Stories 12.3, 12.4 and 12.5.)*

NEW:
> *(Superseded by SCP 2026-11-20: Stories 10.11, 10.17, 10.18 and Epics 14–15, plus the #249/#251/#252/#253 fixes, are v1.0 scope and run before Stories 12.3, 12.4 and 12.5.)* *(SCP 2026-11-24 camera-release-timeline: Story 10.11 runs after 12.3, 12.4 and 13.6 and before 12.5; 12.5's production promotion carries the camera build as a v1.0.0 tag.)*

**SCP 2026-11-20 v1.1 promotion paragraph (line 269), release order**

OLD:
> Release Stories 12.3, 12.4 and 12.5 run before Story 10.11, which depends on them and is the last PR before the tag.

NEW:
> Release Stories 12.3, 12.4 and 12.5 run before Story 10.11, which depends on them and is the last PR before the tag. *(SCP 2026-11-24 camera-release-timeline: 12.5 now runs after 10.11. 10.11's flag-raise PR is the last PR before the `v0.11.0` tag; production is a new `v1.0.0` tag cut from main with the same flag levels, gates 3 and 6 are re-run on it, and 12.5 records the gates on the 1.0.0 build.)*

**SCP 2026-11-20 v1.1 promotion paragraph (line 269), CASE_RECOGNITION**

OLD:
> `CASE_RECOGNITION` (Story 11.3) is not a dependency and keeps its own release decision at 10.11.

NEW:
> `CASE_RECOGNITION` (Story 11.3) is not a dependency and keeps its own release decision at 10.11. *(SCP 2026-11-24 camera-release-timeline, PO: `CASE_RECOGNITION` stays OFF in 0.11.0.)*

**Story 10.11, Runs / Depends on**

OLD:
> **Depends on** Story 10.8's recommendation for every raised feature, and on Stories 12.3, 12.4, 12.5 and 13.6 being done (§6 Q5).

NEW:
> **Depends on** Story 10.8's recommendation, or a PO level decision recorded in the PRD memlog with its evidence, for every raised feature, and on Stories 12.3, 12.4 and 13.6 being done. *(SCP 2026-11-24 camera-release-timeline: 12.5 removed, superseding §6 Q5. A run may ship to the internal, closed and open testing tracks before 12.5. Production stays in 12.5, as a new `v1.0.0` tag cut from main with the same flag levels. A run that raises `QUICK_SCAN` also depends on: the new Quick Scan port stories being done with their BETA call recorded, the Lenovo TB128XU staying the gating minimum unless a separate PO decision changes it; the spike session's SCP amending the architecture spine and PRD (AD-9 reader of record, AD-18 composition, `ShadowReaderPolicy`, `CameraSizePolicy`, FR-22 and §4.8's out-of-scope "continuous video"); and `:app:checkCameraSize` and `:app:checkReleaseSize` headroom measured with the new models in (the research estimate is about 2.0–2.5 MB).)*

**Story 10.11, annotation**

OLD:
> *(SCP 2026-11-21 quick-scan-side-face-accuracy: the first run raises `CAMERA_SCAN` and `PLACEMENT` only; `QUICK_SCAN` is raised by a later 10.11-n once Story 10.21's BETA call passes.)*

NEW:
> *(SCP 2026-11-21 quick-scan-side-face-accuracy: the first run raises `CAMERA_SCAN` and `PLACEMENT` only; `QUICK_SCAN` is raised by a later 10.11-n once Story 10.21's BETA call passes.)* *(SCP 2026-11-24 camera-release-timeline, superseding SCP 2026-11-21: the first run, release 0.11.0, raises `CAMERA_SCAN` to GA and `PLACEMENT` to BETA, following Story 10.8, and `QUICK_SCAN` to BETA only when the `QUICK_SCAN` preconditions under Depends hold. Otherwise it raises `CAMERA_SCAN` and `PLACEMENT` only, and `QUICK_SCAN` follows in a 10.11-n (0.11.x); the PO decides at 10.11 start. `CASE_RECOGNITION` stays OFF in 0.11.0.)*

**Story 10.11, AC1**

OLD:
> and the PR is the last one merged before the `vX.Y.Z` tag

NEW:
> and the PR is the last one merged before the `vX.Y.Z` tag *(SCP 2026-11-24 camera-release-timeline: for the first run that tag is `v0.11.0`; the production `v1.0.0` tag is cut later from main by Story 12.5 with the same flag levels)*

**Story 10.11, AC2 (conflicts with docs/release.md:25)**

OLD:
> **When** its privacy page is updated right after the app PR merges

NEW:
> **When** its privacy page is updated before the app PR merges or, while `APP_REF` is pinned to an earlier tag (`v0.10.1` as of 2026-11-24), before the `vX.Y.Z` tag; `APP_REF` moves to the new tag only after its release run passes (`docs/release.md` § Point the website at the release) *(SCP 2026-11-24 camera-release-timeline: replaces "right after the app PR merges", which fails the site check whenever `APP_REF` is `main`)*

**Story 10.11, AC4**

OLD:
> the store listing's description names the feature with "(beta)" and says the camera reads colors on the device and nothing is saved or sent, and PRD §7 gate 6 is checked for each raised feature (manual fallback works; no unvalidated Scan Result is used), linking 10.8's evidence

NEW:
> the store listing's description names each raised feature, with "(beta)" only for a BETA feature (the GA Guided 6-face Scan carries no beta label), and says the camera reads colors on the device and nothing is saved or sent, and PRD §7 gate 6 is checked for each raised feature (manual fallback works; no unvalidated Scan Result is used), linking 10.8's evidence or, where 10.8 has none, the PO's recorded evidence; and `docs/pilot-session-protocol.md` adds a scan task for each raised feature (a Guided Scan, a Placement run, and for `QUICK_SCAN` a Quick Scan with the beta note read aloud), so the pilot records SM-8 *(SCP 2026-11-24 camera-release-timeline)*

**Story 10.21, last AC**

OLD:
> If it is, a later Story 10.11 run (`10.11-n`) raises `QUICK_SCAN`; the level itself still follows the recognition matrix (Story 10.8). If it is not, Quick Scan stays OFF and the reasons go to a new issue.

NEW:
> If it is, a later Story 10.11 run (`10.11-n`) raises `QUICK_SCAN`; the level itself still follows the recognition matrix (Story 10.8). If it is not, Quick Scan stays OFF and the reasons go to a new issue. *(SCP 2026-11-24 camera-release-timeline: the PO's call on 2026-11-24 was "not viable", so `QUICK_SCAN` stays OFF on this pipeline (action item `epic-10-deferred-10-21-po-beta-quick-scan-off-pipeline-rethink`). Quick Scan returns through the continuous facelet scan approach (PR #363) and its own BETA call, not through 10.24–10.27.)*

**Story 10.24, under the title**

OLD:
> ### Story 10.24: Retake a corner in Quick Scan

NEW:
> ### Story 10.24: Retake a corner in Quick Scan
>
> *(SCP 2026-11-24 camera-release-timeline: superseded by the continuous facelet scan approach (PR #363). Parked until the spike session's SCP replaces or drops it. Do not start.)*

**Story 10.25, under the title**

OLD:
> ### Story 10.25: Quick Scan starts from whichever corner it is shown

NEW:
> ### Story 10.25: Quick Scan starts from whichever corner it is shown
>
> *(SCP 2026-11-24 camera-release-timeline: superseded by the continuous facelet scan approach (PR #363). Parked until the spike session's SCP replaces or drops it. Do not start.)*

**Story 10.26, under the title**

OLD:
> ### Story 10.26: Quick Scan fills a few unread stickers from the cube's rules

NEW:
> ### Story 10.26: Quick Scan fills a few unread stickers from the cube's rules
>
> *(SCP 2026-11-24 camera-release-timeline: superseded by the continuous facelet scan approach (PR #363). Parked until the spike session's SCP replaces or drops it. Do not start.)*

**Story 10.27, under the title**

OLD:
> ### Story 10.27: Clean the corner fixture labels and judge pastel stickers against the cube's own colours

NEW:
> ### Story 10.27: Clean the corner fixture labels and judge pastel stickers against the cube's own colours
>
> *(SCP 2026-11-24 camera-release-timeline: superseded by the continuous facelet scan approach (PR #363). Parked until the spike session's SCP replaces or drops it. Do not start.)*

**Story 10.26, Depends**

OLD:
> **Starts** only after the PO's 10.21 BETA call (Story 10.21's last AC), with the held-out report in hand; the safety assertion (first AC) is built and run before any search.

NEW:
> **Starts** only after the PO's 10.21 BETA call (Story 10.21's last AC), with the held-out report in hand; the safety assertion (first AC) is built and run before any search. *(SCP 2026-11-24 camera-release-timeline: that call (2026-11-24) left `QUICK_SCAN` OFF, so this story does not start; it is superseded with 10.24, 10.25 and 10.27.)*

**Story 12.3, AC1**

OLD:
> no runtime permissions (v1.0 is camera-free)

NEW:
> no runtime permissions (v1.0 is camera-free) *(SCP 2026-11-24 camera-release-timeline: this held for the camera-OFF builds through 0.10.x, where 12.3 was accepted; from 0.11.0, and in v1.0.0, the one runtime permission is `CAMERA`, checked by Story 10.11 AC1 and gate 6)*

**Story 12.4, gate AC**

OLD:
> and gate 6 is recorded N/A for v1.0 (camera flags OFF in release, no `CAMERA` permission)

NEW:
> and gate 6 is recorded N/A for v1.0 (camera flags OFF in release, no `CAMERA` permission) *(SCP 2026-11-24 camera-release-timeline: N/A held for the camera-OFF 0.10.x builds on which 12.4 was accepted. v1.0.0 raises camera flags, so gate 6 is checked per feature by Story 10.11 and re-run on the 1.0.0 tag, which Story 12.5 records)*

**Story 12.5, AC4**

OLD:
> **Given** all gates pass
> **When** the build is promoted
> **Then** it moves closed testing → production manually in Play Console

NEW:
> **Given** all gates pass on the `v1.0.0` build, including PRD §7 gate 6 for every camera feature it raises (Story 10.11)
> **When** the build is promoted
> **Then** it moves closed testing → production manually in Play Console *(SCP 2026-11-24 camera-release-timeline: production is a new `v1.0.0` tag cut from main with the same flag levels as 0.11.0; gates 3 (a green Release pilot run) and 6 are re-run on that tag and recorded here, and gate 4 is cleared by the 0.11.0 pilot record)*

### 4.2 PRD (`prds/prd-RubikXplorer-2026-10-29/prd.md` and `.memlog.md`)

**PRD §7 gate 6**

OLD:
> (v1.0 included: the manifest declares no CAMERA permission). For each raised feature, it is checked by Story 10.11 with Story 10.8's evidence.

NEW:
> (the 0.9.x–0.10.x pilot builds: the manifest declares no CAMERA permission). For each raised feature, it is checked by Story 10.11 with Story 10.8's evidence, or the PO's recorded evidence where 10.8 has none, and re-run on the v1.0.0 tag by Story 12.5. *(SCP 2026-11-24 camera-release-timeline: from 0.11.0, and in v1.0, `CAMERA_SCAN` is GA and `PLACEMENT` BETA, with `QUICK_SCAN` BETA once its preconditions hold, so gate 6 applies to v1.0.)*

**PRD `.memlog.md`, new lines appended at the end:**

> - (decision by user) PO level decisions for the 0.11.0 camera release (SCP 2026-11-24 camera-release-timeline, PO hebelmx 2026-11-24): CAMERA_SCAN GA, citing Story 10.8's GA recommendation (2026-11-18 entry above; docs/spikes/oq-8-recognition-matrix.md; device session C, warm light only, Lenovo TB128XU + S24 FE, 240/240 stickers, 0 confidently wrong) as primary evidence and docs/scan-accuracy.md (Story 10.1, A2, studio light, Kotlin reader 432/432 stickers, 8/8 scans Full, 0 confidently wrong) as support, plus PO device use; daylight, cool and low light are not measured, and the 0.11.0 pilot records SM-8. Supersedes the 2026-11-15 BETA-only entry (Story 10.10 AC3). PLACEMENT BETA, following Story 10.8 (no scanned end-to-end Placement run yet). QUICK_SCAN BETA via the continuous facelet scan approach (PR #363), only once its port stories and BETA call are done (the Lenovo TB128XU stays the gating minimum unless a separate PO decision changes it), the spike session's SCP has amended AD-9, AD-18, ShadowReaderPolicy, CameraSizePolicy, FR-22 and §4.8's out-of-scope "continuous video", and checkCameraSize/checkReleaseSize headroom is measured (research estimate about 2.0–2.5 MB); otherwise 0.11.0 raises CAMERA_SCAN and PLACEMENT only and QUICK_SCAN follows in a 10.11-n. CASE_RECOGNITION stays OFF in 0.11.0. A miss on SM-8 or gate 6 returns a feature to BETA (or OFF) via a 10.11-n.
>
> - (decision by user) Release order (SCP 2026-11-24 camera-release-timeline, PO hebelmx 2026-11-24), superseding Q5 and Q8 of the 2026-11-14 entry: a camera flag may go above OFF once Stories 12.3, 12.4 and 13.6 are done; 12.5 no longer comes first. Story 10.11's first run is release 0.11.0 (closed testing); its flag-raise PR is the last PR before the v0.11.0 tag. The site privacy PR (camera sentence) merges before the flag-raise PR, or APP_REF stays pinned to v0.10.1 until the tag, and APP_REF moves to v0.11.0 after its release run passes (docs/release.md:25). The gate-4 pilot sessions and SM-8 run on 0.11.0; 0.10.1 is install and smoke only, and the 0.11.0 pilot record clears 12.5. Production is a new v1.0.0 tag cut from main with the same flag levels as 0.11.0; gates 3 and 6 are re-run on that tag and Story 12.5 records the gates on the 1.0.0 build.
>
> - (decision by user) Story 10.21 BETA call by PO hebelmx 2026-11-24: QUICK_SCAN stays OFF, because the Quick Scan pipeline does not work on device (near-zero side-face coverage; the calibration centre check fails even right after calibrating; docs/evidence/10-21/device-session-notes.md). Stories 10.24–10.27 are superseded by the continuous facelet scan approach (PR #363, SCP 2026-11-24 camera-release-timeline).
>
> - (change) PRD updated (SCP 2026-11-24 camera-release-timeline): §7 gate 6's N/A sentence now covers the camera-OFF 0.9.x–0.10.x builds instead of v1.0, accepts the PO's recorded evidence where Story 10.8 has none, and is re-run on the v1.0.0 tag by Story 12.5. No FR or target change.

### 4.3 AGENTS.md

**AGENTS.md, project summary (line 6)**

OLD:
> v1.0 ships camera-free; v1.1 on-device camera recognition (Epics 10–11) is built on `main` behind feature flags that stay OFF in release.

NEW:
> On-device camera recognition (Epics 10–11) is built on `main` behind feature flags that stay OFF in release until Story 10.11 raises them: builds through 0.10.x are camera-free; 0.11.0 and v1.0 ship the Guided 6-face Scan as GA and Placement as BETA, plus the new Quick Scan as BETA once its port stories pass (SCP 2026-11-24 camera-release-timeline).

**AGENTS.md, camera work rule (line 25)**

OLD:
> Camera, scan, Quick Check, Placement or Case-recognition work is v1.1: plan it from Epics 10–11

NEW:
> Camera, scan, Quick Check, Placement or Case-recognition work is v1.0 scope (SCP 2026-11-20; release levels per SCP 2026-11-24 camera-release-timeline): plan it from Epics 10–11

### 4.4 Sprint status (`_bmad-output/implementation-artifacts/sprint-status.yaml`)

Story statuses: unchanged. 10.11 stays `backlog`, and 10.24–10.27 stay `backlog` with the superseded note in epics.

**sprint-status, action item epic-12-deferred-12.4-green-release-device-run**

OLD:
> Gate 3: after the next release tag, record its green Release pilot run (Key Flow suite and device instrumentation; the last green one is v0.9.0 43358827489, v0.10.0 run 45514196023 failed and its tag was deleted) in docs/release-gates-v1.0.md, before Story 12.5 promotes to production (medium; deferred from 12.4, docs/release-gates-v1.0.md)

NEW:
> Gate 3: record v0.10.1's green Release pilot run 45531395685 (versionCode 3, signed tag v0.10.1 at f53f676ef58a92e0727daccd72bcd2e1af270631, uploaded to internal testing 2026-11-25 02:46 UTC; v0.10.0 run 45514196023 failed and its tag was deleted) in docs/release-gates-v1.0.md, then re-run gate 3 on the v1.0.0 tag and record that green run before Story 12.5 promotes to production (SCP 2026-11-24 camera-release-timeline) (medium; deferred from 12.4, docs/release-gates-v1.0.md)

**sprint-status, action item epic-12-deferred-12.4-pilot-sm-baselines**

OLD:
> Gate 4: run the #127 beginner pilot sessions and write the SM-1 to SM-7 and SM-9 baselines and the pilot record into docs/release-gates-v1.0.md, triaging any new severity-1/2 issue, before Story 12.5 promotes to production; see epic-7-retro-item-66 for the tag and install part (medium; deferred from 12.4, docs/release-gates-v1.0.md)

NEW:
> Gate 4: run the #127 beginner pilot sessions on 0.11.0 (0.10.1 is install and smoke only) and write the SM-1 to SM-9 baselines, SM-8 included, and the pilot record into docs/release-gates-v1.0.md, triaging any new severity-1/2 issue; the 0.11.0 pilot record is what clears Story 12.5's production promotion (SCP 2026-11-24 camera-release-timeline); see epic-7-retro-item-66 for the tag and install part (medium; deferred from 12.4, docs/release-gates-v1.0.md)

**sprint-status, action item epic-12-deferred-12.4-release-order-10-11-vs-12-5**

OLD:
>     status: open
>     ref: "_bmad-output/implementation-artifacts/spec-12-4-pilot-findings-fixed-and-release-gates-passed.md"
>   - id: "issue-318-deferred-missing-jpeg-says-no-cube"

NEW:
>     status: done
>     ref: "_bmad-output/implementation-artifacts/spec-12-4-pilot-findings-fixed-and-release-gates-passed.md"
>   - id: "issue-318-deferred-missing-jpeg-says-no-cube"

**New action items, appended at the end of `action_items`:**

```yaml
  - id: "scp-2026-11-24-quick-scan-port-stories"
    epic: 10
    action: "The continuous facelet scan spike session writes the Quick Scan port stories through correct-course (Android port behind QUICK_SCAN, device benchmark on the Lenovo TB128XU, Galaxy S24 FE and Galaxy Tab S7 FE 5G with the Lenovo as gating minimum, BETA call) and replaces or drops 10.24-10.27 (high; SCP 2026-11-24 camera-release-timeline)"
    owner: "PO (BMad)"
    status: open
    ref: "_bmad-output/planning-artifacts/sprint-change-proposal-2026-11-24-camera-release-timeline.md"
  - id: "scp-2026-11-24-quick-scan-raise-preconditions"
    epic: 10
    action: "Before Story 10.11 raises QUICK_SCAN: the spike session's SCP amends the architecture spine and PRD (AD-9 reader of record, AD-18 composition, ShadowReaderPolicy, CameraSizePolicy, FR-22, PRD 4.8 out-of-scope continuous video), and :app:checkCameraSize and :app:checkReleaseSize are measured with the new models in (research estimate about 2.0-2.5 MB headroom) (high; SCP 2026-11-24 camera-release-timeline)"
    owner: "PO (BMad)"
    status: open
    ref: "_bmad-output/planning-artifacts/sprint-change-proposal-2026-11-24-camera-release-timeline.md"
  - id: "scp-2026-11-24-site-order-0-11-0"
    epic: 10
    action: "For the 0.11.0 camera release, follow docs/release.md:25: merge the site privacy PR (camera sentence) before the flag-raise PR, or keep APP_REF pinned to v0.10.1 (it is as of 2026-11-24) until the tag; push v0.11.0; move APP_REF to v0.11.0 only after its release run passes (medium; SCP 2026-11-24 camera-release-timeline)"
    owner: "PO (BMad)"
    status: open
    ref: "_bmad-output/planning-artifacts/sprint-change-proposal-2026-11-24-camera-release-timeline.md"
```

## 5. Implementation handoff

**Scope: Moderate** (backlog reorder plus PO decisions; no architecture change in this SCP).

| Who | Does |
| --- | --- |
| Correct-course (this branch) | Apply §4.1–§4.4 to `epics.md`, `prd.md`, the PRD `.memlog.md`, `AGENTS.md` and `sprint-status.yaml`; branch `docs/scp-2026-11-24-camera-release-timeline`. |
| Spike session (PO + dev) | Turn the continuous facelet scan into port stories through its own SCP, amending AD-9, AD-18, `ShadowReaderPolicy`, `CameraSizePolicy`, FR-22 and §4.8; measure camera and release size; benchmark on the Lenovo, S24 FE and Tab S7 FE (Lenovo gating); record the Quick Scan BETA call. |
| Dev, via `/bmad-orchestrate story 10-11-…` | 10.11 run 1 → release-prep PR (after the site PR, or with `APP_REF` pinned) → tag `v0.11.0` (Key Flow suite run locally first). |
| PO | Site privacy PR review; `APP_REF` → `v0.11.0` after the release run; Play Console Data safety + camera permission; promote 0.11.0 to closed testing; pilot with scan tasks on 0.11.0; later cut `v1.0.0` and run 12.5. |

**Success criteria**
- 0.11.0 is in closed testing with the Guided Scan unlabeled (GA), Placement labeled beta and, if raised, Quick Scan labeled beta; Case recognition has no entry point.
- The privacy policy, `privacy-facts.json` (schema 2) and Play Data safety agree, and the site check passed at each `APP_REF` move.
- Gate 6 in `docs/release-gates-v1.0.md` is checked per feature, and the 0.11.0 pilot record (SM-8 included) is written.
- Production (12.5) promotes a `v1.0.0` tag with the same flag levels, only after gates 1–6 pass, with gates 3 and 6 re-run on that tag.

## 6. Adversarial review (2026-11-24)

Applied, with the PO's decisions where a finding offered a choice:
- **GA evidence:** §1 item 2 corrected. Story 10.8's GA recommendation (memlog 2026-11-18, `docs/spikes/oq-8-recognition-matrix.md`) is the primary evidence, `docs/scan-accuracy.md` the support; 10.8 covered warm light only.
- **Placement:** BETA, following 10.8. Timeline, annotations, memlog and success criteria updated.
- **Production build (option b):** production is a new `v1.0.0` tag from `main` with 0.11.0's flag levels; gates 3 and 6 re-run on it; 10.11's "last PR before the tag" applies to 0.11.0; 12.5 records gates on 1.0.0 (Epic 10 and 12 order lines, 10.11 AC1, 12.5 AC4, PRD gate 6).
- **Pilot:** gate-4 sessions and SM-8 run on 0.11.0; 0.10.1 is install and smoke only; the 0.11.0 pilot record clears 12.5 (timeline, gate-4 action item).
- **Site order:** follows `docs/release.md:25`; 10.11 AC2's "right after the app PR merges" conflict fixed; new action item `scp-2026-11-24-site-order-0-11-0`.
- **Quick Scan preconditions:** the spike session's SCP amends AD-9, AD-18, `ShadowReaderPolicy`, `CameraSizePolicy`, FR-22 and §4.8; measured size headroom (about 2.0–2.5 MB estimated) is required; the Lenovo TB128XU stays the gating minimum. This SCP does not edit the spine (10.11 Depends, new action item `scp-2026-11-24-quick-scan-raise-preconditions`).
- **Stale passages now covered:** epics order lines near 236, 238, 249 and 269; 10.21's last AC; 10.26's Depends; 10.11 AC1, AC2 and AC4 (beta label only for BETA features); 12.3 AC1 and 12.4 gate-AC annotations; PRD memlog lines superseding Q5/Q8 and the 2026-11-15 BETA-only entry and recording the 10.21 OFF call; AGENTS.md's camera-free and v1.1 lines.
- **Gate 3 evidence:** the v0.10.1 green Release pilot run 45531395685 is recorded in the gate-3 action item.
- **CASE_RECOGNITION (low):** stays OFF in 0.11.0, recorded in epics (line 269 and 10.11 annotation) and the memlog.

Skipped: the two other low findings, by the PO's instruction.
