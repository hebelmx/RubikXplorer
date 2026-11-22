# Sprint Change Proposal: Quick Scan capture reliability (2026-11-22)

**Status: Draft, awaiting product-owner approval. No planning artifact has been edited; Section 4 lists the edits to apply on approval.**

- **Trigger:** owner report that Quick Scan is not usable (3–4 s at best, 30+ s at times, all with tablet, cube and lights still), investigated on 2026-11-22 in `_bmad-output/specs/spec-quick-scan-reliability/`.
- **Mode:** Batch (Fast path).
- **Scope:** **Moderate.** One new story in Epic 10 (10.22), dependency and wording edits to Stories 10.20 and 10.21, the Epic 10 order line, one sprint-status entry and a PRD memlog note. No new epic, AD, module, dependency or permission. Quick Scan stays OFF in release; Story 10.11 is unchanged.
- **Evidence:** [`docs/evidence/quick-scan-2026-11-22/`](../../docs/evidence/quick-scan-2026-11-22/README.md) (two Quick Scan diagnostic logs, three recorder bursts), the spec's [concerns and evidence](../specs/spec-quick-scan-reliability/concerns-and-evidence.md), [failure modes](../specs/spec-quick-scan-reliability/failure-modes.md) and [fix plan](../specs/spec-quick-scan-reliability/fix-plan.md), and a `bmad-review` pass on 2026-11-22 whose findings are applied to that spec.

---

## 1. Issue Summary

Stories 10.20 and 10.21 plan how well Quick Scan **reads** the faces (keep rule, agreement gate, light cuts, calibration torch). Nothing planned addresses how long a corner takes to **capture**. The 2026-11-22 bench session measured that side.

| Measured on the Lenovo TB128XU, everything still | Result |
| --- | --- |
| Hold 1, recording 1 | about 2 min, one light hold of 52.9 s |
| Hold 2, recording 1 | not captured in 65 s |
| Holds 1 and 2, recording 2 | 19.2 s (probably ended by a manual press) and 24.8 s |
| Floor if every gate passes first time | about 3 s (arithmetic; never observed) |

**Causes established by the logs and bursts:**

1. **Exposure changes during a hold (F1, F2).** Metering on the cube holds one exposure for about 3 s, then the camera returns to the whole-scene level; every sticker shifts ΔE 5–8. In one burst exposure swung inside the 3 s window while the camera reported converged. With exposure steady, sticker variation on a resting cube is ΔE 0.3–0.5.
2. **The steady rule compares colors (F6).** It restarts on each exposure change on a still cube. Hand-held, 52 of 119 consecutive frame pairs trip it and one 1-second window completed in 9.7 s.
3. **Clearing a light hold re-meters (F4).** The re-meter moves exposure and the next hold engaged on the settle frame, four times in the two recordings.
4. **Cube lost during a hold (F14).** 48 of 228 logged frames in recording 1; a loss re-meters and restarts settle and steady.
5. **Diagnostics gaps (F13).** No capture trigger (automatic or manual), throttled restart lines, and a log buffer that lost the first minute of recording 1.

**Not established, and therefore not changed by this proposal:** why the light check failed for 52.9 s at about 1.8 (far above the 1.34 cut), why one face's light level jumps between frames, and whether capturing in the light the check blocks gives usable readings. No capture in either log happened at a failing light score. These stay with Story 10.21's refit.

**Problem classification:** a technical limitation found after Story 10.19 merged. The capture gates interact with camera exposure in a way the 10.19 device gate did not measure.

---

## 2. Impact Analysis

### Change Navigation Checklist

| # | Item | Status | Finding |
| --- | --- | --- | --- |
| 1.1 | Triggering story | [x] | Story 10.19 (Quick Scan lighting pipeline, PR #272), exercised on 2026-11-22. |
| 1.2 | Core problem | [x] | Technical limitation: capture waits on exposure changes, a color-based steady rule and re-meters. |
| 1.3 | Evidence | [x] | Two diagnostic logs, three bursts, source read, independent review. One device, one session. |
| 2.1 | Current epic | [x] | Epic 10 can complete as planned once one story is added. |
| 2.2 | Epic-level changes | [x] | Add Story 10.22; re-point 10.20 and 10.21 dependencies. |
| 2.3 | Remaining epics | [x] | None affected. Epic 11 is done; Story 10.11's first run does not raise `QUICK_SCAN`. |
| 2.4 | New or obsolete epics | [N/A] | None. |
| 2.5 | Order | [x] | 10.22 goes before 10.20, so fixtures are recorded once, under the new exposure handling. |
| 3.1 | PRD | [x] | No FR or NFR changes. A memlog note records the story. |
| 3.2 | Architecture | [x] | No spine edit. Exposure lock and settle are camera-adapter concerns already covered by AD-9; classification stays pure Kotlin (OQ-7 decision K); AD-9 frame non-persistence is untouched. |
| 3.3 | UX | [x] | No change: guide, coaching lines and the review are unchanged. |
| 3.4 | Other artifacts | [x] | `docs/evidence/10-22/` (new); the debug recorder burst (`RecorderBurst`, merged in PR #289) is adopted as Story 10.20 tooling. |
| 4.1 | Direct adjustment | Viable | Effort medium, risk medium (camera behavior differs per device). |
| 4.2 | Rollback | Not viable | Reverting 10.19 removes metering on the cube and the diagnostics that made this measurable. |
| 4.3 | MVP review | Not needed | Quick Scan is already outside the first release raise. |
| 4.4 | Selected path | [x] | Option 1, direct adjustment. |
| 6.4 | sprint-status | [!] | Add the 10.22 entry on approval. |

### Epic impact

Epic 10 gains Story 10.22. Stories 10.20 and 10.21 keep their content and gain a dependency on 10.22. Order becomes 10.19 → **10.22** → 10.20 → 10.21 → 10.11-n.

### Technical impact

- `:core:domain`: `CornerHold` (steady rule, re-meter on clear), `QuickScanController` (turn check, back-out).
- `app/src/camera`: `CameraXFrameSource` (exposure lock, settle signal, re-meter triggers), `GuidedScanActivity` (wiring), debug `ScanDiagnostics` (timeline).
- Tests: `QuickScanControllerTest`, `CameraXFrameSourceTest`, a replay of the recorded bursts.
- No new dependency. Camera2 interop is already used by the settle signal.

---

## 3. Recommended Approach

**Direct adjustment: add Story 10.22 for the capture-time fixes, before the fixture session.**

- The fixes are Kotlin and camera-adapter changes that need no new labelled data, and they improve the inputs of every later evaluation, whichever color reader is used.
- Recording the 10.20 fixtures after 10.22 avoids recording them twice: the light cuts and the agreement gate are to be fitted on captures made under the exposure handling that will ship.
- The light check keeps its approved 2026-11-21 behavior (it blocks; the manual shutter is ignored while it holds). The only change touching it is that a cleared hold no longer forces a re-meter. Whether it becomes advisory is decided in 10.21, with captures taken at failing scores.
- Sticker-level keeping resolved by cube legality, and any change that drops the saved calibration as second read, are **not** proposed. The review showed they would remove the containment that keeps confidently wrong stickers out today. They remain candidate ideas in the spec.

**Effort:** medium (one story, two modules, one device session). **Risk:** medium; the exposure lock must be proven on both devices, and thresholds must come from recorded data. **Timeline:** adds one story ahead of 10.20; nothing on the v1.0 path waits for it.

**Alternatives considered:** fold the fixes into 10.21 (rejected: 10.21 depends on fixtures that should be recorded after the fix); make the light check advisory now (rejected: neither its benefit nor its cost is measured).

---

## 4. Detailed Change Proposals

### 4.1 Stories (`_bmad-output/planning-artifacts/epics.md`)

**A. New story, inserted after Story 10.21:**

~~~
### Story 10.22: Quick Scan captures promptly once the cube is in view

As a Learner using Quick Scan,
I want each corner captured within a few seconds of holding my cube in view,
So that Quick Scan is faster than scanning face by face.

**Refs:** FR-22 · recognition NFRs (Quick Scan) · AD-9, AD-18 · Stories 10.17, 10.18, 10.19 · spec `_bmad-output/specs/spec-quick-scan-reliability/` (CAP-2, CAP-4; failure modes F1, F2, F4, F6, F11, F13, F14, F15)

**Depends on** Story 10.19. **Human gate:** a device session on the main app build, `QUICK_SCAN` raised in debug, on the Lenovo TB128XU and the Galaxy S24 FE. *(SCP 2026-11-22 quick-scan-reliability. Sticker reading, classification and gates stay in Kotlin. The light check's blocking behavior, the keep rule and the agreement gate are not changed here; they stay with Story 10.21.)*

**Acceptance Criteria:**

**Given** a Quick Scan corner hold in a debug build
**When** it runs
**Then** `CubentiaScanDiag` logs every state change as it happens, with milliseconds since the hold began: viewfinder entry, first frame, cube lost and found, pose rejected, metering requested and settled, light hold start and clear, steady restart, and capture with `trigger=auto|manual`. Each capture line carries the hold's total time and its time with the cube undetected. Steady-restart lines are no longer rate-limited. CameraX's own logging is limited so a three-minute session fits the device's default log buffer.

**Given** metering on the detected cube has converged
**When** the hold continues
**Then** exposure stays locked until the capture, or until a re-meter trigger: the cube lost for `CornerHold.LOST_MS`, a torch change, the outline leaving the metered region, or the faces' mean brightness changing beyond a set bound since metering. Focus stays continuous (PO 2026-11-16). A cleared light hold no longer re-meters. White balance at hold 2 is carried from hold 1. The settle signal is true only once exposure time × ISO has stayed within a tolerance for a set window; the AE state alone never settles it. `CameraXFrameSourceTest` replays the exposure series of `docs/evidence/quick-scan-2026-11-22/bursts/20261122-114155-still.jsonl` and reports "not settled" throughout its swing.

**Given** a settled hold
**When** the steady window runs
**Then** it restarts when the detected outline moves more than a threshold from the reference frame, measured as the largest corner displacement divided by the outline's bounding-box diagonal. A color comparison remains only to detect that a different face is shown. The threshold is derived from the three recorded bursts and at least one more hand-held burst, and is written to `docs/evidence/10-22/` with the replay: no restart on either still burst, windows completing on every hand-held burst, and a restart on a burst in which the cube is moved quickly.

**Given** hold 2
**When** the turn and pose checks run
**Then** they do not rely on a centre that is not plain: a logo centre is read from its ring or left out, and the previous hold is not cleared by a single frame. A test replays the logo-centre readings of the still burst as hold 2 with the cube unturned and no capture happens.

**Given** a back-out from the face-by-face hand-over
**When** the corner view resumes
**Then** stale turn-over state is cleared (`QuickScanController.backOut`), with a test that captures again after the back-out.

**Given** the main app on each device after these changes
**When** a tester runs at least 10 holds with everything still and 10 hand-held per device
**Then** the time from cube detected to automatic capture, the time undetected and the blocking gate are recorded per hold from the diagnostics, with the median and the worst case per device, next to the 2026-11-22 baseline in `docs/evidence/10-22/`. The PO sets the acceptable wait from these numbers. A recorder burst on each device shows one exposure level per hold.
~~~

**B. Story 10.20, dependency line:**

OLD: `**Depends on** Story 10.19. **Human gate:** one recording session …`
NEW: `**Depends on** Stories 10.19 and 10.22 (fixtures are recorded under 10.22's exposure handling). **Human gate:** one recording session …` with `*(SCP 2026-11-22 quick-scan-reliability.)*` appended to the existing SCP note.

**C. Story 10.20, first acceptance criterion:**

OLD: the group's `group.json` records `"pipeline": "10.19"`
NEW: the group's `group.json` records `"pipeline": "10.22"`

Appended to the same criterion: "The recorder's burst (`RecorderBurst`, PR #289) stays a debug-only tool; each burst line also carries the light score and the three face levels, and `RecorderBurst.line` has a JVM test."

Rationale: the fixtures must come from the pipeline that will be tuned; the burst is how exposure and outline stability were measured.

**D. Story 10.20, last acceptance criterion (replay baseline), appended:**

"The report also counts confidently wrong stickers among all CONFIDENT stickers, on kept and discarded faces alike, per group. Backlit groups are captured with the light check failing, and their light scores are recorded."

Rationale: the "zero wrong on kept faces" figure covers almost no kept faces today; the per-sticker count is what a keep-rule change must be judged on, and no reading at a failing light score exists yet.

**E. Story 10.21, dependency line:**

OLD: `**Depends on** Story 10.20 (fixtures and held-out split).`
NEW: `**Depends on** Story 10.20 (fixtures and held-out split) and Story 10.22.`

**F. Story 10.21, light-check criterion, appended:**

"The refit uses captures made under Story 10.22's exposure handling. It reports separately holds that fail far above the cut, holds that flip at the cut, and a single face's level changing between frames (with the per-sticker nearest-color matches logged), and the PO decides from the backlit captures whether the check blocks or only coaches."

Rationale: the three light-check failure shapes seen on 2026-11-22 need different answers; the block-or-coach decision needs readings taken in the light the check blocks.

**G. Epic 10 order line (line 235):**

OLD: `… → 10.19 → 10.20 → 10.21`
NEW: `… → 10.19 → 10.22 → 10.20 → 10.21` with `*(SCP 2026-11-22 quick-scan-reliability: 10.22 before the fixture session.)*`

### 4.2 PRD (`prds/prd-RubikXplorer-2026-10-29/.memlog.md`)

Append with `memlog.py`: "(decision by user) SCP 2026-11-22 quick-scan-reliability: Story 10.22 added before 10.20 to fix Quick Scan capture latency (exposure lock, outline-based steady, diagnostics); no FR or NFR change; light-check block-or-coach decision stays with Story 10.21."

No edit to `prd.md`.

### 4.3 Architecture

No edit. AD-9 already places metering in the camera adapter and keeps classification in Kotlin.

### 4.4 UX

No edit.

### 4.5 sprint-status (`implementation-artifacts/sprint-status.yaml`)

Add under `epic-10`, after the 10.21 entry: `10-22-quick-scan-captures-promptly-once-the-cube-is-in-view: backlog`. No other entry changes.

### 4.6 Spec memlog (`specs/spec-quick-scan-reliability/.memlog.md`)

Append: fix-plan steps 0–4 are Story 10.22; the three "later" topics stay with Stories 10.20 and 10.21 under the conditions above.

---

## 5. Implementation Handoff

**Scope: Moderate** (backlog reorganization within Epic 10).

| Who | What |
| --- | --- |
| Product owner | Approve or revise; answer the decisions below. |
| Developer agent (this session or the orchestrator) | Apply Section 4 on branch `docs/scp-2026-11-22-quick-scan-reliability`, open the PR, then run Story 10.22 through `bmad-build`. |
| Product owner | Device session for 10.22 on both reference devices. |

**Decisions for the product owner**

1. **Order.** 10.22 before 10.20 (recommended), or record fixtures first.
2. **Light check during 10.22.** Keep it blocking as approved on 2026-11-21 (recommended), or make it advisory now.
3. **Acceptable wait.** Set after the 10.22 device numbers (recommended), or fix a target now (the spec's provisional 5 s still, 8 s hand-held).
4. **Still open from the spec:** which cube and light the Lenovo's saved calibration came from on 2026-11-22.

**Success criteria:** Story 10.22 merged with its tests; the device evidence shows each still hold auto-capturing in a time the PO accepts, with a per-hold timeline that names what it waited for; Stories 10.20 and 10.21 start from fixtures recorded on that pipeline.
