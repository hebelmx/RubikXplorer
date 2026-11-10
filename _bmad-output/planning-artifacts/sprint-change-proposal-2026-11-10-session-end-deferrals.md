---
title: 'Sprint Change Proposal: durable session end and device-session tracking'
date: 2026-11-10
status: pending-approval
approved_by: pending (PO approval via the PR)
trigger: Story 12.11 deferrals D1 and D2, device-gated reviews of Stories 3.7 and 12.1, and a flaky test from Story 12.12
scope: Minor
mode: batch (non-interactive; PO decisions taken from the request, conservative option chosen where open)
---

# Sprint Change Proposal: durable session end and device-session tracking

## 1. Issue Summary

The product owner asked for the open follow-ups from the 2026-11-10 session to be given stories and tracked in the backlog. There are four.

**D1. A failed snapshot delete on practice close is never retried** (Story 12.11, PR #98, logged deferral, medium).
- `SessionHost.clearSnapshotsNow` (`SessionHost.kt` ~:336-354) keeps the failed attempt rows in `pendingAttempts`, but drops the delete itself.
- The normal snapshot retry is "the next commit writes again" (AD-16, `SessionHost.kt` ~:605-623). That only happens while a session is active. An ended session has no next commit, so nothing retries the delete.
- A later cold start can then restore the closed practice and its unfinished attempt.
- It is reachable only on a Room write failure. *Reset progress* is already safe: it clears the snapshot and Checkpoint in the same writer step as the `ProgressReset` write, writes the reset only when both succeed, and keeps its dialog open with a retry.

**D2. An ordinary close deletes rows, against AD-10.**
- `RoomSessionSnapshotRepository.clear` (~:48) deletes every snapshot row when a session ends.
- AD-10, *Changes and deletes*, says "the only deletes are the Learner's scoped resets". The 2026-11-10 two-look SCP added *Reset progress* deleting the snapshot, but not an ordinary close.

**Device follow-ups.**
- Story 3.7 is merged but stays in `review` until Story 3.8 passes (SCP 2026-11-02).
- Story 12.1 is merged but stays in `review` until the physical TalkBack and Switch Access pass in `docs/accessibility-manual-check.md` is recorded.
- Story 12.1 made the short Lesson stage scroll instead of shrinking the cube (commit `1b4c4f9`). On a device, a vertical drag on the Lesson cube may compete with that scroll. No AC checks this yet.

**Flaky test.** `AppShellLibraryTest > last two look Lesson milestone practices a fresh LL scramble` (from Story 12.12, `AppShellLibraryTest.kt:341`) failed once in CI on PR #101. It passed on rerun and 3 of 3 times locally.

## 2. Impact Analysis

- **Epic impact.** Epic 12 gains one device-free story, 12.13, which runs before 7.8. No epic is added, removed or reordered. Epic 9 and Story 12.11 stay closed.
- **Story impact.**
  - New Story 12.13 fixes D1 and D2 and the flaky test.
  - Story 7.8 now also depends on 12.13.
  - Story 12.1 gets a device follow-up note (the drag check). Its ACs and status do not change.
  - Stories 3.8, 1.11 and 12.2 are not changed. The Epic 12 summary only cross-references them as the device batch.
- **Artifact conflicts.**
  - **PRD:** none. FR-4 and FR-12 already require resume. The fix makes "ended" stay ended.
  - **Architecture:** AD-10 gets one clarifying bullet (ending a session writes an *ended* snapshot and deletes nothing). The delete rule itself is unchanged.
  - **UX:** none. There is no new Learner copy. The drag check goes into the existing 12.1 device checklist.
  - **Epics:** the Epic 12 summary, the 7.8 dependency, the 12.1 note, and new Story 12.13.
  - **Sprint status:** `12-13-closing-a-session-is-durable-when-a-write-fails: backlog`, added by `sprint_plan.py generate`.
- **Technical impact.**
  - The work is in `:app` (`SessionHost`, `RoomSessionSnapshotRepository`, `SnapshotPayload`) and possibly the `SessionSnapshot` model in `:core:application`.
  - No Room schema change is expected, because the payload column carries the *ended* marker. If the schema does change, AD-10 requires a migration and a migration test.
  - The payload change needs a decoder for the previous format and a round-trip test.
  - No new dependency. No device is needed.

## 3. Recommended Approach

**Direct Adjustment.** One new story, **12.13**, run before 7.8. It is device-free, so it fits before the device batch.

### D1 and D2: recommendation

**Recommended: Option A, the *ended* snapshot.** When a session ends, persist an *ended* snapshot for that session through the normal revision-conditional snapshot write, instead of deleting rows. Restore treats an *ended* latest row as "no snapshot", so the existing Checkpoint resume is unchanged. A failed end write stays pending and is retried. The pending write is dropped once a later snapshot write supersedes it.

Why Option A rather than Option B (amend AD-10 to allow deleting on session end, plus a durable retry):

1. **It keeps AD-10's delete rule as written.** Deletes stay limited to the Learner's scoped resets. The spine only gains a clarification, not an exception.
2. **The retry is safe by construction.** The end write targets one session (kind, `sessionId`, revision) and goes through the existing `Stale` check.
   - Under Option B, retrying the current `clear()` deletes every kind's row. A retry that runs after the Learner opens a new Lesson would wipe that Lesson's snapshot. Making it safe needs a new scoped delete query, so the code is no smaller than Option A.
3. **It uses the persistence path that every other snapshot already takes:** one writer, revision-conditional, with attempt rows in the same transaction.

**Caveats, recorded honestly:**
- "Retried like any snapshot write" is not enough on its own. The existing retry waits for the next commit, and an ended session has none. Story 12.13 therefore adds an explicit retry: on the writer's next operation and when the app goes to the background. A pending end write is dropped once it is superseded (see the 12.13 ACs). Option B would need the same retry.
- **Residual risk:** if the end write fails and the process dies before any retry, a cold start can still restore the closed practice. The impact is small: the Learner sees a practice they had closed and can close it again. Nothing reset-locked can reopen, because the reset path is already atomic. This is recorded as an open question for the PO to accept.

### Device follow-ups: no new story

Each item already has an owning story whose AC blocks its closure, so a separate device-validation story would duplicate 3.8, 1.11 and 12.2.
- **3.7:** Story 3.8's last AC already keeps production acceptance of 3.7 blocked until 3.8 passes.
- **12.1:** its AC3 (the TalkBack and switch-access pass) is not yet met. The story stays in `review` until `docs/accessibility-manual-check.md` holds real results.
- **Drag vs scroll:** a check is added to the same 12.1 device checklist and noted under Story 12.1 in `epics.md`. It is an effect of 12.1's own change, so any fix is a 12.1 follow-up before it leaves `review`.
- The Epic 12 summary now lists the **device batch** (3.8, 1.11, 12.2 and the 12.1 physical pass), so the session has one checklist. The scope of 3.8, 1.11 and 12.2 is unchanged.

### Flaky test

It folds into Story 12.13 as an AC rather than an action item. `sprint_plan.py` cannot add action items, and this test exercises the LL-practice session lifecycle that 12.13 touches.

**Effort and risk**
- **Effort:** small to medium.
- **Risk:** low to medium, because the change touches the snapshot/restore path from Epic 6. The existing Room-backed restore and `SessionEndingTest` tests guard it.
- **Timeline:** adds one story before 7.8. The ~16-week plan does not change.

## 4. Detailed Change Proposals

### 4.1 Architecture (`ARCHITECTURE-SPINE.md`, AD-10)

OLD:
> - **Changes and deletes:** rows are never updated. The only deletes are the Learner's scoped resets (progress, calibration, preferences). *Reset progress* also ends the live session and deletes its snapshot (SCP 2026-11-10).

NEW (adds one bullet below it):
> - **Changes and deletes:** *(unchanged)*
> - **Ending a session (SCP 2026-11-10, session-end deferrals):** an ordinary end (closing practice, finishing a Lesson) deletes nothing. It writes an *ended* snapshot for that session through the revision-conditional snapshot write; restore treats it as no snapshot. A failed end write stays pending and is retried until it is saved or a later snapshot write supersedes it.

The frontmatter `updated:` changes to `2026-11-10`.

Rationale: it pins Option A so the code cannot drift back to deleting rows, and the delete rule itself stays unchanged.

### 4.2 Epics (`epics.md`)

**Epic 12 summary, *Also carries*:** after "12.11–12.12 … also before 7.8 (…)", add "and 12.13 (durable session end; Story 12.11 deferrals), also before 7.8 (this SCP)". Add a **Device batch** line: Stories 3.8, 1.11, 12.2 and the 12.1 physical pass. 3.7 stays in `review` until 3.8 passes; 12.1 stays in `review` until its physical pass is recorded.

**Story 7.8, *Depends on*:** add "and 12.13 (durable session end, this SCP)".

**Story 12.1, after AC3:**
> *Device follow-up (this SCP): the physical pass also checks, on the reference phone at 200% type, that a vertical drag on the Lesson cube turns or rotates the cube and does not scroll the Lesson stage (the stage became scrollable in this story). A conflict is fixed as a Story 12.1 follow-up before it leaves `review`.*

**New Story 12.13** (appended to Epic 12):

### Story 12.13: Closing a session is durable when a write fails

As a Learner,
I want a practice I closed to stay closed,
So that the app never brings back something I already ended.

**Refs:** FR-4, FR-12 · AD-10, AD-16 · Story 12.11 deferrals D1 and D2 · this SCP

**Acceptance Criteria:**

**Given** a live Lesson, practice or 2-look session
**When** it ends normally (`SessionEnded`: close or Back)
**Then** the host writes an *ended* snapshot for that session through the revision-conditional snapshot write, together with any pending attempt rows, and deletes no snapshot row (AD-10)

**Given** an *ended* snapshot is the latest row
**When** the app cold starts
**Then** no session is restored from snapshots, and the existing Checkpoint resume behaves as before

**Given** the *ended* snapshot write fails
**When** the host's writer runs its next operation, and when the app goes to the background
**Then** the pending end write is retried until it is saved, and a cold start after a successful retry restores nothing

**Given** a pending end write and a newer session that saves its own snapshot first
**When** the retry would run
**Then** the pending end write is dropped, so it never hides or replaces the newer session's snapshot

**Given** snapshot payloads written before this story
**When** they are decoded
**Then** they still restore as before; the *ended* marker ships with a decoder for the previous payload format and a round-trip test

**Given** *Reset progress*
**When** it runs
**Then** its behavior from Story 12.11 is unchanged: it deletes the snapshot and Checkpoint in the same writer step as the reset write and fails safely, with the dialog offering a retry

**Given** `AppShellLibraryTest > last two look Lesson milestone practices a fresh LL scramble` (failed once in CI on PR #101, passed on rerun)
**When** the story closes
**Then** the cause of the flake is found and fixed (or the test is made deterministic), and the test passes in at least 20 consecutive local runs

### 4.3 Device checklist (`docs/accessibility-manual-check.md`)

Add a *Gesture check* paragraph before the flow list. On the reference phone at 200% type, a vertical drag on the cube of a scrolling Lesson stage must turn or rotate the cube and must not scroll the stage. Record the result in the Flow 2 notes.

### 4.4 Sprint status (`sprint-status.yaml`)

Regenerated with `sprint_plan.py generate` (no hand edit):
- `12-13-closing-a-session-is-durable-when-a-write-fails: backlog` is added under Epic 12.
- `epic-12` stays `in-progress`.
- 3.7 and 12.1 stay `review`, and 3.8, 1.11 and 12.2 stay `backlog`.
- No action items are added. The flaky test lives in 12.13, and the device items live in their stories.

### 4.5 PRD and UX

No change.

## 5. Implementation Handoff

- **Scope:** Minor. One story is added, and planning text changes in the spine, the epics and one device checklist.
- **PO (hebelmx):** approve this proposal by merging the PR. The edits in 4.1–4.4 are already applied on branch `docs/scp-2026-11-10-session-end-deferrals`.
- **Dev, through `/bmad-orchestrate`:** run 12.13 by name, with the other device-free leftovers, before 7.8. Then run the device batch when the reference devices are connected: 3.8 (which closes 3.7), 1.11, 12.2, and the 12.1 physical pass including the gesture check (which closes 12.1).
- **Success criteria:**
  - 12.13 is merged with green CI and a passing acceptance gate.
  - The Story 12.11 durable-cleanup deferral is marked resolved in its spec.
  - The flaky test is fixed or made deterministic.
  - 3.7 and 12.1 reach `done` only after their device evidence is recorded.

## Open questions

1. Does the PO accept the residual risk that a failed end write followed by process death, before any retry, can still restore a closed practice once? The story treats this as acceptable. A fully durable guarantee would need the end write to happen before the session is reported as closed.
2. 12.13 is reachable only on a Room write failure. It is ordered before 7.8 as pre-pilot hygiene. Does the PO want it to gate the pilot, or may it slip after 7.8 if time is short?
3. Must the device batch (3.8, 1.11, 12.2, the 12.1 physical pass) finish before the 7.8 pilot, or can the pilot ship while 3.7 and 12.1 are still in `review`?

## Checklist record

| Section | Status | Note |
| --- | --- | --- |
| 1. Trigger and context | [x] | Story 12.11 spec deferral (D1), AD-10 conflict (D2), device-gated 3.7 and 12.1, CI flake on PR #101; checked against `SessionHost.kt` and `RoomSessionSnapshotRepository.kt` |
| 2. Epic impact | [x] | Epic 12 gains 12.13; 7.8 depends on it; no epic reordered |
| 3. Artifact conflicts | [x] | AD-10 clarified; PRD and UX unchanged; epics and device checklist updated |
| 4. Path forward | [x] | Direct Adjustment; Option A (*ended* snapshot) over Option B (amend AD-10 deletes) |
| 5. Proposal components | [x] | Sections 1–5 and open questions |
| 6. Final review and handoff | [!] | Pending PO approval via the PR |
