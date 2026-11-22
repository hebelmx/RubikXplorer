# Sprint Change Proposal: Quick Scan side-face accuracy (2026-11-21)

**Status: Approved by the product owner (`hebelmx`) on 2026-11-21, after a review pass (3 of 5 findings applied: calibration match on face centres, held-out split minimums, one release-wide BETA call). Section 4 planning edits are applied on branch `docs/scp-2026-11-21-quick-scan-side-face-accuracy`.**

- **Trigger:** GitHub issue [#274](https://github.com/hebelmx/RubikXplorer/issues/274), deferred from Story 10.19 (PR #272, merged `1d4a015e`) by PO decision on 2026-11-21.
- **Mode:** Batch (Fast path: one round of questions, all four recommended answers chosen by the PO on 2026-11-21).
- **Scope:** **Moderate.** Two new stories are added to Epic 10, plus an annotation on 10.19, an Epic 10 order line, three linked sprint-status action items and small notes in the PRD memlog, the AD-9 spine and EXPERIENCE.md. No new epic, AD, module, dependency or permission. Quick Scan stays OFF in release, and Story 10.11 no longer waits for it.
- **Evidence:** [`docs/evidence/10-19/light-check-and-replay.md`](../../docs/evidence/10-19/light-check-and-replay.md) (device session section, runs L1–L8 and S1–S6), the raw `CubentiaScanDiag` logs `.git/bmad-orchestrate/d1-lenovo-*.log` and `d3-s24-*.log`, and `QuickScanSideFaceReplayTest` on the pre-10.19 `Fixtures/corner/` groups.

---

## 1. Issue Summary

Story 10.19 merged as the Quick Scan **lighting pipeline**: the light check with coaching, metering on the cube, the torch, the calibration offer, the centre check and the per-capture diagnostics. Its accuracy acceptance criteria were deferred to #274.

| 10.19 AC | Deferred item |
| --- | --- |
| AC2 | 10 front-lit and 10 backlit light-check attempts per device: false and missed blocks (only partial counts exist). |
| AC4 | Torch glare on a glossy cube on the S24 FE, and its coaching (not tested). |
| AC6 | Side-face replay bar per device and light: 0 confidently wrong, ≥ 90% of side stickers correct, ≥ 3 of 4 side faces kept, measured on new 10.19-pipeline fixtures (none recorded). |
| AC7 | `evaluate_model.py --fixtures-corner` on held-out captures, with ≥ 20 positives and ≥ 10 negatives per device. |
| AC8 | The PO's Quick Scan BETA call. |

**What the device session showed (2026-11-21):**

- **Safety holds:** no kept face showed a confidently wrong sticker in any run (AC5 met).
- **Coverage fails:**
  - **Galaxy S24 FE:** 0 of 6 faces kept in all runs (S2–S6), with the torch on and off, before and after recalibrating. Each face goes to the Guided grid for 1–8 close-call or disagreement stickers.
  - **Lenovo TB128XU** (calibrated, front-lit): 3–5 of 6 faces kept, side faces 1/4 to 3/4 (L7, L8). Runs without a calibration, or with another cube's, sent all 6 faces to the grid (L4, L5).
- **Root causes found in the per-capture diagnostics:**
  1. **Keep rule too strict:** a single close-call sticker sends a whole face to the grid (L8: F and L at 8/1, `closeCall=1`).
  2. **Agreement gate:** the shaded back face disagrees in every calibrated Lenovo run (B, `disagree` 4–9).
  3. **Calibration light differs from scan light:** the calibration screen has no torch, so S24 calibrations are torch-off while Quick Scan opens with the torch on (S5).
  4. **Light cut with no margin:** the calibrated cut 1.34 sits inside ordinary S24 torch-off light (S4 1.226–1.610, S6 1.37–1.39). Hold and release flip, and a well-lit cube is held. The uncalibrated 27 L* cut depends on the cube's colours (L3). Both cuts were fitted on 16 Lenovo holds only.

**Problem classification:** a technical limitation found in the 10.19 device gate. The thresholds and rules were provisional by design and must be fitted on real 10.19-pipeline captures.

---

## 2. Impact Analysis

### Change Navigation Checklist

| # | Item | Status | Finding |
| --- | --- | --- | --- |
| 1.1 | Trigger story | `[x]` | Story 10.19 (PR #272), device session 2026-11-21; issue #274. |
| 1.2 | Core problem | `[x]` | Kept faces are safe, but too few are kept: an all-9 keep rule, an agreement gate that fails on shaded faces, calibration light that differs from scan light, and provisional light cuts. |
| 1.3 | Evidence | `[x]` | Evidence doc tables L1–L8 and S1–S6, diagnostics logs, the replay test. |
| 2.1 | Current epic (Epic 10) | `[!]` | Completable: add Stories 10.20 and 10.21. |
| 2.2 | Epic-level changes | `[x]` | Two appended stories; the Epic 10 order line changes. |
| 2.3 | Remaining epics | `[x]` | Epic 11 (Quick Scan lives under FR-22) is unaffected beyond the BETA call moving to 10.21. Epics 12–15 are unaffected. |
| 2.4 | Obsolete / new epics | `[N/A]` | None. |
| 2.5 | Order | `[!]` | **Story 10.11 no longer waits for Quick Scan** (PO 2026-11-21). It raises `CAMERA_SCAN` and `PLACEMENT` per Story 10.8, with `QUICK_SCAN` OFF, as Story 10.8 already recommended (PRD memlog 2026-11-18). Quick Scan is raised in a later `10.11-n` once 10.21's BETA call passes. 10.20 → 10.21 run in parallel with, or after, 10.11. |
| 2.6 | Superseded wording | `[!]` | The Epic 10 *Never blocks* note says 10.11 "raises `CAMERA_SCAN`, `QUICK_SCAN` and `PLACEMENT`". Annotate it: `QUICK_SCAN` follows 10.21. |
| 3.1 | PRD | `[x]` | No FR or target changes: FR-22's "beta unless it meets its own targets" and the §4.8 Quick Scan GA row still apply. One memlog line records the PO decisions. |
| 3.2 | Architecture | `[!]` | AD-9 gets one clause: the saved calibration records the torch state it was taken under, and Quick Scan treats a torch mismatch as no calibration. The Room `ColorCalibrationEntity` gains a nullable column (a migration). No new AD. |
| 3.3 | UX | `[!]` | EXPERIENCE.md: the Quick Scan capture row (a kept face may carry one blank sticker for review) and the calibration torch. DESIGN.md is unchanged; the calibration screen reuses the 10.19 torch toggle component. |
| 3.4 | Other artifacts | `[!]` | sprint-status gains two stories, and three existing 10.19 action items are linked to 10.21 (*torch requested vs lit*, *light clear steadiness*, *cal offer reappears*). The `side-face-accuracy` item is closed by this SCP. `Fixtures/corner/` grows (10.20). No change to CI or the release gates. |
| 4.1 | Option 1: direct adjustment | Viable | Add two stories within Epic 10. Effort medium; risk medium (the bar may still not be met). |
| 4.2 | Option 2: rollback | Not viable | 10.19's pipeline works and is safe. Reverting it gains nothing. |
| 4.3 | Option 3: MVP review | Not needed | Quick Scan already sits first on the PRD cut order and is OFF in release. v1.0 ships without it if 10.21 fails. |
| 4.4 | Selected path | `[x]` | **Option 1**, plus the order change in 2.5. |

### Epic impact
- **Epic 10:** two new stories, 10.20 and 10.21. 10.19 is annotated with its deferred ACs.
- **Story 10.11:** runs without waiting; `QUICK_SCAN` stays OFF in its first run.

### Technical impact
- `:core:domain` recognition (`QuickScanClassification`, `SideLight`, `CornerHold`, `QuickScan`): the keep rule, the agreement gate, hysteresis and refitted cuts.
- `app/src/camera/`: a torch on the calibration screen (and in the fixture recorder if it has none); the torch is skipped only while lit.
- `app/src/main/.../data/`: `ColorCalibrationEntity` gets a nullable `torchOn` column, with a Room migration and a migration test.
- Fixtures: new `Fixtures/corner/` groups with `group.json` (`"pipeline": "10.19"`).

---

## 3. Recommended Approach

**Direct adjustment, in two stories, with Story 10.11 decoupled.**

- **Why two stories:** tuning needs data that does not exist yet. 10.20 is one device session where the PO records the fixtures and runs the corner-model check offline (small code: recorder metadata and a torch). 10.21 is offline tuning against the replay, followed by one live validation session. Each story then has exactly one human gate, which matches how 10.18 and 10.19 split.
- **Why decouple 10.11:** Story 10.8 already recommended Quick Scan OFF. Waiting would put the v1.0 release behind the riskiest camera feature, which is first on the PRD cut order anyway.
- **Effort:**
  - 10.20: small to medium (recorder metadata and torch; one device session of about 1–2 h on two devices).
  - 10.21: medium (tuning, a migration, a calibration-screen torch, one device session).
- **Risk:** medium. 10.21 may still miss the bar on the S24 FE. In that case Quick Scan stays OFF, or the PO makes one BETA call for the whole release from the evidence on both devices (AD-18 flag levels are per build type, not per device). Either way v1.0 is not affected.
- **Timeline:** no impact on v1.0, since 10.11 proceeds.

---

## 4. Detailed Change Proposals

### 4.1 Stories (`_bmad-output/planning-artifacts/epics.md`)

#### 4.1.1 Story 10.19: annotate the deferral

Section: after the **Human gate** paragraph of Story 10.19.

OLD: *(none)*

NEW:
> *(SCP 2026-11-21 quick-scan-side-face-accuracy, #274: 10.19 merged as the Quick Scan lighting pipeline (PR #272). The AC2 counts, AC4 glossy-cube glare, AC6 side-face bar, AC7 held-out metrics and AC8 BETA call move to Stories 10.20 and 10.21; the provisional light cuts (27 L*, 1.34) are refitted in 10.21.)*

Rationale: the story is `done` but its ACs are only partly met. The annotation keeps the trail honest.

#### 4.1.2 New Story 10.20: Record 10.19-pipeline corner fixtures and check the corner model

Insert after Story 10.19.

> ### Story 10.20: Record 10.19-pipeline corner fixtures and check the corner model
>
> As the developer tuning Quick Scan,
> I want labelled corner captures recorded through the 10.19 pipeline on both reference devices,
> So that the keep rule, the agreement gate and the light cuts are fitted and scored on real data, per device and light.
>
> **Refs:** FR-22 · recognition NFRs (Quick Scan, Confidence calibration) · AD-9, AD-15 · OQ-8 · Stories 10.10, 10.12, 10.18, 10.19 · #274
>
> **Depends on** Story 10.19. **Human gate:** one recording session on the debug fixture recorder (corner mode), on the Lenovo Tab M10 Plus 3rd Gen (TB128XU, no flash) and the Galaxy S24 FE (flash). *(SCP 2026-11-21 quick-scan-side-face-accuracy.)*
>
> **Acceptance Criteria:**
>
> **Given** the debug fixture recorder in corner mode
> **When** an operator records a capture group
> **Then** the group's `group.json` records `"pipeline": "10.19"`, the device model, the cube id (`stla`/`stlb`/`stka`/`stkb`, or a new id for a glossy cube), the light direction (`front`/`back`/`side`), the light name, the torch state per hold, and the recorder calibration it took. On a device with a flash, the recorder offers the same torch toggle as the Quick Scan corner view (on by default), and its calibration is taken with the torch state the operator chose. Each hold stores the 10.19 per-capture diagnostics (`light=`, `lightScore=`, `lightMode=`, metering settle) next to the frame.
>
> **Given** the recording session
> **When** it ends
> **Then** `Fixtures/corner/` holds, **per device**: a stickered, a stickerless and (on the S24 FE) a glossy cube; at least two front lights and one backlit group; on the S24 FE, groups with the torch on and with it off; and a held-out split, named in the group index and never used for tuning in Story 10.21, that on its own holds at least 20 positive and 10 negative (no-cube) captures per device and at least one group for every scored device, light and torch condition; the tuning split holds at least one other group for each of those conditions
>
> **Given** the held-out split
> **When** `uv run --project tools/corner-model python tools/corner-model/evaluate_model.py --fixtures-corner Fixtures/corner` runs
> **Then** it reports, per device, recall ≥ 92%, rejection ≥ 96% and mean corner error ≤ 3% of the outline diagonal (10.19 AC7). A miss is recorded in the evidence and routed to the PO, and does not block Story 10.21 unless the PO says so.
>
> **Given** `QuickScanSideFaceReplayTest`
> **When** it runs in CI on the new groups
> **Then** it reports, without enforcing the bar, the current (10.19) results per device, light and torch state: side stickers correct, uncertain and wrong, side faces kept, and the keep reason per face (`closeCall`/`disagree`). This is Story 10.21's baseline, written to `docs/evidence/10-20/`.

#### 4.1.3 New Story 10.21: Quick Scan keeps the side faces it reads well

Insert after Story 10.20.

> ### Story 10.21: Quick Scan keeps the side faces it reads well
>
> As a Learner using Quick Scan,
> I want the faces the app read well to stay read, with at most one sticker left for me to fill,
> So that two corner photos usually finish the scan instead of sending me face by face.
>
> **Refs:** FR-22, FR-23, FR-24 · recognition NFRs (Quick Scan, Confidence calibration), NFR-10, NFR-11 · AD-9, AD-18 · OQ-8 · UX-DR15 · Stories 10.4, 10.17, 10.19, 10.20 · #274
>
> **Depends on** Story 10.20 (fixtures and held-out split). **Human gate:** a device session on the main app build, `QUICK_SCAN` raised in debug, on the Lenovo TB128XU and the Galaxy S24 FE with a stickered, a stickerless and a glossy cube, front-lit and backlit. *(SCP 2026-11-21 quick-scan-side-face-accuracy; PO decisions 2026-11-21: keep rule ≤ 1 blank with a matching calibration; calibration torch with a torch tag; Story 10.11 does not wait for this story.)*
>
> **Acceptance Criteria:**
>
> **Given** a saved calibration that matches the scan: the same torch state (AC2), and every face centre captured so far reads CONFIDENT as its own colour against the saved calibration (a calibration from another cube or another light fails this check)
> **When** a corner capture's face is classified
> **Then** the face is kept when at most **1** of its stickers is uncertain and none disagrees beyond the agreement gate. The uncertain sticker is left blank and pre-selected in the Quick Scan review, as the sticker review net already does (EXPERIENCE.md). A face with 2 or more uncertain stickers goes to the Guided grid, as before. **Without** a matching calibration (none, a torch mismatch, or a centre that fails the check, logged as `calibration=mismatch centre`), the Story 10.17 rule is unchanged: a face is kept only when all 9 stickers are CONFIDENT. A kept face never carries a confidently wrong sticker (Confidence calibration NFR).
>
> **Given** the calibration screen (Story 10.4) on a device whose back camera has a flash
> **When** the Learner calibrates
> **Then** the screen shows the same torch toggle as the Quick Scan corner view, on by default, and the saved calibration records the torch state it was taken under (`ColorCalibrationEntity` gets a nullable torch column through a Room migration with a migration test; rows saved before it count as torch-off). Quick Scan uses a saved calibration only when its torch state equals the current one. Otherwise it self-calibrates as when no calibration exists, logs `calibration=mismatch torch`, and shows the 10.19 *Calibrate colors* offer. On a device without a flash, no torch control appears and the torch state is always off. The Guided 6-face Scan's use of calibration is unchanged.
>
> **Given** the agreement gate between a face's self-calibrated and calibrated reads
> **When** it is tuned against the Story 10.20 tuning split
> **Then** its rule and thresholds are chosen per colour where the data supports it (Yellow, Orange and Red against Blue on the shaded back face), and the spec records the before and after numbers. No change turns an uncertain sticker into a confident wrong one on any group, tuning or held-out.
>
> **Given** the light check (10.19 AC1)
> **When** it is refitted on the Story 10.20 captures
> **Then** both cuts (uncalibrated L* difference, calibrated top-over-sides ratio) are refitted per mode with **hysteresis** (a hold starts above the hold cut and clears only below a lower clear cut), so an ordinary torch-off S24 FE light no longer flips hold and release. The light check is skipped only while the torch is **actually lit** (`enableTorch` succeeded), not merely requested (action item `torch-requested-vs-lit`). The light-clear window's steadiness rule is settled with the PO and matches the AC (action item `light-clear-steadiness`). Returning from calibration opened from the corner view clears a stale light hold, so the offer does not reappear at once (action item `cal-offer-reappears`).
>
> **Given** the Story 10.20 **held-out** groups
> **When** `QuickScanSideFaceReplayTest` runs in CI with its bar enforced
> **Then** per device, light and torch state (never pooled): 0 confidently wrong side stickers and 0 Red↔Orange confusions on kept faces, at least 90% of side stickers read correctly, and at least 3 of every 4 side faces kept (a face kept with one blank sticker counts as kept; its blank counts as not correct). Backlit groups are reported, not scored, and must trip the light check. The replay also runs the held-out groups with a calibration from another cube and from another light, and in those runs too no kept face carries a confidently wrong sticker. This is 10.19 AC6.
>
> **Given** the main app on each device after the tuning
> **When** a tester runs at least 10 front-lit and 10 backlit Quick Scans per device, including a solved cube without a saved calibration
> **Then** false-block and missed-block counts per light mode are recorded from the diagnostics (10.19 AC2). On the S24 FE a glossy cube is captured at both holds with the torch on and off, and torch glare is coached and cleared by turning the torch off (10.19 AC4). Faces kept per scan are recorded per device.
>
> **Given** the device evidence
> **When** the PO compares two-corner completion before (Story 10.20 baseline) and after
> **Then** the PO records whether Quick Scan is viable as `BETA` (10.19 AC8). If it is, a later Story 10.11 run (`10.11-n`) raises `QUICK_SCAN`; the level itself still follows the recognition matrix (Story 10.8). If it is not, Quick Scan stays OFF and the reasons go to a new issue.

#### 4.1.4 Epic 10 order and the *Never blocks* note (Epic List, around line 236)

OLD (end of the **Order** line):
> … 11.1 → 10.18 (Quick Scan finds the cube's real edges and corners, #195) → 10.17 (after 10.16 too); all v1.0 scope (SCP 2026-11-20) …

NEW:
> … 11.1 → 10.18 (Quick Scan finds the cube's real edges and corners, #195) → 10.17 (after 10.16 too) → 10.19 → 10.20 → 10.21 *(SCP 2026-11-21 quick-scan-side-face-accuracy, #274: 10.20 and 10.21 do not block Story 10.11, which raises `CAMERA_SCAN` and `PLACEMENT` with `QUICK_SCAN` OFF; Quick Scan is raised in a later 10.11-n after 10.21's BETA call)*; all v1.0 scope (SCP 2026-11-20) …

And append to the *Never blocks* paragraph:
> *(SCP 2026-11-21 quick-scan-side-face-accuracy: `QUICK_SCAN` leaves this first raise and follows Story 10.21.)*

Rationale: it records PO decision 4 and removes the conflict with Story 10.8's "Quick Scan OFF" recommendation.

#### 4.1.5 Story 10.11: one line

Section: after the **Runs** paragraph.

NEW:
> *(SCP 2026-11-21 quick-scan-side-face-accuracy: the first run raises `CAMERA_SCAN` and `PLACEMENT` only; `QUICK_SCAN` is raised by a later 10.11-n once Story 10.21's BETA call passes.)*

### 4.2 PRD (`prds/prd-RubikXplorer-2026-10-29/.memlog.md`)

Append:
> - (decision by user) SCP 2026-11-21 quick-scan-side-face-accuracy (#274) approved by PO hebelmx: 10.19's deferred accuracy ACs move to Stories 10.20 (10.19-pipeline fixtures, corner-model held-out check) and 10.21 (keep rule ≤ 1 blank sticker with a matching calibration, 10.17 all-9 rule otherwise; calibration-screen torch with the torch state saved and matched; agreement gate and light cuts refitted with hysteresis; replay bar on held-out groups; BETA call). Story 10.11 no longer waits for Quick Scan: `QUICK_SCAN` stays OFF in its first run. No FR or target change.

FR-22 and §4.8 are unchanged.

### 4.3 Architecture (`ARCHITECTURE-SPINE.md`, AD-9, line 255)

OLD:
> … Quick Scan classifies side faces with the Learner's saved calibration when one exists (SCP 2026-11-21, Story 10.19).

NEW:
> … Quick Scan classifies side faces with the Learner's saved calibration when one exists (SCP 2026-11-21, Story 10.19). The saved calibration records the torch state it was taken under, and Quick Scan uses it only when that matches the current torch; otherwise it self-calibrates (SCP 2026-11-21 quick-scan-side-face-accuracy, Story 10.21).

Plus a matching line in the architecture `.memlog.md`. No new AD and no change to the module map.

### 4.4 UX (`EXPERIENCE.md`)

**Quick Scan capture row (line 102):** append:
> **Kept faces (SCP 2026-11-21 quick-scan-side-face-accuracy, Story 10.21):** with a saved calibration that matches the scan, a face read with at most one uncertain sticker is kept, and that sticker arrives blank and pre-selected in the review; without one, a face is kept only when every sticker is confident.

**Calibration (Story 10.4 row; worker to locate):** append:
> On a device with a flash, the calibration screen shows the same torch control as the Quick Scan corner view, on by default; a calibration is used by Quick Scan only under the same torch state, and otherwise Quick Scan offers to calibrate again.

No new copy keys: the toggle reuses 10.19's "Torch on"/"Torch off", and the offer reuses "Colors look off — calibrate?".

### 4.5 sprint-status (`implementation-artifacts/sprint-status.yaml`)

- Add under `epic-10`, after `10-19-…`:
  - `10-20-record-10-19-pipeline-corner-fixtures-and-check-the-corner-model: backlog`
  - `10-21-quick-scan-keeps-the-side-faces-it-reads-well: backlog`
- `epic-10-deferred-10.19-side-face-accuracy`: `status: closed`, with the note "planned by SCP 2026-11-21 quick-scan-side-face-accuracy as Stories 10.20 and 10.21".
- `…-torch-requested-vs-lit`, `…-light-clear-steadiness`, `…-cal-offer-reappears`: add `story: 10-21` (they stay open until 10.21 merges).

---

## 5. Implementation Handoff

**Scope: Moderate** (backlog additions and an order change; no replan).

| Role | Responsibility |
| --- | --- |
| PO (`hebelmx`) | Approve this SCP and merge the docs PR. Run the 10.20 recording session and the 10.21 device session, settle the light-clear steadiness rule in the 10.21 spec, and make the BETA call. |
| Developer agent (via `/bmad-orchestrate`) | Apply §4 on branch `docs/scp-2026-11-21-quick-scan-side-face-accuracy` and open the PR. Then build 10.20 and 10.21 in order; 10.11 can run independently. |
| Architect | None beyond the one AD-9 clause in §4.3 (applied with this SCP). |

**Success criteria:**
- 10.20: a 10.19-pipeline fixture set per device with a held-out split; corner-model metrics and a report-only baseline written to `docs/evidence/10-20/`.
- 10.21: the replay bar enforced and green on held-out groups per device, light and torch; live AC2 counts with no false block in ordinary front light; S24 FE glossy-cube glare coached; the PO's BETA call recorded.
- Throughout: 0 confidently wrong stickers on kept faces, in every group and every run, including runs with another cube's or another light's calibration.

**Next steps after approval:** merge the docs PR, run `/bmad-orchestrate story 10-11-raise-a-camera-feature-above-off-for-a-release` (Quick Scan OFF), and run `/bmad-orchestrate story 10-20-…` once a device session can be scheduled. The two tracks are independent.
