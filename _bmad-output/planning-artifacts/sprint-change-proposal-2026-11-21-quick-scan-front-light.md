# Sprint Change Proposal: Quick Scan reads side faces in front light (2026-11-21)

**Status: Approved by the product owner (`hebelmx`) on 2026-11-21. Section 4 planning edits are applied on branch `docs/scp-2026-11-21-quick-scan-front-light`.**

- **Trigger:** Story 10.18 (*Quick Scan finds the cube's real edges and corners*, PR #266), device gate D2 on the Lenovo Tab M10 Plus 3rd Gen (`TB128XU`, minimum camera spec), 2026-11-21.
- **Mode:** Batch
- **Scope:** **Moderate**. Story 10.18 narrows to the LiteRT corner foundation it has proven, and a new Story 10.19 takes side-face reading: a light gate with coaching, metering on the cube, saved calibration and the torch where a flash exists. Story 10.17 moves ahead of 10.19, and the release-order wording in `epics.md` is reconciled. No new epic, AD, module or dependency.
- **Review:** revised after a PO-requested review on 2026-11-21 (10 findings, all kept).
- **Evidence:** [`docs/evidence/10-18/d2-side-face-replay.md`](../../docs/evidence/10-18/d2-side-face-replay.md), `QuickScanSideFaceReplayTest` and the 14 recorded captures under `Fixtures/corner/` (branch `feat/10-18-quick-scan-finds-the-cube-s-real-edges-and-corners`, PR #266); D1 and D3 results in the 10.18 orchestrator ledger and `docs/evidence/10-18/litert-model-evaluation.md`.

---

## 1. Issue Summary

Story 10.18 replaced the heuristic corner finder with a 7-keypoint LiteRT model. On the Lenovo, two of its device gates passed:

| Gate | Result |
| --- | --- |
| D1 latency and accuracy (`LiteRtCornerDetectorDeviceTest`, debug) | median 13.96 / 13.97 / 14.09 ms at 320x240 / 640x480 / 1280x960 (bar < 40 ms); corners found 39/37/38 of 40; mean error about 2.9 px; real-palette 24/24; non-cubes rejected 20/20 |
| D3 inference on an R8-minified build (`cornerR8`) | passes with only the shipped keep rules; median about 6.4 ms |

D2 checks AC3: "post-unwarp Red (`R`) vs Orange (`L`) side-face separation is verified so clean corners avoid forced `R`/`L` 2D fallbacks". The PO recorded 14 real corner captures with the fixture recorder (4 cubes `stla stlb stka stkb`, 2 holds each; light behind the cube, light from the camera side). Replaying them through the production Quick Scan path (`SelfCalibration` + `QuickScanReadout`):

| Captures | Side cells correct / uncertain / wrong | Red<->Orange wrong | Red/Orange uncertain | Side faces sent to the Guided grid |
| --- | --- | --- | --- | --- |
| All 14 | 148 / 61 / 15 of 224 | 1 | 31 | 25 of 28 |
| `stla`, light behind the cube | 13 / 17 / 2 of 32 | 0 | 11 | 4 of 4 |
| `stla`, light from the camera side | 27 / 5 / 0 of 32 | 0 | 2 | 2 of 4 |
| `stka`, light behind the cube | 23 / 8 / 1 of 32 | 0 | 4 | 4 of 4 |
| `stka`, light from the camera side | 24 / 7 / 1 of 32 | 0 | 6 | 4 of 4 |

*(Totals cover the 14 captures replayed by the worker; the two `cool-front` captures for `stla` and `stka` were added afterwards and are shown on their own rows.)*

Top faces read better (83/112 overall, 15/16 for `stla` front-lit), though the hold-2 top (yellow up) leaves 3 Red/Orange stickers uncertain on `stka` under both lights. The corner detection and unwarp work; the colors of the shaded side faces do not.

**Root causes** (worker analysis, 2026-11-21):
- Quick Scan self-calibrates Red, Green, Orange and Blue from the shaded side-face centers, but White and Yellow from the lit top.
- Shading varies 15-20 ΔE within one face. With the light behind the cube the side faces are far darker than the top.
- On shaded faces Red loses saturation toward Orange: on `stla` backlit, a Red and an Orange cell on the same face are 7.0 ΔE apart, under the 10 ΔE close-call rule, so no classifier on those Lab values reads both confidently and correctly.
- Classifier-only fixes were tried (per-face brightness normalization, per-channel correction, chroma ratios, saved calibration only). None met the bar; the best one turns 3 uncertain Red/Orange cells into confident wrong reads, which the PO rejects.

**Hardware finding:** the Lenovo TB128XU has no flash unit (`android.flash.info.available = FALSE` on both cameras), so a torch cannot help the minimum device.

**PO decisions (2026-11-21):**
- Fix it with light: coach the Learner into front light, meter exposure on the cube, use the Learner's saved calibration, and use the torch on devices that have one.
- Rejected: a side-face calibration step (too cumbersome for Learners).
- Rejected: relaxing AC3 so uncertain side cells become suggestions (Learners accept suggestions as fact and don't review them).

**Problem classification:** technical limitation discovered during implementation.

---

## 2. Impact Analysis

### Change Navigation Checklist

| # | Item | Status | Finding |
| --- | --- | --- | --- |
| 1.1 | Trigger story | `[x]` | Story 10.18, device gate D2 (AC3), PR #266. |
| 1.2 | Core problem | `[x]` | Shaded 3D side faces lose color separation (Red vs Orange) on the minimum device, worst with the light behind the cube; classification cannot recover it. |
| 1.3 | Evidence | `[x]` | 14 real captures, replay table, D1/D3 numbers, Lenovo camera characteristics (no flash). |
| 2.1 | Current epic (Epic 10) | `[!]` | Completable. 10.18 narrows; new Story 10.19 carries side-face reading and the Quick Scan BETA decision. |
| 2.2 | Epic-level changes | `[x]` | Add Story 10.19 to Epic 10. No new epic. |
| 2.3 | Remaining epics | `[x]` | Epics 14 and 15 unaffected. 10.11 (raising camera flags for release) still needs the Quick Scan BETA decision, now in 10.19. |
| 2.4 | Obsolete / new epics | `[N/A]` | None. |
| 2.5 | Order | `[!]` | `10.18 → 10.17 → 10.19`, then Quick Scan joins Story 10.8's recognition matrix, and Story 10.11 raises flags last. 10.17 runs before 10.19 so the light gate has a working exit (*Scan face by face*). **With `QUICK_SCAN` OFF** (if 10.19 slips or fails its bar): 10.17 and 10.19 leave the release path, Story 10.11 can still raise `CAMERA_SCAN` and `PLACEMENT`, and release Stories 12.3–12.5 are unaffected. The final flag levels stay tied to Story 10.8's full matrix. |
| 2.6 | Release-order wording | `[!]` | Pre-existing contradiction: the SCP 2026-11-20 promotion note in `epics.md` says release Stories 12.3–12.5 run after 10.11, while Story 10.11 depends on 12.3–12.5 and is "the last PR merged before the tag". Reconciled below in favor of Story 10.11's own text (12.3–12.5 first, 10.11 last). |
| 3.1 | PRD | `[!]` | FR-22 gains one consequence (lighting help, metering, saved calibration, torch). OQ-8 test matrix notes light direction (behind the cube vs from the camera side) as a condition. No target changes. |
| 3.2 | Architecture | `[!]` | AD-9 gains one note: metering and the torch are adapter concerns in `app/src/camera/`; Quick Scan classifies with the saved calibration when one exists. No new AD, dependency or permission (the torch needs none). |
| 3.3 | UX | `[!]` | EXPERIENCE.md Quick Scan capture row: backlight coaching and the torch behavior. New Learner copy goes in `en.json`. |
| 3.4 | Other artifacts | `[!]` | sprint-status gains `10-19-…`; the fixture recorder (debug tool) needs corner-mode fixes to record the 10.19 fixtures. |
| 4.1 | Direct adjustment | `[x]` | Viable: narrow 10.18, add 10.19. Effort: medium (about 2–4 days plus one device session). Risk: medium; front light is proven on one cube, the bar needs more cubes and the S24 FE. |
| 4.2 | Rollback | `[N/A]` | Nothing to revert; the LiteRT foundation is proven. |
| 4.3 | MVP review | `[N/A]` | Quick Scan stays BETA-or-OFF behind its flag, as planned. |
| 4.4 | Path | `[x]` | Direct adjustment. |

---

## 3. Recommended Approach

**Direct adjustment.**

1. **Story 10.18 merges as the LiteRT corner foundation.** Its device gates D1 and D3 pass. AC3 becomes "the real-capture replay exists and records the baseline"; the enforced side-face bar, real-capture model validation and the PO's BETA decision move to Story 10.19. Its human gate becomes D1 + D3, both passed.
2. **New Story 10.19 makes the side faces read in good light:** backlight coaching holds the capture until the side faces are lit; exposure, white balance and focus are metered on the detected cube; Quick Scan classifies with the Learner's saved calibration when one exists; the torch turns on during corner capture on devices with a flash. Its bar is enforced in CI by `QuickScanSideFaceReplayTest` on front-lit captures.
3. **Explicitly out of scope:** a side-face calibration step; turning uncertain side cells into suggestions.

**Rationale:** front light is a **promising** intervention, not a proven one. It lifted the stickerless `stla` from 13/32 to 27/32 side stickers correct (84%) with 0 wrong, but 2 of its 4 side faces still went to the Guided grid, and the stickered `stka` barely changed (23/32 → 24/32, 1 wrong). No single lever meets the bar, so 10.19 combines four (coaching, metering on the cube, saved calibration, torch where present) and is judged by **measured two-corner completion on the no-flash Lenovo**, per device and lighting condition, not by a pooled score. Saved calibration fixed the top-face mismatch in the worker's replay (tops 94/96 vs 68/96) but is not yet shown safe on side faces, so 10.19 tests it against mismatched calibrations too. If the combined levers cannot meet the bar on the Lenovo, `QUICK_SCAN` stays OFF for v1.0 (Section 2, item 2.5).

**Risks:** (1) a light gate that blocks in ordinary front light makes Quick Scan feel broken: 10.19 measures false blocks and missed blocks on both devices, and *Scan face by face* (Story 10.17, delivered first) keeps any captured corner. (2) Regional metering is best effort in CameraX and may be unsupported: 10.19 checks support on both devices and falls back to center metering. (3) A saved calibration from other light or another cube could produce confident wrong reads: 10.19 must show such mismatches end as uncertainty, never as confident wrong.

---

## 4. Detailed Change Proposals

### 4.1 Stories (`epics.md`)

**Story 10.18 — header (Human gate)**

OLD:
> **Human gate (acceptance):** a fast BETA evaluation on the Lenovo Tab M10 Plus 3rd Gen (TB128XU, the minimum camera spec) and the Galaxy S24 FE, both test cubes (stickered scrambled + solved, and stickerless), main app build, `QUICK_SCAN` raised in debug.

NEW:
> **Human gate (acceptance):** on the Lenovo Tab M10 Plus 3rd Gen (TB128XU, the minimum camera spec): `LiteRtCornerDetectorDeviceTest` on the debug build (D1) and on the R8-minified `cornerR8` build (D3). *(SCP 2026-11-21 quick-scan-front-light: the BETA evaluation on both devices moves to Story 10.19.)*

**Story 10.18 — AC2 (model)**

OLD:
> **Then** the model is trained from reproducible synthetic 3D cube renders (…) fine-tuned/validated on real Lenovo and S24 FE captures; release installed size …

NEW:
> **Then** the model is trained from reproducible synthetic 3D cube renders (…), with real Lenovo and S24 FE palettes and face crops; release installed size … *(SCP 2026-11-21: validation on real labelled corner captures moves to Story 10.19.)*

**Story 10.18 — AC3 (fixtures and side faces)**

OLD:
> **Then** the detected corners are within a reported pixel tolerance of the labeled corners, post-unwarp 3D side-face color separation (specifically shaded Red `R` vs. Orange `L` observed as `unc=9` in …) is verified so clean corners avoid forced `R`/`L` 2D fallbacks, and if additional real fixtures are needed the developer flags them

NEW:
> **Then** the detected corners are within a reported pixel tolerance of the labeled corners on synthetic fixtures, and a replay of real corner captures (`Fixtures/corner/`) through the production Quick Scan side-face readout reports, per cube, light and face, the stickers read correctly, left uncertain and read wrong, with Red↔Orange confusions counted separately; the baseline is recorded in `docs/evidence/10-18/`. *(SCP 2026-11-21: the enforced side-face bar moves to Story 10.19.)*

**Story 10.18 — AC6 (BETA decision)**

OLD:
> **Given** a fast hands-on BETA evaluation on both devices **When** Quick Scan is tested after the fix **Then** the PO confirms whether Quick Scan is viable as `BETA` for v1.0 (…)

NEW: *(removed; moved to Story 10.19 AC7.)*

**Story 10.17** — unchanged; it still depends only on Stories 11.1, 10.16 and 10.18, and now runs **before** Story 10.19.

**Epic 11 / promotion note — release order (`epics.md`, the SCP 2026-11-20 v1.1 promotion paragraph)**

OLD:
> … and Story 10.11, which raises those flags for the v1.0 release from the test-matrix evidence (BETA or GA). Release Stories 12.3, 12.4 and 12.5 run after them.

NEW:
> … and Story 10.11, which raises those flags for the v1.0 release from the test-matrix evidence (BETA or GA). Release Stories 12.3, 12.4 and 12.5 run before Story 10.11, which depends on them and is the last PR before the tag. *(SCP 2026-11-21: `QUICK_SCAN` reaches 10.11 only through Stories 10.17 and 10.19 and Story 10.8's matrix; if it stays OFF, 10.11 raises `CAMERA_SCAN` and `PLACEMENT` without it.)*

**New Story 10.19** (appended ID; runs after Stories 10.18 and 10.17, as the How-to-read note allows)

> ### Story 10.19: Quick Scan reads the side faces in good light
>
> As a Learner using Quick Scan,
> I want the app to help me light my cube and read its shaded sides with my own colors,
> So that both corner photos read all three faces instead of sending me face by face.
>
> **Refs:** FR-22, FR-23, FR-24 · recognition NFRs (Quick Scan, Confidence calibration), NFR-10, NFR-11 · AD-9, AD-13, AD-18 · OQ-8 · UX-DR15 · Stories 10.4, 10.8, 10.17, 10.18, 11.1 · #195, #196
>
> **Depends on** Stories 10.18 and 10.17 (*Scan face by face* is the exit from a held capture). **Human gate:** a device session on the main app build, `QUICK_SCAN` raised in debug, on the Lenovo Tab M10 Plus 3rd Gen (TB128XU, no flash) and the Galaxy S24 FE (flash), with a stickered, a stickerless and a glossy cube, light from the camera side and light behind the cube. *(SCP 2026-11-21 quick-scan-front-light. Out of scope by PO decision: a side-face calibration step, and turning uncertain side stickers into suggestions. Numeric thresholds below are proposed in the SCP and confirmed by the PO at approval.)*
>
> **Acceptance Criteria:**
>
> **Given** the corners are detected and the side faces are much darker than the top face or the background (the light is behind the cube)
> **When** frames are analysed in the corner capture view
> **Then** the light check is part of "aligned and steady": while it fails, neither auto-capture (UX-DR15, about 600 ms aligned and steady) nor the manual shutter takes a photo; after about 1 s of failing, inline coaching from `en.json` reads "Turn so the light shines on the cube."; the hold clears once the side faces pass for about 0.5 s while steady, and auto-capture then restarts its normal count; *Scan face by face* (Story 10.17) stays visible and keeps any corner already captured; the threshold comes from the recorded captures
>
> **Given** the main app on each device
> **When** a tester starts a Quick Scan with the light behind the cube, then turns so the light falls on the cube
> **Then** the capture is held with the coaching shown, and resumes and captures after the light is corrected; across at least 10 front-lit attempts per device in ordinary room light the light check never blocks (false blocks), and across at least 10 backlit attempts it always blocks (missed blocks); both counts are recorded in the device evidence
>
> **Given** a detected cube outline
> **When** frames are analysed
> **Then** the camera adapter meters on the detected cube region, not the whole frame: auto-exposure and focus on both holds, and white balance on Hold 1 only (Hold 2's grid has no white reference, as in Story 10.18); before metering, the adapter checks that regional metering is supported (`CameraInfo.isFocusMeteringSupported`) and falls back to center metering when it is not; a capture waits until the camera has settled after a metering change (settle signal defined in the spec: exposure converged, or a fixed settle time measured on both devices); support and settle times on both devices are recorded; pixels and camera control stay in `app/src/camera/` (AD-9); no frame is stored
>
> **Given** a device whose back camera has a flash unit
> **When** the corner capture view is open
> **Then** the torch is on by default during corner capture, with a visible control to turn it off; the Learner's choice carries over from Hold 1 to Hold 2; the torch turns off on *Scan face by face*, on leaving the capture view and when the app goes to the background; on a device without a flash (the Lenovo) no torch control or copy appears; on the S24 FE a glossy cube is captured in both holds with the torch on and off, and glare caused by the torch is detected and recoverable (turning the torch off clears it)
>
> **Given** saved color calibration (Story 10.4)
> **When** Quick Scan classifies the stickers
> **Then** it uses the saved calibration when one exists, and self-calibrates from the face centers when none exists; the replay covers no saved calibration, a matching calibration, and calibrations made under different light and on a different cube, and in every mismatched case the result is uncertainty or fallback, never a confidently wrong sticker; the Guided 6-face Scan's behavior is unchanged
>
> **Given** front-lit real corner captures under `Fixtures/corner/`, from the Lenovo and the S24 FE, with at least one stickered and one stickerless cube under at least two lights
> **When** `QuickScanSideFaceReplayTest` runs in CI with its bar enforced
> **Then** results are reported and enforced **per device and lighting condition**, never pooled: 0 confidently wrong side stickers (0 Red↔Orange confusions; Confidence calibration NFR), at least 90% of side stickers read correctly, and at least 3 of every 4 side faces read without being sent to the Guided grid for any reason; this replaces Story 10.18's original "no forced R/L fallback" wording, which demanded zero Red/Orange uncertainty; backlit captures are reported, not scored, and must trip the light check
>
> **Given** the debug fixture recorder in corner mode
> **When** an operator records corner captures
> **Then** it shows the Y anchor and the live detected outline, lets the operator place the 7 corners on the frozen frame by hand (the detector's points are saved separately for diagnostics and are never used as labels), keeps positive frames even when the detector misses, records no-cube (negative) captures, starts the camera right after the permission is granted, and never silently overwrites a saved capture group; `evaluate_model.py --fixtures-corner` on held-out captures, with at least 20 positives and 10 negatives per device, meets recall ≥ 92%, rejection ≥ 96% and mean corner error ≤ 3% of the cube outline's diagonal (validation moved from Story 10.18)
>
> **Given** a hands-on evaluation on both devices after this story
> **When** the PO compares two-corner completion on the Lenovo before and after
> **Then** the PO confirms whether Quick Scan is viable as `BETA` for v1.0 (moved from Story 10.18); the release level itself is decided by Story 10.8's full recognition matrix and raised by Story 10.11

### 4.2 PRD (`prds/prd-RubikXplorer-2026-10-29/prd.md`)

**FR-22 — add one consequence** (after the "real edges and corners" bullet):

> - Quick Scan helps the Learner light the cube: when the light is behind the cube it says so and waits, it meters on the cube, it uses the Learner's saved color calibration when one exists, and it turns on the torch where the device has one. It never turns an uncertain sticker into a suggested color. *(SCP 2026-11-21)*

**OQ-8 — append:**

> Light direction is a matrix condition: light from the camera side and light behind the cube (SCP 2026-11-21).

### 4.3 Architecture (`ARCHITECTURE-SPINE.md`, AD-9)

**Add one bullet under AD-9:**

> - Quick Scan metering on the detected cube and the torch are camera-adapter concerns in `app/src/camera/`; the torch needs no permission and is used only when the back camera reports a flash unit. Quick Scan classifies side faces with the Learner's saved calibration when one exists (SCP 2026-11-21).

### 4.4 UX (`EXPERIENCE.md`, Quick Scan capture row)

**Append:**

> **Light (SCP 2026-11-21):** when the cube's side faces are much darker than its top or the background, neither auto-capture nor the shutter takes a photo; after about 1 s inline coaching reads "Turn so the light shines on the cube.", and the hold clears once the faces are lit and steady. *Scan face by face* stays available and keeps any corner already captured. On a device with a flash, the torch is on during corner capture with a control to turn it off; the choice carries to the second corner, and the torch goes off when the Learner switches to face by face or leaves. On a device without a flash, nothing about the torch appears.

### 4.5 Sprint tracking

- Add `10-19-quick-scan-reads-the-side-faces-in-good-light: backlog` (generated from `epics.md`).
- 10.18 stays in-progress on PR #266 until it merges with the narrowed ACs. 10.17 stays backlog and runs next.

---

## 5. Implementation Handoff

- **Scope:** Moderate (backlog change plus story ACs; no replan).
- **PO (`hebelmx`):** approve this proposal; run the 10.19 device session (Lenovo + S24 FE).
- **Developer (orchestrator):** apply Section 4 on branch `docs/scp-2026-11-21-quick-scan-front-light` and merge it; then resume Story 10.18 (PR #266) against the narrowed ACs (re-run the acceptance gate, route deferrals, merge); then Story 10.17; then `/bmad-orchestrate story 10-19-quick-scan-reads-the-side-faces-in-good-light`.
- **Success criteria:** 10.18 merges with D1 + D3 as its device proof; 10.19's replay bar passes per device and condition in CI; the main-app light-gate check passes on both devices; two-corner completion on the Lenovo measurably improves; the PO records the Quick Scan BETA decision, and the release level follows Story 10.8's matrix.
