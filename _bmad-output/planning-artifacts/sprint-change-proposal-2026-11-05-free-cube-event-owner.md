# Sprint Change Proposal: One event owner for the free cube

- **Date:** 2026-11-05
- **Trigger:** A Story 5.2 PO decision (ledger 754, 772; `spec-5-2-…md`, "Product owner decisions (a)"), and Epic 5 retrospective action item 1 (`epic-5-retro-2026-11-05.md`, PR #55)
- **Mode:** Batch
- **Status:** Approved by PO 2026-11-05
- **Scope:** Moderate. It adds one backlog story and amends the wording of one AD and one done story.

## 1. Issue Summary

AD-4 says: "every input source (gesture, button, accessibility action) produces a `MoveIntent`. The ViewModel maps every `MoveIntent` to the same `MoveCommitted` event." The Story 5.2 AC (`epics.md:1116-1118`) says a recognized gesture "goes through the same `MoveCommitted` path as a Move key".

In a Lesson this holds, because gestures commit through `SessionEvent` (since 5.4). The free cube is different. It is not a Learning Session (AD-3, AD-20), so it has no event type and no reducer. `FreeCubeViewModel` writes `MoveHistory` directly in five methods: `onMoveIntent`, `onUndo`, `onRedo`, `onReset` and `onScramble`. Each one builds its own `PresentationItem` and revision. The gestures and the Move keys do share `onMoveIntent`, so behaviour is correct today. But the "single `MoveCommitted` path" exists only by convention. Any new input or command can bypass it, and the transition logic is untested JVM-side because it lives in an Android ViewModel.

The Story 5.2 Codex gate flagged this as high (`FreeCubeViewModel.kt:54`). The PO accepted the shared path as equivalent for 5.2 and asked for a follow-up story.

**Evidence:** `app/.../ui/freecube/FreeCubeViewModel.kt:54-80`. `ARCHITECTURE-SPINE.md:94` lists the free cube under "Not sessions", and `:138` holds the AD-4 commit-first rule. `core/application/.../session/Reduce.kt` and `Presentation.kt` are the Lesson pattern to mirror.

## 2. Impact Analysis

| Area | Impact |
|---|---|
| Epics | Epic 5 is done and stays closed. Only the wording of the Story 5.2 AC changes, and the story stays `done`. Add **Story 6.6** at the end of Epic 6. Nothing else in the epic changes. |
| Epic 6 ordering | **No dependency.** The retro asked whether persisted free-cube history needs this story first. It doesn't, because the free cube is never persisted: AD-3 and AD-20 say "not a session", and Epic 6 snapshots and Checkpoints are keyed by `SessionKind` (`LESSON`, `PRACTICE`, `TWO_LOOK`, `SOLVE_PLAYBACK`). Story 6.4's preferences (glyphs, haptics) go through `UpdatePreference` and are not part of free-cube state. |
| PRD | No conflict. FR-1 and FR-3 behaviour is unchanged. |
| Architecture | AD-4: clarify that on the free cube, `MoveCommitted` is a free-cube event reduced by its own pure reducer. Ownership Matrix: add a free-cube row. AD-3 and AD-20 are unchanged, because the free cube stays outside the session model. |
| UX | None. There is no visible change. |
| Code / tests | Story 6.6 adds a pure reducer in `:core:application` with JVM tests and turns `FreeCubeViewModel` into a dispatcher. The existing 2D/3D sync suites and the 5.2 gesture tests must pass unchanged. |

### Checklist summary

- [x] 1 Trigger and context: the 5.2 gate high and the PO's follow-up request. Issue type: a technical gap between the AD-4 rule and the free-cube implementation.
- [x] 2 Epic impact: Story 5.2 AC wording, plus the new Story 6.6. No epic is invalidated or reordered.
- [x] 3 Artifact conflicts: epics.md and the ARCHITECTURE-SPINE (AD-4 and the Ownership Matrix). PRD [N/A], UX [N/A].
- [x] 4 Path: Direct Adjustment. Rollback [N/A]. MVP review [N/A].
- [x] 5 Proposal components: see below.
- [x] 6 Final review and handoff: see below.

### Options considered

- **Wording only** (amend AD-4 and the 5.2 AC to bless `onMoveIntent` as the path). Rejected, because it leaves five unguarded writers and no JVM tests.
- **Make the free cube a `SessionKind`.** Rejected: it would reverse AD-20, bring the free cube into suspend/resume and snapshot rules, and change the PRD's "nothing at stake" free-cube behaviour.
- **Chosen:** a separate pure free-cube reducer. It mirrors the Lesson pattern and stays outside `SessionHost`.

## 3. Recommended Approach

**Direct Adjustment.** Add a small story and two wording amendments.

- **Effort:** one small story. It adds roughly one reducer file, one event file and tests, and the ViewModel shrinks.
- **Risk:** low. Behaviour is unchanged, and the existing sync and gesture suites act as the regression net.
- **Timeline:** no impact. The story sits at the end of Epic 6 with no dependents.

## 4. Detailed Change Proposals

### 4.1 Story 5.2: Acceptance Criteria (`epics.md:1116-1118`)

OLD:
```
**Given** a recognized gesture
**When** the `MoveIntent` is produced
**Then** it goes through the same `MoveCommitted` path as a Move key, the input is acknowledged within 100 ms, and a light haptic tick fires (switchable off)
```

NEW:
```
**Given** a recognized gesture
**When** the `MoveIntent` is produced
**Then** it goes through the same `MoveCommitted` path as a Move key (a `SessionEvent` in a Lesson; on the free cube, the shared `FreeCubeViewModel.onMoveIntent` commit, replaced by the free-cube reducer in Story 6.6), the input is acknowledged within 100 ms, and a light haptic tick fires (switchable off)
```

Rationale: this records the PO-approved as-built path and points to the story that formalises it.

### 4.2 Epics: new Story 6.6 (after Story 6.5)

```
### Story 6.6: Give the free cube one event owner

As a Learner,
I want every way of turning the free cube to behave the same,
So that the cube I see always matches the turns I made.

**Refs:** FR-1, FR-3 · NFR-4 · AD-3, AD-4, AD-20 · AR-8

**Acceptance Criteria:**

**Given** a pure `FreeCubeEvent` (`MoveCommitted(move)`, `Undo`, `Redo`, `Reset`, `Scrambled(moves)`) and `reduceFreeCube(state, event)` in `:core:application`, where the state holds the `MoveHistory` and a monotonic `revision`
**When** an event is reduced
**Then** it returns the next state and a revision-stamped presentation: `Animate(move)` for a commit, `Animate(inverse)` for Undo, `Animate(move)` for Redo, and `Snap` for Reset and Scramble. Undo or Redo with nothing to undo or redo returns the state unchanged with no presentation. JVM tests cover Story 1.6's undo, redo, reset and redo-clearing rules

**Given** the free cube screen
**When** a gesture, a Move key or an accessibility action produces a `MoveIntent`, or the Learner taps Undo, Redo, Reset or Scramble
**Then** `FreeCubeViewModel` only dispatches a `FreeCubeEvent` and submits the returned presentation. It is not a second writer of the history, the Scramble Moves are generated outside the reducer (no `RandomSource` in `reduce`), and a test shows a gesture and the equal Move key produce identical transitions

**Given** the existing free-cube suites (the 2D/3D randomized sync suite and the Story 5.2 gesture tests)
**When** they run
**Then** they pass unchanged, and the free cube is still not a Learning Session and persists nothing (AD-20)
```

Also update the Epic 6 summary (`epics.md:208`) by appending this sentence: "The free cube also gets one event owner (Story 6.6), with no persistence."

### 4.3 Architecture: AD-4 "Commit first" (`ARCHITECTURE-SPINE.md:138`)

OLD:
```
  - **Commit first:** every input source (gesture, button, accessibility action) produces a `MoveIntent`. The ViewModel maps every `MoveIntent` to the same `MoveCommitted` event. Input during an animation is committed immediately.
```

NEW:
```
  - **Commit first:** every input source (gesture, button, accessibility action) produces a `MoveIntent`. The ViewModel maps every `MoveIntent` to the same `MoveCommitted` event: a `SessionEvent` in a Learning Session, or a `FreeCubeEvent` reduced by the pure free-cube reducer on the free cube, which stays outside the session model (AD-3, AD-20). Input during an animation is committed immediately.
```

### 4.4 Architecture: Ownership Matrix (after the "Feature flags" row, `ARCHITECTURE-SPINE.md:406`)

NEW row:
```
| Free cube `MoveHistory` | `reduceFreeCube` (dispatched by `FreeCubeViewModel`) | memory only (not persisted) |
```

## 5. Implementation Handoff

- **Scope: Moderate.**
  - The developer applies §4.1–4.4 on `docs/correct-course-free-cube-event-owner` and opens a PR to main.
  - After the merge, regenerate sprint-status so `6-6-give-the-free-cube-one-event-owner` appears as `backlog`.
- **Build:** Story 6.6 runs through `/bmad-orchestrate` like any other Epic 6 story. It has no human gate.
- **Retro bookkeeping:** this closes Epic 5 retro action item 1. Retro item 4 (split `Cube3DRenderer.kt`, one presented-frame source) stays separate.
- **Success criteria:**
  - AD-4 and the Story 5.2 AC describe the as-built free-cube path truthfully.
  - After 6.6, `FreeCubeViewModel` holds no `MoveHistory` transition logic, and JVM tests cover every free-cube transition.
  - Epic 6's persistence stories are unaffected.
