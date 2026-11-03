---
title: Story 3.3 — defer measured JVM/device calibration to mandatory Story 3.8
status: approved
created: 2026-11-02
scope: moderate-backlog-reorganization
mode: batch
implementation_authorized: true
---

# Sprint Change Proposal

## 1. Issue and authorization

Story 3.3 has implemented clean-room two-phase search, independently verified corpus correctness and active cancellation, with green CI on PR38. Independent acceptance found two of three original criteria met: reference-device-equivalent JVM profile calibration was absent. Independent host evidence was 10,101 cases, zero failures and p95 20.868 ms; these are host measurements and do not establish NFR-3 device compliance.

The user explicitly instructed: “lets defere the device calibration, and merge as it is but with a course correction”. This authorizes the narrow calibration deferral and normal PR merge, conditional on recording required follow-up and revised acceptance/CI. It does not authorize dropping NFR-3 or declaring missing measurements passed. Batch mode applies to this already specified narrow adjustment; no new product choice is invented.

## 2. Impact analysis

Epic 3 retains all scope and exit criteria. Story 3.3 can close on implementation/correctness, cancellation, honest host metrics and its existing host performance regression threshold. Existing mandatory Story 3.8 receives calibration and calibrated performance acceptance before production Story 3.7 acceptance or Epic 3/P0.S closure. Its title, ID and canonical tracking key remain stable. Story 12.2 remains final physical production performance confirmation. No new epic, renumbering, removal or broad resequencing is needed. Stories 3.4–3.6 may continue against accepted APIs, as already planned.

The PRD FR-15, NFR-3, NFR-4 and release gates remain unchanged. Architecture AD-8 timeout, clean-room provenance, verification, loading isolation and AD-12 sealed failures remain unchanged. DESIGN/EXPERIENCE flows, preparation/cancellation/failure UI and accessibility remain unchanged. No source, test, CI, benchmark threshold, dependency or production loading-policy change is needed. No frozen PRD/spine edit or architecture decision append is required; no architectural choice has been made.

Remaining epics 4–12 remain viable without scope changes. Recovery still consumes the accepted solver contract; future release/hardware qualifications still require their own evidence. Story 12.2 links the received calibration evidence but retains its actual minimum-device corpus pass. This deferral gives no permission to defer unrelated hardware gates.

## 3. Recommended approach

Direct Adjustment: transfer one external evidence obligation to the existing hardware/production integration story. Planning effort is low; dependency/evidence tracking risk is moderate. Hardware scheduling remains unknown, so no completion date is claimed. Rollback would discard passing implementation without supplying calibration. MVP reduction is unnecessary and not authorized.

## 4. Exact edit proposals and transfer

### Story 3.3 benchmark criterion — OLD

Given the JVM benchmark harness from Story 1.10, when the corpus runs, then median, p95, memory and failure rate are reported, and 95% solve within 3 s on the reference-device-equivalent profile [ASSUMPTION: JVM proxy in CI, confirmed on device in Story 12.2].

### Story 3.3 benchmark criterion — NEW

Given the same harness, when the corpus runs, then median, p95, memory and failure rate are reported with the actual host/JVM profile, zero failures and at least 95% successful solves within 3 s pass as a host regression check; no reference-device equivalence is claimed from uncalibrated host results.

Corpus correctness (solved plus at least 10,000 random legal states including deep Scrambles, independently applied Solutions) and prompt active-search cancellation without partial publication stay in Story 3.3. The 3 s search timeout is unchanged. Independent acceptance and normal CI remain required before merging PR38.

### Story 3.8 — ADD mandatory acceptance

Given the minimum reference device and the reproducible Story 3.3 solve corpus, when the Developer establishes a reference-device-equivalent JVM profile using measured paired device/JVM runs and reruns that corpus on the calibrated profile, then calibration method, device/Android and host/JVM identities, revision, seed, warmup/table-readiness boundaries and run conditions are linked; every returned Solution is independently verified, zero failures are required, median, p95, memory and failure rate are reported, and at least 95% solve within 3 s on the calibrated profile. Uncalibrated host or emulator results cannot close this criterion.

Story 3.8 remains open while calibration or calibrated corpus evidence is missing, alongside its existing table hardware, installed-size, delivery-choice, memlog and production integration obligations. It must pass before production Story 3.7 acceptance or Epic 3/P0.S closure. Final production physical corpus evidence still belongs to Story 12.2 and is not replaced by proxy evidence.

### Traceability and tracking

| Obligation | Owner / receiving story | Gate |
| --- | --- | --- |
| Full corpus correctness, zero failures, cancellation, host metrics and regression | Developer / 3.3 | Revised independent acceptance and green CI before PR38 merge |
| Paired physical/JVM calibration and full calibrated corpus pass | Developer, device access supplied by hebelmx / 3.8 | Mandatory before production 3.7 acceptance and Epic 3/P0.S closure |
| Actual production corpus on minimum device, 95% within 3 s with metrics | Developer + hebelmx / 12.2 | Final physical NFR-3 and release acceptance |
| Existing table delivery/size/loading/StrictMode/Lesson independence | Developer + hebelmx / 3.8 | Existing gates unchanged |

An explicit `before-start` human gate for Story 3.8 is added in `_bmad/custom/bmad-orchestrate.toml`; orchestration must ask for the physical-device work and may not silently skip it.

Synchronized artifacts: epics.md, Story 3.3 operative spec, Epic 3 distilled context, docs/benchmarks.md and an explicit superseding note in the prior device-split proposal. Historical review/gate failures are preserved with a clear superseding amendment. Sprint tracking is regenerated with the installed helper: 3.3 stays review pending acceptance, 3.8 stays backlog, Epic 3 stays in-progress, 12.2 stays backlog. The helper has no action-item mutation API; enforceable follow-up acceptance lives in epics and the operational ledger, not an invented YAML status or a hand edit.

## 5. Checklist and implementation handoff

| Checklist items | Status | Disposition |
| --- | --- | --- |
| 1.1–1.3 | [x] | Trigger, evidence and narrow user instruction documented. |
| 2.1–2.5 | [x] | Same epics/scope, stable IDs, mandatory earlier 3.8 gate retained; all future epics remain viable. |
| 3.1–3.3 | [x] | PRD/architecture/UX behavior and requirements unchanged; no frozen artifact edit. |
| 3.4 | [x] | Spec, epics, context, benchmark docs, historical proposal, status and ledger synchronized; no CI changes. |
| 4.1 | [x] viable | Direct Adjustment, low planning effort/moderate evidence risk. |
| 4.2 | [x] not recommended | Rollback supplies no calibration. |
| 4.3 | [N/A] | No MVP reduction. |
| 4.4, 5.1–5.5 | [x] | Exact transfer, rationale and ownership defined. |
| 6.1–6.3 | [x] | Narrow user authorization above; no new independent scope added. |
| 6.4 | [x] | Installed sprint helper used; states retained until execution gates pass. |
| 6.5 | [x] | Orchestrator receives revised acceptance/CI/merge; Developer and device owner receive required 3.8 evidence. |

Scope is Moderate backlog reorganization. PO/Developer owns acceptance synchronization, the independent reviewer proves revised criteria, and the orchestrator updates PR38, runs normal CI and merges only after gates. Developer owns the actual calibration and reproducible evidence in 3.8; hebelmx supplies reference-device access. Success means PR38 merges with truthful host claims and all calibration, physical performance and production exit obligations remain required and discoverable. Nothing in this proposal marks missing device evidence passed.
