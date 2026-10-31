---
title: 'Sprint Change Proposal: split Story 1.10 into device-free and device parts'
date: 2026-10-31
trigger: 'Reference devices and the stickerless cube are not on hand; Stories 1.9 and 1.10 are blocked'
scope: moderate
status: approved
approved: 2026-10-31 by product owner
mode: batch
---

# Sprint Change Proposal: split Story 1.10 into device-free and device parts

## 1. Issue summary

**Problem.** Story 1.10 bundles three things. Two of them need no device: closing OQ-9 and adding the JVM and Macrobenchmark harnesses. One needs the two reference devices in hand: the on-device frame-timing baseline. Its first AC also waits on the Story 1.9 spike results, and 1.9 needs a stickerless cube. The product owner has neither the devices nor the cube right now. So all of 1.10 is blocked, and so is Story 3.3, which depends on 1.10's JVM benchmark harness.

**Discovery.** At the 1.10 human gate (orchestrator, 2026-10-31), the PO said the devices are not available and asked to defer the device work and continue development.

**Category.** A new practical constraint: hardware availability. No requirement is wrong.

**Evidence.**
- `epics.md` Story 1.10, AC1 ("Given the spike results from Stories 1.8 and 1.9") and AC3 ("Given the reference devices").
- `epics.md` Story 3.3, AC2 ("Given the JVM benchmark harness from Story 1.10").
- The PO decided OQ-9 on 2026-10-30 (keep about 16 weeks). That decision is recorded only in the orchestrator's local ledger, not in any planning artifact.
- ARCH-OQ-1 is already closed: the architecture memlog has the decision (line 59), and the spine's Reference devices row names Galaxy Tab S7 FE 5G (minimum) and Galaxy S24 FE (mid).

## 2. Impact analysis

- **Epic impact.** Epic 1 stays in progress. Story 1.10 shrinks to its device-free part. A new Story 1.11 holds the device part and stays deferred with 1.9. No other epic changes, and nothing is removed or resequenced. Epic 3 can start after the new 1.10.
- **Story impact.**
  - 1.10: AC1 decides OQ-9 without the 1.9 results, AC2 is re-pointed at the existing record, and AC3 becomes "harnesses build and run on demand" with no device baseline.
  - 1.11 (new): device baselines, the renderer re-measure, and the SceneView spike deletion (the "Deferred after this story" note moves here).
  - 3.3: no change. It still uses "the JVM benchmark harness from Story 1.10", which stays in 1.10.
  - 10.1: no change. It still depends on 1.9's `FaceSample` fixtures, which are far off.
  - 12.2: no change. It already does the full on-device performance check.
- **PRD.** OQ-9 is closed: keep the P0 schedule at about 16 weeks. The §6.2 `[ASSUMPTION]` becomes a decision, and §9 OQ-9 is marked closed. If the 1.9 spike later misses the ≥ 99% exit criterion, the PRD's existing fallback applies (the camera ships as beta, §6.2). That outcome reopens OQ-9 rather than blocking it now.
- **Architecture.** The spine's "Performance on the reference devices" line says "Story 1.10 measures it". It should say Story 1.11. This is a one-word reference fix, routed to a `bmad-architecture` update because the spine is final. The decision itself does not change.
- **UX.** None.
- **Technical.** No code changes in this proposal. The new 1.10 adds a benchmark module and a JVM harness. The spine already anticipates both (AD-14 and the "Performance on the reference devices" line), so neither is a new architectural element.
- **Orchestrator config.** The `before-start` gate on the old 1.10 key moves to the new 1.11 key. The new 1.10 is not gated. 1.9 keeps its gate.

## 3. Recommended approach

**Direct Adjustment.** Edit Story 1.10, add Story 1.11, close OQ-9 in the PRD, re-key sprint-status, and move the orchestrator gate.

- **Effort:** low (documentation only; about 1 hour).
- **Risk:** low. Deciding OQ-9 before the recognition spike was considered. The PRD already has a fallback for a failed spike (camera ships as beta), so the schedule decision does not depend on the result. A failed spike is an explicit reopen trigger.
- **Timeline:** this unblocks Epic 3 now. The device work waits until the PO has the hardware, and it must happen before Story 5.1 merges, since 5.1 has a device gate before merge.

Rejected alternatives: doing all of 1.10 later, which blocks 3.3 and stops Epic 3 at its third story; and having 3.3 build its own harness, which leaves 1.10's AC and 3.3's ref out of sync.

## 4. Detailed change proposals

### 4.1 Stories (`epics.md`)

**Story 1.10: title, user story, refs and ACs**

OLD:

```
### Story 1.10: Week-2 decision gate: schedule, reference devices and baselines

As the developer,
I want the open week-2 decisions closed with numbers,
So that the rest of P0 is planned against real devices and a confirmed scope.

**Refs:** NFR-1, NFR-2, NFR-3, NFR-13 · AD-14 · OQ-9, ARCH-OQ-1

**Given** the spike results from Stories 1.8 and 1.9
**When** the gate is held at the end of week 2
**Then** OQ-9 is decided (confirm ~16 weeks, or move scan uses to P1) and recorded, and if scope moves, `bmad-correct-course` is run on this epic list

**Given** ARCH-OQ-1
**When** the gate closes
**Then** a minimum and a mid-tier reference device are named and recorded in the architecture memlog

**Given** the reference devices
**When** a Macrobenchmark module and a JVM benchmark harness are added
**Then** they produce a baseline for frame timing on the 2D net (NFR-2) and a placeholder for the solve corpus (NFR-3), runnable on demand

**Deferred after this story:** once 1.10 has re-measured the OpenGL ES renderer … Tracked as a sprint-status action item.
```

NEW:

```
### Story 1.10: Week-2 decision gate: schedule and benchmark harnesses

As the developer,
I want the open week-2 decisions closed and the benchmark harnesses in place,
So that the rest of P0 is planned against a confirmed scope and performance can be measured on demand.

**Refs:** NFR-2, NFR-3 · AD-14 · OQ-9, ARCH-OQ-1

**Given** the Story 1.8 spike result and the PRD §6.2 fallback (camera ships as beta if recognition misses ≥ 99%)
**When** the gate is held
**Then** OQ-9 is recorded as decided (PO 2026-10-30: keep ~16 weeks) in the PRD memlog, and a Story 1.9 result below the exit criterion is recorded as a trigger to reopen OQ-9

**Given** ARCH-OQ-1, closed by the PO on 2026-10-30
**When** the gate closes
**Then** the architecture memlog and the spine's Reference devices row are confirmed to name the minimum and mid-tier devices, and nothing is re-recorded

**Given** no physical device is required
**When** a Macrobenchmark module (2D net frame timing, NFR-2) and a JVM benchmark harness (solve-corpus placeholder, NFR-3) are added
**Then** both build in CI, the JVM harness runs on demand with one Gradle command and prints its metrics, and the Macrobenchmark runs on demand against a connected device or emulator; device baselines are Story 1.11
```

**Story 1.11 (new), inserted after 1.10**

```
### Story 1.11: Reference-device baselines and SceneView spike removal

As the developer,
I want frame-timing baselines measured on the two reference devices,
So that the rendering choice is confirmed on real hardware and the spike code can go.

**Refs:** NFR-1, NFR-2, NFR-13 · AD-4, AD-14 · ARCH-OQ-1

**Given** the reference devices (minimum Galaxy Tab S7 FE 5G, mid Galaxy S24 FE) and the Story 1.10 Macrobenchmark module
**When** it runs on each device
**Then** a 2D net frame-timing baseline (NFR-2) is recorded per device

**Given** the OpenGL ES renderer from Story 1.8
**When** it is re-measured on both devices with the in-app `onDrawFrame` log
**Then** p90 ≤ 33.3 ms confirms the choice, and the result is appended to the architecture memlog; a p90 above 33.3 ms reopens the rendering choice (AD-4)

**Given** the choice is confirmed
**When** the story closes
**Then** the Story 1.8 SceneView spike is deleted: `app/src/spike3dSceneView/`, `app/src/spike3dNoSceneView/`, the `rubikxplorer.spike3d.sceneview` switch in `app/build.gradle.kts`, and the `sceneview` entry in `gradle/libs.versions.toml`
```

Rationale: the device-dependent work moves into 1.11 unchanged, and the spike deletion becomes an AC instead of a deferred note.

### 4.2 PRD (`prds/prd-RubikXplorer-2026-10-29/prd.md`)

**§6.2 NOTE FOR PM**, first line

OLD: `**The schedule grew from 10 weeks to about 16** [ASSUMPTION: ~16 weeks, to be confirmed by the end of week 2 — OQ-9].`

NEW: `**The schedule grew from 10 weeks to about 16** (confirmed by the PO on 2026-10-30, OQ-9; reopened if the recognition spike misses its exit criterion).`

**§9 OQ-9**

OLD: `9. **OQ-9:** confirm the P0 schedule of about 16 weeks, or move scan uses to P1 to shorten it. **Deadline: end of week 2**, together with the spike result.`

NEW: `9. **OQ-9 (closed 2026-10-30):** the P0 schedule of about 16 weeks is confirmed, and scan uses stay in P0. Reopen it if the Story 1.9 recognition spike misses ≥ 99% per-sticker accuracy.`

Also append a decision to the PRD memlog with `uv run _bmad/scripts/memlog.py append`.

### 4.3 Architecture (route to `bmad-architecture` update)

In `ARCHITECTURE-SPINE.md`, "Performance on the reference devices", change "Story 1.10 measures it" to "Story 1.11 measures it (harnesses from Story 1.10)". The decision does not change.

### 4.4 Sprint tracking (`sprint-status.yaml`)

- Rename the key `1-10-week-2-decision-gate-schedule-reference-devices-and-baseline` to `1-10-week-2-decision-gate-schedule-and-benchmark-harnesses` (`backlog`).
- Add `1-11-reference-device-baselines-and-sceneview-spike-removal: backlog`.
- In the action item `story-1-10-deferred-delete-sceneview-spike`, change "After Story 1.10 confirms" to "Story 1.11 AC3:", and point `ref` at Story 1.11. Keep it open until 1.11 closes.

### 4.5 Orchestrator gates (`_bmad/custom/bmad-orchestrate.toml`)

- Re-key the 1.10 `before-start` gate to `1-11-reference-device-baselines-and-sceneview-spike-removal`, with the reason "needs both reference devices connected for on-device baselines".
- The new 1.10 has no gate.
- The 1.9 gate is unchanged.

## 5. Implementation handoff

**Scope: Moderate**, meaning backlog reorganization (PO and Dev).

| Who | What |
|---|---|
| Correct-course (this PR) | §4.1, §4.2, §4.4 and §4.5 edits; the PRD memlog append |
| `bmad-architecture` update | §4.3, the one-line spine reference fix |
| Orchestrator | `/bmad-orchestrate story 1-10-week-2-decision-gate-schedule-and-benchmark-harnesses`, then `/bmad-orchestrate epic 3` |
| PO (hebelmx) | Run 1.9 and 1.11 when the stickerless cube and the reference devices are on hand, before Story 5.1 merges |

**Success criteria.**
- `sprint_plan.py validate` passes, and the status view lists 1.10 (not gated), 1.11 and 1.9 (both gated).
- The new 1.10 builds and merges with no device attached.
- Story 3.3's harness reference resolves to 1.10.
