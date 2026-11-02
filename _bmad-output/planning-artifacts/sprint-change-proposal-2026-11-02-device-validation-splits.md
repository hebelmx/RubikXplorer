---
title: Device validation splits — Story 3.2 and backlog safety assessment
status: draft-awaiting-approval
created: 2026-11-02
scope: moderate-backlog-reorganization
mode: batch
implementation_authorized: false
---

# Sprint Change Proposal

## 1. Issue and evidence

Story 3.2 combines independently useful pruning-table infrastructure with physical-device measurements and a production delivery decision. PR [36](https://github.com/hebelmx/RubikXplorer/pull/36) remains unmerged. Independent full Gradle verification passes; the independent acceptance gate reports one external/human finding and zero implementation defects. No minimum-device generation measurement, installed-size result, physical StrictMode pass or production delivery choice has been established. Hardware absence is not a passing acceptance result.

Evidence: [current story](epics.md#story-32-pruning-tables-cached-off-the-main-thread), [implementation plan](../plan-3-2-pruning-tables-cached-off-the-main-thread.md), [measurement procedure](../../docs/pruning-tables.md), and `.git/bmad-orchestrate/ledger.md` entries for 2026-11-02. The deterministic payload is 4,031,685 bytes and its cache adds 40 bytes; neither fact proves installed size or reference-device performance.

The requested change is a backlog/acceptance decomposition, not a failed solver approach, strategic pivot or reduction of MVP scope. Recommend Direct Adjustment. Rollback would discard useful tested infrastructure without supplying hardware evidence. An MVP reduction is neither necessary nor proposed. Planning effort is low; dependency and evidence management risk is moderate. Hardware scheduling remains uncertain, so no invented delivery date or measurement is offered.

### Reconciled historical evidence

Story 1.9's implementation spec contains stale statements that the PRD memlog is absent and live evidence is still a before-merge condition. Later authoritative evidence supersedes those statements: the actual [PRD memlog](prds/prd-RubikXplorer-2026-10-29/.memlog.md) records S3-calibrated/S4-scored offline JPEG accuracy of **135/216 = 62.5%**, CAMERA_SCAN BETA and Manual Cube Editor primary. PR [34](https://github.com/hebelmx/RubikXplorer/pull/34) merged at `7d407673c80d3a20b5ca7431b9b68d26d5d30d3e` after the PO explicitly approved deferring the live CameraX/YUV matrix. Therefore do not reopen Story 1.9 or claim its BETA memlog record is missing. The actual residual debt is live CameraX/YUV evidence on both cube types and reference devices under documented conditions, plus the existing OQ-9 below-threshold reopening disposition. Offline JPEG results are measured evidence, but not live YUV accuracy, the complete four-lighting matrix, or a GA qualification.

Story 1.8's emulator-based merge was likewise explicitly approved; Story 1.11 retains real-device confirmation and conditional SceneView deletion. The SceneView per-ABI/AAB comparison deferred from 1.8 still needs explicit disposition before deleting that spike. Epic 2's release R8/content/Guide-me/411dp device-or-emulator smoke action remains open. These are existing accepted deferrals, not newly discovered missing merge permission.

## 2. Exact immediate edit: retain Story 3.2; add Story 3.8

Retain stable ID **3.2**, title and key `3-2-pruning-tables-cached-off-the-main-thread`; this preserves PR36 and implementation-plan traceability. Add numeric **3.8**, key `3-8-reference-device-pruning-table-delivery-decision-and-production-integration`. Do not use suffix IDs such as 3.2a/3.2b or renumber 3.3–3.7.

### Story 3.2 — before

As a Learner,
I want the Solver ready quickly without bloating the app,
So that solving feels instant and the download stays small.

**Refs:** FR-15 · NFR-3, NFR-11 · AD-8, AD-16 · AR-12, AR-22 · UX-DR25

**Given** the pruning tables
**When** they are generated
**Then** their size and build time are measured on the minimum reference device, and the bundled-vs-first-run choice is decided against NFR-11 and recorded in the architecture memlog

**Given** first-run generation (if chosen)
**When** tables are built
**Then** it happens on `Dispatchers.Default`, results are cached in app-private storage, Lessons never wait on them, and StrictMode reports no main-thread disk access

### Story 3.2 — proposed replacement

As the developer,
I want tested clean-room pruning-table generation, optional off-main cache infrastructure and a reproducible device measurement harness,
So that search can use verified table APIs while production delivery is decided from real-device evidence in Story 3.8.

**Refs:** FR-15 · NFR-3, NFR-11, NFR-12 · AD-1, AD-8, AD-11, AD-12, AD-16 · AR-12, AR-22 · UX-DR25

**Acceptance Criteria:**

**Given** the clean-room Story 3.1 coordinate transitions
**When** phase-1 twist/slice and flip/slice and phase-2 corner/slice and edge/slice pruning projections are generated
**Then** all reachable entries have breadth-first distances from solved, solved entries have distance zero, graph consistency and descending-path tests pass, and the API exposes admissible phase heuristics and deterministic payload size without third-party solver code

**Given** the optional pruning-table provider
**When** tables are generated or a versioned private-file cache is loaded or replaced
**Then** CPU generation uses `Dispatchers.Default`, disk access uses an I/O dispatcher, the bounded cache validates its version and checksum, corrupt data is regenerated, replacement is atomic, and load failures are sealed values

**Given** cancellation, concurrent callers and failed cache writes
**When** provider tests run
**Then** cancellation is cooperative and retryable, concurrent loads are serialized, no partial table result is published, and a failed replacement preserves the prior destination and cleans temporary files

**Given** the debug measurement Activity and documented adb procedure
**When** the debug build is produced
**Then** the harness can report device identity, cold-process generation duration, payload/cache bytes, an independent warm disk-cache load and main-thread StrictMode violations; no unrun physical result is claimed

**Given** app startup and Lessons
**When** the infrastructure is installed
**Then** neither calls or awaits it, and no bundled-versus-first-run production policy is selected by this story

**Given** the implementation and acceptance record
**When** Story 3.2 is independently reviewed
**Then** JVM/cache/cancellation tests and CI-equivalent gates pass, host and format facts are distinguished from device results, and every physical measurement, delivery decision, production wiring and applicable StrictMode obligation transferred below remains required in Story 3.8

**Closure boundary:** Story 3.2 can close independently after this replacement is approved, its independent acceptance gate is rerun and normal PR/CI gates pass. Approval alone does not mark it done or authorize a fabricated production decision. Its current before-merge hardware gate is superseded only for this revised infrastructure scope.

### New Story 3.8 — proposed exact text

As a Learner,
I want pruning tables delivered using a measured production strategy,
So that solving is ready without freezing Lessons or exceeding the app's size budget.

**Refs:** FR-15 · NFR-3, NFR-5, NFR-11 · AD-8, AD-11, AD-13, AD-16 · AR-12, AR-22 · UX-DR25

**Dependencies:** accepted Story 3.2 infrastructure; production loading integration coordinated with Story 3.4. May proceed after 3.2 while search is developed. Required before production end-to-end acceptance of Story 3.7 and closure of Epic 3/P0.S delivery readiness. Story 12.2 remains the final device solve-corpus performance confirmation.

**Acceptance Criteria:**

**Given** the minimum reference device, Samsung Galaxy Tab S7 FE 5G, and the Story 3.2 harness
**When** repeated cold-process generation and independent warm-cache loads run
**Then** generation duration, payload/cache bytes and cache loading duration are recorded with device/Android identity, revision, run conditions and available memory evidence; no host timing or emulator measurement substitutes for the minimum-device result

**Given** a release-equivalent unbundled baseline and a temporary bundled candidate
**When** installed size and table delivery costs are measured
**Then** the measurement method, installed totals/deltas, cache cost and recognition-model budget are recorded against NFR-11's install size below 30 MB; APK file size alone is not accepted as installed-size evidence

**Given** those device and size measurements
**When** bundled versus first-run delivery is selected
**Then** the rationale, tradeoffs and evidence are appended to the architecture memlog using `uv run _bmad/scripts/memlog.py append`, and the selected mode respects AD-8, offline operation and NFR-11

**Given** the selected production delivery mode
**When** it is integrated into the production Solver loading path
**Then** table generation/access remains off-main; if first-run is chosen, generation uses `Dispatchers.Default` and results are cached in app-private storage; bundled delivery also uses off-main loading and any applicable private cache; Lessons never wait for readiness and no app-startup wait is introduced

**Given** the actual selected production integration on the minimum reference device
**When** cold and warm loading paths run under debug StrictMode
**Then** no main-thread disk access is reported, logs are inspected, Lessons remain independently usable, and cancellation/failure never publishes an invented or partial Solution

**Given** completed integration
**When** the story is reviewed
**Then** affected correctness, CI and release-size checks pass and link their evidence; Story 3.8 remains open if any required hardware result, delivery decision, memlog append or production verification is unavailable

## 3. Requirement transfer and sequencing

| Original obligation | 3.2 after split | Required receiving acceptance |
| --- | --- | --- |
| Clean-room pruning tables | Generation and graph/API correctness | 3.8 consumes tested implementation |
| Minimum-device size/build time | Harness only | 3.8 AC1–2 |
| Bundled versus first-run decision against NFR-11 | Explicitly pending | 3.8 AC2–3 |
| Architecture memlog decision | No fabricated decision | 3.8 AC3, append tool only |
| Default generation/private cache | Optional infrastructure/tests | 3.8 AC4 selected production path |
| Lessons never wait | No startup/Lesson caller now | 3.8 AC4–5 production regression check |
| StrictMode no main-thread disk | Instrumentation support, no physical claim | 3.8 AC5 actual selected integration |
| NFR-3 physical solve performance | Not claimed by table tests | 3.3 JVM proxy plus 12.2 real-device corpus; P0.S cannot claim physical compliance prematurely |

**Dependency edit before → after:** the epics Overview currently says stories never depend on a later story. Replace that sentence with: “Story IDs are stable identifiers. Stories normally execute in numeric order; approved split follow-ups may execute earlier than their appended ID. Story 3.8 depends on 3.2 and must pass before production acceptance of 3.7. Stories 3.3–3.6 may proceed against accepted APIs without selecting an unmeasured production delivery policy. Across epics, preserve the existing dependency direction and explicitly documented parallel tracks.”

**Epic 3 overview addition:** “Story 3.8 owns reference-device pruning-table measurements, the production delivery decision and its integration. Story 3.3 depends on 3.2's verified APIs, not on the packaging decision. Story 3.4 may implement and test its injected port/effect contract before 3.8; final production loading is integrated and accepted in 3.8. Story 3.7 cannot receive production end-to-end acceptance before 3.8 passes. Epic 3/P0.S closure preserves NFR-3 and all original exit criteria; the existing JVM proxy does not establish physical performance.”

This requires an explicit execution-order exception rather than renumbering stable stories. 3.5 editor and 3.6 playback do not require a packaging decision. Epic 4 rule/recovery work can proceed independently; any production call to CubeSolver must use the accepted delivery path. No new Lesson dependency on table readiness is permitted.

## 4. Full backlog disposition — recommendations, not authorized rewrites

Only the Story 3.2/3.8 changes above are proposed for immediate implementation. All other split candidates below require their own concrete AC/dependency proposal and approval before any existing story is rewritten. Physical reference measurements, emulator instrumentation, participant research, store credentials and content/legal decisions are different dependencies.

| Stories | Recommendation and safe scope boundary | Gate that must remain | Source |
| --- | --- | --- | --- |
| 1.1–1.7 | Already done; no reopening or split. Keep existing deferred findings in their owning actions. | Ordinary quality/deferred obligations remain. | epics.md Stories 1.1–1.7; sprint-status.yaml |
| 1.8 | Already done with explicitly approved emulator evidence. | 1.11 confirms physical GL timing; dispose of SceneView per-ABI/AAB comparison before deletion. | ledger PO decision 2026-10-30; epics.md 1.11 |
| 1.9 | Already done under explicit PO live-matrix deferral; offline 62.5% BETA memlog exists. Reconcile stale spec later, do not invent missing memlog debt. | Live CameraX/YUV two-cube/two-device evidence early enough to inform Epic 10; complete full matrix for GA; explicitly dispose of OQ-9 reopening trigger. | PRD .memlog.md final result; ledger PR34/PO decision |
| 1.10 | Already complete infrastructure split; no further split. | Cannot stand in for 1.11 baselines. | epics.md 1.10–1.11 |
| 1.11 | Already dedicated device decision story; keep open. | Physical renderer choice confirmation before Epic 5 engine commitment; delete spike only after confirmation. | epics.md 1.11 |
| 2.1–2.7 | Already done; retain open retro actions. Do not conflate UI smoke with missing reference performance. | Release R8/content/12 Lessons/Guide-me/411dp smoke can use device or emulator; existing architectural/text actions remain. | epic-2-retro; sprint-status action_items |
| 3.1 | Already done; no split. | Clean-room coordinate correctness unchanged. | epics.md 3.1 |
| 3.2 | Immediate infrastructure-only replacement above. | Rerun independent acceptance before normal PR merge. | plan-3-2; docs/pruning-tables.md |
| 3.3 | No further split: existing JVM corpus and benchmark are independently executable. | ≥10,000 states, deep Scrambles, re-verification, cancellation, all metrics; retain explicit JVM proxy limitation and 12.2 device confirmation. | epics.md 3.3 |
| 3.4 | No device split; implement injected SolveCube effect/timeout/orientation/re-verification contracts. | No premature production packaging choice; actual loading accepted in 3.8. | epics.md 3.4; AD-8 |
| 3.5–3.6 | No split: editor validation/accessibility and playback/notes use local inputs. | Do not defer invalid-state rejection, generated-note honesty or playback correctness. | epics.md 3.5–3.6 |
| 3.7 | Development with doubles can proceed. | Production end-to-end acceptance explicitly depends on 3.8; cancel/failure/readiness UI remains here. | epics.md 3.7 |
| 3.8 | New mandatory device/production story. | All transferred original ACs; no post-MVP deferral. | section 2 above |
| 4.1–4.6 | No split. Pure assessment, diagnosis, hints, checkpoint recovery and configured adaptation do not need physical measurements. | Correctness/scenarios stay in feature acceptance; any actual solver delivery uses accepted path. | epics.md 4.1–4.6; AD-3/5/6/8 |
| 5.1 | Conditional narrow candidate: implementation plus emulator renderer-sync suite, with exact physical ≤250 ms measurement explicitly owned by 12.2 or a dedicated follow-up. | 1.11 decision before engine commitment; do not defer renderedFacelets sync instrumentation or glyph correctness. 12.2 must gain exact transferred assertion before split. | epics.md 5.1, 1.11, 12.2 |
| 5.2 | Conditional narrow candidate for physical ≤100 ms measurement; 12.2 already owns responsiveness. | Keep pure pose/direction fixtures, intent/event path and haptic implementation in feature acceptance. | epics.md 5.2, 12.2 |
| 5.3–5.4 | No split. View/cube rotation, deterministic animation behavior, arrows/highlights and reduce motion can be tested locally/emulator. | Final physical performance check remains 12.2; exact behavior is not deferred. | epics.md 5.3–5.4 |
| 5.5 | No split by default: TalkBack services can run on emulator. | Keep runtime focus/summary/read-faces/announcements acceptance; final all-flow audit remains 12.1. | epics.md 5.5, 12.1 |
| 6.1–6.5 | No split. Room migration/transaction/process-death, DTO recovery, preferences and Continue are local/emulator foundations. | Forced kill/restart and persistence acceptance precede dependent progress/resume implementation. | epics.md 6.1–6.5; AD-10 |
| 7.1–7.7 | No split. Content, practice/mastery, gating, trends and skip-ahead are deterministic/local. | Legal/harmful paths and Course usability precede pilot; no reduced curriculum criteria. | epics.md 7.1–7.7 |
| 7.8 | Conditional candidate: release workflow and pilot protocol preparation can close separately from actual signed distribution. | Secrets, Play account, manual promotion and ~8 participants are external prerequisites; actual pilot access must occur early enough for feedback, not be pushed to production end. | epics.md 7.8; PRD §7 gate4; AD-15 |
| 8.1–8.5 | No physical-device split just because flow is called Physical-cube Mode. Reducer state and confirmation/mismatch UI can use entered observations/emulator. | Honesty, undo barriers, confirmation and restore remain feature acceptance. | epics.md 8.1–8.5; AD-3/5 |
| 8.6 | No split: end-to-end instrumented virtual/physical software flows can run on emulator. | Keep kill/resume and zero silent desync; real-cube human usability is separately evaluated by pilot. | epics.md 8.6; PRD §7 gate3 |
| 9.1 | No device split; OQ-2/6 are curriculum/provenance decisions. | Decide primary Algorithms/Case set and licensing/source provenance before dependent Library/2-look content; memlog sources required. | epics.md 9.1; PRD OQ-2/6 |
| 9.2–9.8 | No split. Matching/verification, diagrams, filters/status logs, Lesson predicates and mastery are local. | All78 verification and stable references remain build gates. | epics.md 9.2–9.8; AD-19 |
| 10.1 | Fixture/classifier implementation can proceed using honestly labeled source data. No blanket device split. | Do not present synthetic fixtures or offline JPEG accuracy as live CameraX matrix qualification. Resolve 1.9 live acquisition debt early enough to influence classifier/capture decisions. | epics.md 10.1; PRD §4.8; 1.9 ledger |
| 10.2 | Conditional candidate for adapter/contracts/test-double infrastructure only. | Real-camera alignment/stability/orientation/glare/blur coaching must be exercised early, before dependent capture integration is accepted. Cannot move all live behavior to end-stage 10.8. | epics.md 10.2; AD-9 |
| 10.3 | No split: correction/typed results/uncertain-sticker/validation gates use supplied samples. | Invalid or unresolved input must never be accepted; scan flow never owns session. | epics.md 10.3 |
| 10.4 | No automatic split: calibration ownership/persistence/reset and screen flow test locally. | Real cube calibration effectiveness belongs to live integration/matrix; do not claim it from mocked swatches. | epics.md 10.4; PRD FR-24 |
| 10.5 | No blanket split: flow, validation, denied-camera and manual fallback test with samples/emulator. | Live scan-to-solve acceptance follows working 10.2; BETA/manual primary policy preserved. | epics.md 10.5 |
| 10.6–10.7 | No split of evidence correctness: partial consistency, unique match, stale revision, adoption and software Quick Check flow are testable. | Zero false VERIFIED remains scenario acceptance, not postponed to devices; real capture success measured in 10.8. | epics.md 10.6–10.7; AD-5 |
| 10.8 | Already dedicated hardware/matrix/GA-BETA decision. | Required full evidence and working manual fallback; no GA from missing measurements. BETA fallback follows PRD, does not waive validation/privacy. | epics.md 10.8; PRD §4.8, §7 gate6 |
| 11.1 | Conditional candidate: capture/assembly/fallback implementation versus measured Quick Scan qualification. | OQ-7 method and size choice before committing implementation; actual matrix ≥95%/≤3 corrections/<15s before GA, otherwise BETA. | epics.md 11.1; PRD FR-22 |
| 11.2 | No split: Placement is deterministic over accepted Full state. | Stage evaluation, skipped-not-mastered, user choice and OFF links stay in acceptance. | epics.md 11.2 |
| 11.3 | Conditional candidate: matcher/UI and ambiguity contracts versus live capture accuracy/speed qualification. | Keep candidate-set correctness in implementation; ≤1% wrong unique Case and <1s real capture evidence before GA. Exact accuracy requirement must be explicitly linked if later split. | epics.md 11.3; PRD §4.8 |
| 12.1 | Already final accessibility audit. | Contrast/semantics/local large-text feature checks are not delayed; final TalkBack/switch/200% all-flow audit still required. | epics.md 12.1; UX Accessibility Floor |
| 12.2 | Already final physical performance/compatibility acceptance. | Minimum/mid device targets, solve corpus metrics and rotations remain required; API29–30 coverage is emulator-only per spine. Add exact transferred ≤250ms if 5.1 later split. | epics.md 12.2; spine Reference devices |
| 12.3 | Conditional candidate: local size/privacy/offline hardening versus actual closed-testing vitals/Play declaration. | Vitals require distributed usage, not unit tests; ≥99.5% crash-free/ANR and no-data-collected declaration remain production gates. | epics.md 12.3; NFR-8/9 |
| 12.4 | Keep integrated release gate story, no infrastructure-only closure. | Pilot research, severity1/2 resolution, SM1–9 baselines and all six evidence links remain mandatory. | epics.md 12.4; PRD §7–8 |
| 12.5 | Conditional candidate: repo/listing preparation versus external publication/promotion. | Trademark/name decision precedes branded listing assets; privacy page, content rating, screenshots/video and manual Play promotion remain required. Repo public only after app works end-to-end. | epics.md 12.5; OQ-1; AD-15 |

No new epic is required; none is obsolete. Feature order only changes to make independent implementation available while retaining early decisions that could invalidate dependent work. All original phase exits and release gates remain. Camera BETA/cut policy comes from the existing PRD, not a new general permission to postpone device work.

## 5. Exact artifact changes proposed for approval

1. **epics.md:** replace Story 3.2 body/ACs with section2; append Story3.8; replace ordering convention and extend Epic3 overview exactly as section3. Preserve every other story, FR/NFR inventory, cut order and phase gate.
2. **implementation-artifacts/sprint-status.yaml:** add `3-8-reference-device-pruning-table-delivery-decision-and-production-integration: backlog` within Epic3. Retain 3.2's exact key and `review` until independent acceptance; retain Epic3 `in-progress`. Do not mark 3.8 or Epic3 done. Record required follow-up/dependency in supported action/notes form rather than inventing a new status enum.
3. **plan-3-2-pruning-tables-cached-off-the-main-thread.md:** preserve historical original ACs/gate results as history; add dated approved scope amendment, replacement acceptance contract and explicit transfer to3.8. Replace current operative “before merge” references only with this approved scoped gate; retain truthful missing evidence.
4. **docs/pruning-tables.md:** explain infrastructure closure versus mandatory3.8, keeping exact physical measurement/installed-size/production StrictMode procedure and all missing-evidence disclaimers. `docs/benchmarks.md` only needs a cross-reference if its current references conflict; do not broaden performance claims.
5. **Architecture memlog:** append approved split/traceability decision with memlog.py, explicitly saying production bundled/first-run remains pending. This is not the eventual delivery-choice append. No substantive AD change.
6. **PR36:** update scope/acceptance description to approved3.2 and link new3.8/proposal. Rerun independent acceptance and normal CI; merge follows existing authorized orchestration only after those gates.
7. **Operational ledger:** after approval, record revised gate and dependency sequencing so orchestration does not retry missing hardware as a code defect or silently skip3.8. Preserve prior events.

**Not included in immediate edits:** rewriting 5.1/5.2/7.8/10.2/11.1/11.3/12.3/12.5, reopening1.9, changing its BETA decision, lowering release gates or changing UX. The stale architecture Deferred pointer “Story1.10 measures it” should be corrected to1.11 through a separately scoped authorized architecture update; it is a pre-existing traceability inconsistency, not a prerequisite to3.2 infrastructure acceptance. The current PRD and DESIGN/EXPERIENCE need no behavioral changes for this proposal. All Learner-facing preparation, failure, manual fallback and uncertainty states are preserved.

## 6. Checklist and handoff

| Checklist | Status | Disposition |
| --- | --- | --- |
| 1.1–1.3 trigger/problem/evidence | [x] | Story3.2 mixed implementation/hardware acceptance; evidence linked; no fabricated numbers. |
| 2.1–2.5 epic impact/order | [x] | Same scope/epics; stable-ID split plus explicit dependency placement; all remaining stories assessed. |
| 3.1 PRD | [x] | No requirement/MVP reduction; all production gates retained. |
| 3.2 architecture | [x] | ADs unchanged; delivery decision pending; existing1.10 pointer inconsistency identified. |
| 3.3 UX | [x] | No flow/design behavior changes; preparation and fallback remain. |
| 3.4 secondary artifacts | [x] | Plan/docs/status/PR/ledger edits enumerated; CI gates unchanged. |
| 4.1 direct adjustment | [x] viable | Low planning effort, moderate dependency-management risk. |
| 4.2 rollback | [x] not recommended | Does not supply missing device evidence. |
| 4.3 MVP reduction | [N/A] | Not requested or necessary; goals retained. |
| 4.4 chosen path | [x] | Direct Adjustment; Moderate backlog reorganization. |
| 5.1–5.5 proposal/handoff | [x] | Concrete ACs, transfer, sequencing and ownership included. |
| 6.1 checklist review | [x] | Pending approval/mutations explicitly recorded. |
| 6.2 proposal accuracy | [x] | Independent acceptance reviewer confirmed proposed 3.2 matches implemented/tested scope, all original obligations transfer to 3.8, and production dependency gates remain intact. User approval remains pending below. |
| 6.3 explicit approval | [!] | Awaiting user approval; not implied by preparing draft. |
| 6.4 sprint updates | [!] | Only after approval; no existing artifacts changed in this drafting step. |
| 6.5 confirm handoff | [!] | Proposed roles below; confirm on approval. |

PO owns approved backlog/AC/traceability synchronization. Developer owns revised3.2 acceptance, selected-mode implementation in3.8 and evidence links. Owner supplies reference-device access and human/external Play/pilot/trademark decisions. Architect reviews measured results that reopen binding choices; append decisions only with memlog tool. No user messages or publication actions are sent by this proposal.

Success means PR36 can be judged on a concrete independent infrastructure contract, Story3.3 can proceed without pretending that physical measurements passed, mandatory3.8 blocks production solver acceptance, and all original MVP/release evidence remains discoverable and required. This document is a reviewable draft; implementation approval is still pending.
