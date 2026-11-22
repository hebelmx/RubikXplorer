# Sprint Change Proposal: Quick Scan shadow sticker reader (2026-11-22)

**Status: Approved by the product owner (`hebelmx`) on 2026-11-22 with two decisions: LiteRT is allowed for per-face sticker color classification, starting in shadow mode with a runtime debug toggle; the first model trains on a combined dataset with whole capture groups held out. Section 4 edits are applied on branch `docs/quick-scan-shadow-sticker-reader`.**

- **Trigger:** planning ahead of Story 10.22. The 2026-11-22 reading of the Quick Scan reliability spec judged that 10.22 addresses capture time but not sticker reading on shaded side faces, and recorded a shadow-mode sticker reader as a candidate (`fix-plan.md`, commit `cdb24459`). The PO approved it for planning.
- **Mode:** Batch (Fast path).
- **Scope:** **Moderate.** One new story in Epic 10 (10.23), a narrowing amendment to AD-9 (the "LiteRT in corner detection only" limit is lifted for Quick Scan sticker reading), a PRD OQ-7 note, the Epic 10 order line and one sprint-status entry. No new epic, AD, module, runtime dependency or permission. Release builds are unchanged: the reader and its model exist in debug builds only. Quick Scan stays OFF in release; Story 10.11 is unchanged.
- **Evidence:** the spec's [concerns and evidence](../specs/spec-quick-scan-reliability/concerns-and-evidence.md), [failure modes](../specs/spec-quick-scan-reliability/failure-modes.md) and [fix plan](../specs/spec-quick-scan-reliability/fix-plan.md); `docs/evidence/10-18/quick-scan-litert-escalation.md`; the 10.19 device session.

---

## 1. Issue Summary

Story 10.22 fixes how long a corner takes to capture. Stories 10.20 and 10.21 fit the keep rule and the light cuts in Kotlin. Nothing planned measures whether a learned reader would read the faces better, and the standing constraint (PO 2026-11-22, earlier the same day) kept LiteRT in corner detection only.

What the evidence shows about sticker reading:

| Observation | Source |
| --- | --- |
| Capture 2 kept 0 of 6 faces (7, 7, 6, 8, 2, 6 confident stickers of 9) and fell back to face by face | 2026-11-22 bench |
| With a matching calibration, 3–5 of 6 faces kept; the shaded back face rejected in every calibrated run | 10.19 device session, runs L7 and L8 |
| All nine stickers of the red face and of the orange face UNCERTAIN together on a solved cube; shaded red and orange within ΔE 10 | 10.18 escalation log |
| A logo centre reads up to ΔE 36 apart between frames on a still cube | 2026-11-22 still burst |
| From Story 10.22 the light check only coaches, so more captures are taken in poor light | SCP 2026-11-22 quick-scan-reliability |

**Not established:** that a model reads these faces better than Kotlin. No labelled comparison exists. That is why this proposal adds a reader that only observes.

**Problem classification:** a planning gap and a constraint change. The safe way to answer the open question (CAP-3) was ruled out by the corner-only constraint.

---

## 2. Impact Analysis

### Change Navigation Checklist

| # | Item | Status | Finding |
| --- | --- | --- | --- |
| 1.1 | Triggering story | [x] | Story 10.22 (planned) and the 10.19 side-face results; spec CAP-3. |
| 1.2 | Core problem | [x] | No planned way to compare a learned sticker reader with Kotlin on real scans; the corner-only constraint blocked it. |
| 1.3 | Evidence | [x] | Bench captures, 10.19 device session, 10.18 escalation log. One device for the 2026-11-22 data. No model evidence yet. |
| 2.1 | Current epic | [x] | Epic 10 can complete as planned; one story is added. |
| 2.2 | Epic-level changes | [x] | Add Story 10.23 after 10.22, alongside 10.20. |
| 2.3 | Remaining epics | [x] | None affected. |
| 2.4 | New or obsolete epics | [N/A] | None. |
| 2.5 | Order | [x] | 10.19 → 10.22 → (10.20 ∥ 10.23) → 10.21. 10.23 blocks nothing. |
| 3.1 | PRD | [x] | No FR or NFR change. OQ-7 gains a dated update and a decision-log line. |
| 3.2 | Architecture | [!] | AD-9 "Detection method" amended; Stack line updated; `docs/ARCHITECTURE.md` aligned. No new AD, port in `:core:*`, or module. |
| 3.3 | UX | [N/A] | No Learner-facing change. The toggle is a debug tool. |
| 3.4 | Other artifacts | [x] | New `tools/sticker-model/` and `docs/evidence/10-23/` (created by the story); a `build-logic` check that release and benchmark carry no sticker model. |
| 4.1 | Direct adjustment | Viable | Effort medium-high (data pipeline and model), risk low for the product (no result changes). |
| 4.2 | Rollback | Not applicable | Nothing to revert. |
| 4.3 | MVP review | Not needed | Quick Scan is outside the first release raise; the reader is debug only. |
| 4.4 | Selected path | [x] | Option 1, direct adjustment. |
| 6.4 | sprint-status | [x] | 10.23 entry added. |

### Epic impact

Epic 10 gains Story 10.23. Stories 10.20, 10.21 and 10.22 keep their content; 10.22's note is clarified. 10.23 does not block 10.20, 10.21 or 10.11.

### Technical impact (for Story 10.23; nothing is implemented by this proposal)

- `tools/sticker-model/`: seeded dataset assembly, synthetic faces, training, evaluation, group hold-out check.
- `app/src/camera` debug source set: the shadow reader, its `.tflite` model and the debug toggle; the capture path hands it the three unwarped face crops.
- Debug `ScanDiagnostics`: model read beside both Kotlin reads, inference time, toggle state.
- `build-logic`: a check that release and benchmark contain no sticker model asset or reader class.
- No change to `:core:domain` classification, `QuickScanClassification`, `ScanAssembler` or the review. LiteRT is already in debug builds through the corner model.

---

## 3. Recommended Approach

**Direct adjustment: lift the corner-only limit for Quick Scan sticker reading and add Story 10.23 in shadow mode.**

- **Additive.** The reader has no input to the agreement gate, the keep rule, assembly or the review. A test must show the same scan result with the reader on, off and failing.
- **Quick to switch.** A debug-only control turns it on and off on the device without reinstalling.
- **No release cost.** The reader and model live in the debug camera source set, so release size (NFR-11) and the release classpath gates are unchanged.
- **Independent read.** The model reads the face image only and takes no Kotlin reading as input. If it later becomes the second read in the agreement gate, the two reads must be independent; an image-only model keeps that option open. The hybrid inputs described under CAP-3 (Kotlin candidates, earlier captures, centers) remain a later variant.
- **Later modes are not approved here.** (a) Second read: the model replaces the saved calibration in the agreement gate. (b) Rescue: the model decides stickers Kotlin left UNCERTAIN. Each needs the held-out per-sticker report, a size check and a further proposal.

**Training data (PO 2026-11-22):**

1. Existing `Fixtures/` and capture evidence from earlier commits. The tree holds 198 photographs and 91 JSON files today; some sources carry cell measurements without images, so the story starts with an inventory.
2. Additional real captures where the inventory shows thin coverage.
3. Synthetic faces targeting shaded faces, glare, red against orange and white against yellow.
4. Whole capture groups are held out for validation, so near-identical frames cannot leak across the split.

**Effort:** medium-high (one story: data pipeline, model, debug wiring, one device session). **Risk:** low for the product; the main risk is that existing real data is too thin for a meaningful held-out result, which the inventory and learning curve make visible. **Timeline:** nothing on the v1.0 path waits for it.

**Alternatives considered:** wait for Story 10.21 and decide then (rejected by the PO: the comparison data should accumulate from the 10.20 session onward); go straight to second-read mode (rejected: no evidence yet, and it changes results).

---

## 4. Detailed Change Proposals

### 4.1 Stories (`_bmad-output/planning-artifacts/epics.md`)

**A. New story, inserted after Story 10.22:**

~~~
### Story 10.23: A shadow sticker reader runs beside the Kotlin reads

As the developer tuning Quick Scan,
I want a small on-device model to read every captured face next to the Kotlin reads without deciding anything,
So that we learn on real scans whether a model reads shaded and glossy faces better, at no risk to the scan.

**Refs:** FR-22, FR-24 · recognition NFRs (Quick Scan, Confidence calibration), NFR-8, NFR-11 · AD-9, AD-13, AD-18 · OQ-7 · Stories 10.13, 10.18, 10.19, 10.22 · spec `_bmad-output/specs/spec-quick-scan-reliability/` (CAP-3; failure modes F7, F8, F9, F10)

**Depends on** Story 10.22 (per-hold diagnostics and the capture pipeline the reader hooks into). May run alongside Story 10.20. **Human gate:** a device session on the main app debug build, `QUICK_SCAN` raised, on the Lenovo TB128XU and the Galaxy S24 FE. *(SCP 2026-11-22 quick-scan-sticker-reader. PO decisions 2026-11-22: LiteRT is allowed for per-face sticker color classification; this story is shadow mode only, with a runtime debug toggle; the first model trains on existing fixtures and earlier capture evidence, extra real captures where coverage is thin, and synthetic data; whole capture groups are held out. The Kotlin classifier, the agreement gate and the keep rule are not changed here.)*

**Acceptance Criteria:**

**Given** the existing `Fixtures/` tree and the capture evidence committed earlier (`docs/evidence/`, including git history)
**When** the training set is assembled by a seeded, reproducible pipeline under `tools/sticker-model/`
**Then** an inventory in `docs/evidence/10-23/` lists every source, what it holds (face images or cell measurements only), its labels, and its coverage per device, cube and light. The set combines those real captures with synthetic faces that target shaded side faces, glare, red against orange and white against yellow. Where the inventory shows a device, cube or light with too few real faces, additional captures are recorded with the debug fixture recorder and added. Synthetic faces are used for training only.

**Given** the assembled set
**When** it is split
**Then** whole capture groups are held out: no group, and no frame of a held-out group, appears in training or tuning. The held-out groups are named in a committed file and cover both devices, a stickered and a stickerless cube, and at least one shaded or backlit group. A test fails when a group appears on both sides.

**Given** the sticker model
**When** it is exported
**Then** it takes one unwarped face image and returns, for each of the nine stickers, a color and a certainty that maps to `StickerConfidence` (AD-9: categorical only across the port). It reads the image only and takes no Kotlin reading as input, so its read is independent of both Kotlin reads. The `.tflite` file is under 500 KB, and its hash, recipe and tool versions are recorded as for the corner model (Story 10.18).

**Given** the held-out groups
**When** the evaluation runs
**Then** `docs/evidence/10-23/` reports, per device, cube and light, for the model and for the current Kotlin reads: stickers correct, uncertain and confidently wrong; where the two agree and disagree; the red/orange and white/yellow confusions; and results as the real training data grows (a learning curve). No bar is enforced; the report is routed to the PO.

**Given** a Quick Scan capture in a debug build with the reader on
**When** a corner is captured
**Then** the three unwarped face crops are read once by the model off the main thread, and `CubentiaScanDiag` logs per face the model's nine colors and certainties beside both Kotlin reads, with the inference time. The crops stay in memory and are discarded when the scan ends (AD-9, AD-13). The reader has no input to `QuickScanClassification`, the keep rule, `ScanAssembler` or the review: a test replays recorded captures with the reader on, off and failing (model missing, inference error) and gets the same `ScanResult` each time.

**Given** a debug build on a device
**When** the developer uses the debug-only reader control
**Then** the reader is switched on or off without reinstalling, the state is logged at the start of each scan, and it takes effect from the next capture.

**Given** the release and benchmark builds
**When** the build gates run
**Then** they contain no sticker model asset and no shadow-reader class: the reader and its model live only in the debug camera source set, a `build-logic` check fails otherwise, and the release size report is unchanged (NFR-11).

**Given** both reference devices
**When** a tester runs at least 10 Quick Scans per device with the reader on and 10 with it off
**Then** `docs/evidence/10-23/` records the inference time per capture (median and worst) and the per-hold capture times from the Story 10.22 diagnostics for both settings, showing the reader does not slow capture.

**Given** the Story 10.20 held-out corner groups, once they exist
**When** the evaluation is run on them
**Then** the same report is added to `docs/evidence/10-23/`. Letting the model take part in a result (as the second read in the agreement gate, or deciding stickers Kotlin left UNCERTAIN) is not part of this story: it needs that report, a per-sticker safety result and a further sprint change proposal.
~~~

**B. Epic 10 order line:**

OLD: `… → 10.19 → 10.22 → 10.20 → 10.21`
NEW: `… → 10.19 → 10.22 → (10.20 ∥ 10.23) → 10.21` with `*(SCP 2026-11-22 quick-scan-sticker-reader: 10.23, the shadow sticker reader, after 10.22; it does not block 10.20, 10.21 or 10.11)*`

**C. Story 10.22, dependency note:**

OLD: `Sticker reading, classification and gates stay in Kotlin.`
NEW: `Sticker reading, classification and gates stay in Kotlin in this story; Story 10.23 adds a LiteRT shadow reader that decides nothing, SCP 2026-11-22 quick-scan-sticker-reader.`

Rationale: 10.22 is unchanged; the note would otherwise read as a standing rule.

### 4.2 Architecture (`architecture/architecture-RubikXplorer-2026-10-29/ARCHITECTURE-SPINE.md`)

**AD-9, "Detection method":**

OLD: `Guided 6-face Scan and all sticker color classification stay pure Kotlin (per-cell histogram-mode reader + self-calibration from the six face centers + nearest-center classifier in `:core:domain`).`
NEW: `Guided 6-face Scan stays pure Kotlin, and the Kotlin sticker classifier (per-cell histogram-mode reader + self-calibration from the six face centers + nearest-center classifier in `:core:domain`) stays the reader of record for every scan.`

**AD-9, new item after "Detection method":**

> **Quick Scan sticker reading** (PO 2026-11-22, SCP 2026-11-22 quick-scan-sticker-reader, Story 10.23): LiteRT is also allowed for per-face sticker color classification on the unwarped Quick Scan faces. The model sits behind a sticker-reader seam in the camera source set and returns only categorical per-sticker results (a color and a `StickerConfidence`); face crops stay in memory and are discarded when the scan ends. Story 10.23 adds it in **shadow mode only**: the reader and its `.tflite` model live in the debug camera source set, run once per capture, log beside the Kotlin reads and have no input to classification, the keep rule, assembly or review, and a debug-only control switches it on and off. Release and benchmark builds carry no sticker model bytes and no reader class (`build-logic` check). Letting the model take part in a result needs a further SCP with held-out per-sticker evidence and the NFR-11 size check. Training and evaluation hold out whole capture groups.

**Stack, LiteRT row:** now names the debug-only shadow sticker-reader model beside the corner model. Frontmatter `updated: 2026-11-22`.

**`docs/ARCHITECTURE.md`:** the stack line and the "Quick Scan corner detection" row are aligned with the above.

Rationale: AD-9 said all sticker color classification stays pure Kotlin. The Kotlin classifier remains the reader of record; the amendment allows a LiteRT reader in Quick Scan and limits it to shadow mode until a further proposal. No new AD: the categorical-results rule, frame non-persistence and flag isolation already cover it.

### 4.3 PRD (`prds/prd-RubikXplorer-2026-10-29/prd.md`)

- **§9 OQ-7:** appended "Updated 2026-11-22" sentence: LiteRT allowed for Quick Scan per-face sticker classification, shadow mode in debug first, Kotlin stays the reader of record.
- **§10 decision log:** one line dated 2026-11-22 with the constraint lift and the training-data strategy.
- No FR or NFR change. NFR-11 is not affected while the model is debug only.

### 4.4 UX

No edit.

### 4.5 sprint-status (`implementation-artifacts/sprint-status.yaml`)

Added under `epic-10`, after the 10.22 entry: `10-23-a-shadow-sticker-reader-runs-beside-the-kotlin-reads: backlog`.

### 4.6 Spec (`specs/spec-quick-scan-reliability/`)

Through `bmad-spec`: the constraint "LiteRT stays in corner detection only" is replaced; a hold-out constraint is added; the two open questions answered by the PO are removed; the shadow reader moves from candidate to planned in `fix-plan.md`.

### 4.7 Memlogs

PRD, architecture and spec memlogs each receive the decision through `memlog.py`.

---

## 5. Implementation Handoff

**Scope: Moderate** (one story added within Epic 10; a narrowing architecture amendment).

| Who | What |
| --- | --- |
| Product owner | Approved 2026-11-22. Reviews the 10.23 held-out report and decides on any later mode. |
| Developer agent | Story 10.23 through `bmad-build`, after Story 10.22. |
| Product owner | Device session for 10.23 on both reference devices; extra fixture captures if the inventory asks for them. |

**Product-owner decisions (2026-11-22)**

1. **Governance and scope:** "LiteRT stays in corner detection only" is lifted to allow LiteRT for per-face sticker color classification (CAP-3). Implementation starts with shadow mode and a runtime debug toggle.
2. **Training data:** existing `Fixtures/` and capture evidence from earlier commits; additional real captures if edge-case coverage is insufficient; synthetic data targeting shaded faces, glare, and red/orange and white/yellow separation.
3. **Validation:** whole capture groups are held out to prevent leakage.

**Success criteria:** Story 10.23 merged with its tests; scan results proven identical with the reader on, off and failing; release and benchmark builds free of the model; a held-out report comparing the model with the Kotlin reads per device, cube and light, with a learning curve, in `docs/evidence/10-23/`.
