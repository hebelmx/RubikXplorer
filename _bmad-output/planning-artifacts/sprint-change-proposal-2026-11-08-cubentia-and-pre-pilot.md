---
title: 'Sprint Change Proposal: Cubentia rebrand, Epic 8 pre-pilot fixes, SCP wording reconciliation'
date: 2026-11-08
status: approved
approved_by: hebelmx (PO), 2026-11-08
trigger: PO decisions 2026-11-08 (rebrand); Epic 8 retrospective (epic-8-retro-2026-11-08.md)
scope: Moderate
mode: batch
---

# Sprint Change Proposal: Cubentia rebrand and pre-pilot fixes

## 1. Issue Summary

This proposal has three triggers, all from 2026-11-08 and all to be done **before the Story 7.8 pilot build**.

**A. Rebrand to Cubentia.**
- The PO chose **Cubentia** as the product and store name, pronounced *koo-BEN-tyah* (Spanish /kuˈben.tja/), written without an accent mark. This answers PRD **OQ-1**.
- The Play application ID becomes **`com.exxerpro.cubentia`**, matching the verified ExxerPro organization account. **An application ID can never change after the first upload**, which is Story 7.8, so this must land first.
- The PO chose a **full code rename** (`dev.rubikxplorer.*` → `dev.cubentia.*`).
- Evidence from a dry run on `2cfa628`:
  - about 3,950 occurrences in about 500 files;
  - 165 `dev/rubikxplorer/` package folders;
  - Learner-facing text with the "Rubik's" trademark: `msg.milestone.first-solve` ("You solved the Rubik's cube!") and `msg.practice.stage-07-independent-practice.objective`.
- The user's draft `rg … 'rubixplorer' | perl …` command misspells the name and matches **0 files**. The real spelling is `rubikxplorer`.

**B. Epic 8 retrospective, pre-pilot items.** The retrospective was accepted with open items. It lists items 1–4, 6, 7 and 10 as defects at story seams that per-story gates missed, and wants them fixed before the pilot. The PO turned them into stories and decided the two open contracts:
- **Item 4:** the mismatch ladder uses the recommended contract (§4.2, Story 12.8).
- **Item 7:** the Physical mode switch is hidden on the skip-ahead check only (§4.2, Story 12.9).

**C. Wording reconciliation with the vision-deferral SCP (retrospective item 12).**
- The epics still say "Epic 10" or "Epic 11" where they mean v1.1 camera recognition.
- The Epic List pointer headings `### Epic 10:` and `### Epic 11:` match the sprint generator's `EPIC_RE` (`^#{1,3}\s*Epic\s+(\d+)`), so every `sprint_plan.py generate` re-adds empty `epic-10`/`epic-11` keys (seen in the 8.7 run).

## 2. Impact Analysis

| Area | Impact |
|---|---|
| Epics 1–8 | None (Epic 8 is closed; its follow-ups land as new Epic 12 stories). |
| Epic 7 (7.8 pilot) | **Sequenced after** 12.6–12.10. No AC change; the build simply carries the new application ID and name. |
| Epic 9, 3.x leftovers | None; they run after the new stories. |
| Epic 12 | **Five new stories, 12.6–12.10**, run before 7.8. Story 12.5 adds the repo and folder rename. |
| PRD | OQ-1 resolved (name chosen; trademark search still due before publishing), the codename line, and §10. Edited through `bmad-prd`. |
| Architecture spine | **No change.** It never names the Kotlin package root, and the codename in its title, folder and seed tree is historical. 12.8's contract lands in `TRANSITIONS.md`, a code-side document, within AD-3 and AD-5. |
| UX | No change. Codename references in the spines are historical, and the copy changes are content keys (NFR-10). |
| sprint-status | Add 12.6–12.10 as `backlog`. Removing the pointer headings drops the `epic-10`/`epic-11` keys. |
| AGENTS.md | After 12.6, run a `bmad-project-context` refresh: paths change (`build-logic/src/main/kotlin/dev/rubikxplorer/…`) and so does the title. |
| Not changed | `_bmad/`, `.claude/skills/`, historical `_bmad-output` records (SCPs, retros, memlogs, story specs), git history, planning folder names (`prd-RubikXplorer-…`). |

## 3. Recommended Approach

**Direct Adjustment.** Add stories inside the existing epic structure. No rollback is needed, and the MVP scope doesn't change.

- **Why Epic 12:** its purpose is release readiness, and every new story is a release-readiness item. Numbering them 12.6+ keeps every epic stable.
- **Why this order:**
  - **12.6 first**, because the rename touches nearly every file, so nothing should be in flight, and because the application ID must be fixed before 7.8.
  - **12.7 then 12.8**, because both touch `Reduce.kt` and `SessionHost`, so they run one after the other.
  - **12.9 and 12.10** are independent.
- **Effort:** about 3–5 working days for 12.6–12.10. 12.6 is mechanical but large, 12.8 is the heaviest logic change, and the rest are small.
- **Risk:** medium for 12.6 (a big diff, but verified by the full gate and CI), medium for 12.8, low for the rest.
- **Timeline:** v1.0 grows from 17 to 22 stories. The target moves by a few days, to about early December 2026.

## 4. Detailed Change Proposals

### 4.1 Epics (`epics.md`)

**Epic List: stop the generator re-adding deferred epics (retrospective item 12)**
```
OLD: ### Epic 10: Scan my cube — deferred to v1.1
     Moved to `post-launch/v1.1-camera-recognition.md` (...)
     ### Epic 11: Scan shortcuts: Quick Scan, Placement and Case recognition — deferred to v1.1
     Moved to ...
NEW: **Deferred to v1.1 — Epic 10 (Scan my cube):** moved to `post-launch/v1.1-camera-recognition.md` (...)   [same text, bold line, not a heading]
     **Deferred to v1.1 — Epic 11 (Scan shortcuts: Quick Scan, Placement and Case recognition):** moved to ...  [same]
```

**Wording: "Epic 10/11" becomes "v1.1 camera recognition"**
| Where | OLD | NEW |
|---|---|---|
| Epic 8 intro | "The camera rungs are added in Epic 10." | "The camera rungs arrive with v1.1 camera recognition (`post-launch/v1.1-camera-recognition.md`)." |
| 8.2 AC | "(*Scan it* hidden until Epic 10)" | "(*Scan it* hidden until v1.1 camera recognition)" |
| 8.6 AC | "(or a Quick Check, once Epic 10 lands)" | "(or a Quick Check, once v1.1 camera recognition lands)" |
| 3.7 AC | "*Scan my cube* only once Epic 10 lands" | "*Scan my cube* only once v1.1 camera recognition lands" |
| 7.x Welcome AC | "hidden until Epic 11 enables `PLACEMENT`" | "hidden until v1.1 enables `PLACEMENT`" |

**Epic List, Epic 12 entry:** add a line: *"Also carries the pre-pilot stories 12.6–12.10 (Cubentia rebrand; Epic 8 retrospective fixes), run before Story 7.8 (SCP 2026-11-08-cubentia-and-pre-pilot)."*

**Story 12.5, AC 2:** add *"…and the GitHub repository and local working folder are renamed to Cubentia (deferred here from 12.6)"*.

**New stories, inserted after 12.5:**

```
### Story 12.6: Rebrand to Cubentia

As the developer,
I want the app, its application ID and its code named Cubentia before the first Play upload,
So that the store identity is permanent and free of the "Rubik's" trademark.

Refs: PRD OQ-1, §7 store readiness · NFR-8, NFR-10 · AD-13, AD-15 · this SCP

AC 1 (risk analysis first): Given no rename has been applied, when the story starts, then a written
risk analysis (in the spec) covers at least: application-ID permanence after first upload; Room DB file
rename (no installed users yet; developer devices lose local data); package and namespace rename effects
(R class, Hilt/KSP/Room generated code, Room schema export paths, R8/ProGuard keep rules, Macrobenchmark
target package, `--tests` filters in CI and docs); the excluded paths; and rollback (one revertable range).

AC 2 (dry run): Given the corrected pattern `rubikxplorer` (case-insensitive: rubikxplorer, RubikXplorer,
RUBIKXPLORER), when the rename script runs in dry-run mode, then it lists every file whose contents change
and every directory to `git mv`, with these exclusions applied: `_bmad/`, `.claude/skills/`, `.git/`,
build outputs, and historical `_bmad-output` records (`sprint-change-proposal-*`, `*retro*`, `.memlog.md`,
`spec-*`, planning folder names); the listing is recorded in the spec before any write.

AC 3 (rename): Given the dry run, when the rename runs, then: `applicationId = "com.exxerpro.cubentia"`;
packages and folders become `dev.cubentia.*` (`git mv`, history preserved); Android namespaces become
`dev.cubentia.app` and `dev.cubentia.macrobenchmark`; `rootProject.name = "Cubentia"`; `app_name` is
"Cubentia"; the DB file is `cubentia.db`; `RubikXplorer*` identifiers become `Cubentia*`; and README, docs,
`.github/` and `Fixtures/` text say Cubentia. After the rename, `rg -i rubikxplorer` matches only excluded
paths. Other `Rubik*` code identifiers (`RubikTheme`, `RubikDatabase`, …) stay and are listed in the spec
for a 12.5 decision.

AC 4 (trademark-free copy): Given all Learner-facing text (content strings and `strings.xml`), when content
tests run, then no string contains "Rubik" (a test enforces it), and the two current strings read
"…the cube…".

AC 5 (gates): Given the renamed tree, when `./gradlew spotlessCheck build`, `./gradlew -p build-logic test`
and `./gradlew compileDebugAndroidTestKotlin` run, then all pass, CI is green, and the merged release
manifest's package is `com.exxerpro.cubentia`.

### Story 12.7: Physical progress is recorded only when true

As a Learner on a real cube,
I want my progress, undo and resume to reflect what I actually confirmed,
So that the app never records or restores something my cube didn't do.

Refs: FR-3, FR-7, FR-8, FR-19 · NFR-4, NFR-6 · AD-3, AD-10 · UX-DR20 · Epic 8 retro items 1–3

AC 1: Given Stage 6 in Physical-cube Mode with the Objective met, when the Learner has not tapped
*Yes, it's solved*, then no `LessonCompleted` is written. A `PhysicalSolveConfirmed` SessionEvent
(through reduce) gates `completeLesson` in physical sessions; the confirmed and unconfirmed flags live
in `SessionState` and the snapshot, not `rememberSaveable`; the `pendingPhysicalSolve` workaround is
removed; and the `"lesson-06-03"` literals become one source. Tests cover re-entry and process death
before and after confirming.

AC 2: Given physical mode, when the Learner taps Undo, then the Instruct text ("Turn {move} on your cube
to undo.") is shown and announced in a polite live region instead of being mapped to an animation, and
Redo is disabled in physical mode (or emits its own Instruct).

AC 3: Given a mode switch to physical, when it is reduced, then a physical Checkpoint is persisted
without moving the recovery boundary, and a `SessionHostCheckpointTest` covers switch → `SolvePlaybackStarted`
→ `endSolve` → `PHYSICAL_AWAITING_CONFIRM`.

### Story 12.8: The mismatch ladder resolves a mismatch

As a Learner whose cube doesn't match,
I want the answers I give the tutor to actually fix the tracked state,
So that I can continue instead of being stuck.

Refs: FR-9, FR-10, FR-12 · AD-3, AD-5 · Epic 8 retro item 4 · PO contract decision 2026-11-08

AC 1: Given `PHYSICAL_MISMATCH`, when the Learner enters the move they made (rung 1), then it is committed
as their real move: Expected updates, the state returns to `PHYSICAL_PREDICTED`, and Certainty is the
diagnosed one (never a false VERIFIED).
AC 2: Given `PHYSICAL_MISMATCH`, when the Learner taps *It matches after all*, then the state returns to
`PHYSICAL_PREDICTED` with Expected unchanged.
AC 3: Given rung 2 (one-face question), when answered, then it guides to re-entry (rung 3); no
`PhysicalObservation.Partial` is produced in v1.0 (partial observations arrive with v1.1 camera).
AC 4: Given a full state entered in the Manual Cube Editor from physical mode, when accepted, then the
state is `PHYSICAL_VERIFIED`, and an offered Guided recovery survives the physical confirms that follow.
AC 5: `TRANSITIONS.md` matches the reducer for every case above, and each case has a reducer test.

### Story 12.9: One source for physical copy; skip-check needs the virtual cube

As a Learner,
I want consistent physical-mode wording, and a skip-ahead check that proves I can do it,
So that the app speaks with one voice and never skips a Stage on my word alone.

Refs: FR-4, NFR-10 · AD-6 · UX-DR24 · Epic 8 retro items 6, 7 · PO decision 2026-11-08

AC 1: Given the mismatch ladder and other physical-mode copy, when rendered, then text resolves from
`en.json` content keys (the existing `msg.physical.mismatch.*`), and the unreferenced `strings.xml`
duplicates are deleted (advances epic-2-retro-item-3).
AC 2: Given the skip-ahead check, when it is open, then the Physical mode switch is hidden, so the check
can be passed only on the virtual cube. Independent practice keeps Physical mode, and its self-reported
nature is recorded as an accepted v1.0 limitation.

### Story 12.10: Prove the release-permission gate end to end

As the developer,
I want the build to prove that a release can never ship a runtime permission,
So that the camera-free v1.0 promise is enforced, not assumed.

Refs: NFR-8 · AD-13, AD-14 · Epic 8 retro item 10 · epic-2-retro-item-4

AC 1: A Gradle TestKit test shows `checkReleaseMergedManifest` failing on a manifest that declares
`CAMERA`, and passing the debug variant (shares setup with epic-2-retro-item-4).
AC 2: An `AppShell`-level test shows `CAMERA_SCAN` at BETA reaching `LessonScreen` (the camera wording appears).
AC 3: The policy keys on `variant.debuggable` (or fails on a null build type), so an unknown build type
cannot bypass it.
```

### 4.2 PRD (`prd.md`, through `bmad-prd` update)
```
Line 9  OLD: *Working title / codename. The store name is chosen before publishing, after a trademark search (OQ-1).*
        NEW: *Store name: **Cubentia** (koo-BEN-tyah), chosen 2026-11-08 (OQ-1). RubikXplorer remains the codename in planning documents.*
OQ-1    OLD: 1. **OQ-1:** official store name and icon. Chosen after a trademark search, before publishing. "Rubik's" is … RubikXplorer remains the codename.
        NEW: 1. **OQ-1 (name resolved 2026-11-08):** the store name is **Cubentia**; the application ID is `com.exxerpro.cubentia`. Still open before publishing (Story 12.5): a trademark search for "Cubentia", and the icon. "Rubik's" must not appear in the listing name, icon or Learner-facing text.
§10     NEW: - Product name Cubentia and application ID com.exxerpro.cubentia (PO, 2026-11-08).
```

### 4.3 sprint-status.yaml
- Add `12-6-rebrand-to-cubentia`, `12-7-physical-progress-is-recorded-only-when-true`, `12-8-the-mismatch-ladder-resolves-a-mismatch`, `12-9-one-source-for-physical-copy-skip-check-needs-the-virtual-cube` and `12-10-prove-the-release-permission-gate-end-to-end` as `backlog`, through `sprint_plan.py generate`.
- Removing the pointer headings drops the `epic-10`/`epic-11` keys. Verify with `validate` and confirm no `epic-10`/`epic-11` keys remain.
- Mark Epic 8 retrospective items 1–4, 6, 7, 10 and 12 as `in-progress`, with a pointer to their story or to this SCP.

## 5. Implementation Handoff

**Scope: Moderate.** This is a backlog reorganization with two PO contract decisions already made.

| Who | Does | Done when |
|---|---|---|
| This workflow | Updates epics.md (§4.1) and sprint-status (§4.3), on branch `docs/scp-2026-11-08-cubentia-pre-pilot`, then a PR. | Merged; `validate` ok; no `epic-10`/`epic-11` keys after `generate`. |
| PM (`bmad-prd` update) | Applies §4.2. | OQ-1 shows Cubentia; trademark search still open. |
| Developer (`/bmad-orchestrate`) | Story 12.6 first, then 12.7 → 12.8 → 12.9 → 12.10, named explicitly (the recommender points at 1.11). | Each merged with gates and CI green. |
| After 12.6 | `bmad-project-context` refresh (paths and title change). | AGENTS.md verified against the post-rename SHA. |
| PO (hebelmx) | Trademark search for "Cubentia" and the icon, before 12.5. | Recorded in the PRD and memlog. |

**Success criteria:**
- The 7.8 build uploads as `com.exxerpro.cubentia`, named Cubentia.
- No Learner-facing "Rubik" text remains, and a test enforces that.
- Epic 8 retrospective items 1–4, 6, 7 and 10 are closed by merged stories.
- `sprint generate` no longer re-adds the deferred epics.

## Checklist record

| Item | Status | Note |
|---|---|---|
| 1.1–1.3 Trigger, problem, evidence | [x] | PO decisions, Epic 8 retrospective, dry-run counts, generator regex |
| 2.1 Current epic | [x] | Epic 8 closed; follow-ups go to Epic 12 |
| 2.2–2.5 Epic changes | [x] | Epic 12 +5 stories; order: 12.6 → 12.10, then Epic 9, leftovers, 7.8, 12.1–12.5 |
| 3.1 PRD | [x] | OQ-1 updated (bmad-prd fast path) |
| 3.2 Architecture | [N/A] | Package root not in the spine; the ladder contract lives in TRANSITIONS.md under AD-3/AD-5 |
| 3.3 UX | [N/A] | Copy changes are content keys |
| 3.4 Other | [!] | AGENTS.md refresh after 12.6 |
| 4.1 Direct Adjustment | Viable | Selected |
| 4.2 Rollback | Not needed | — |
| 4.3 MVP Review | Not needed | Scope unchanged |
| 4.4 Path | [x] | Direct Adjustment |
| 5.x, 6.1–6.2 | [x] | — |
| 6.3 Approval | [x] | Approved by PO 2026-11-08 |
| 6.4 sprint-status | [x] | 12.6–12.10 added; epic-10/11 orphans dropped; retro items updated |
| 6.5 Handoff | [x] | Section 5 |
