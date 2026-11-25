---
title: 'Sprint Change Proposal: retire "v1.1"; every item is v1.0 scope'
date: 2026-11-25
status: approved
mode: batch
trigger: 'GitHub issue #104 (Story 3.9). PO 2026-11-25: there is no v1.1 release any more; the features were pulled back into v1.0'
scope: Minor
---

# Sprint Change Proposal: retire "v1.1"

- **Trigger:** `epics.md:969` still says Story 3.9 (#104) is "v1.1 scope", and so do about 70 other lines across the PRD, epics, architecture, UX spec, sprint status and docs.
- **Scope (PO, 2026-11-25):** all v1.1 wording, not only #104.
- **Classification:** Minor. These are release-label edits. No acceptance criterion or NFR changes behaviour, threshold or flag condition. Where a sentence's release label was its only meaning (NFR-5, PRD §7 line 693), the new text is given word for word in §4.
- **Route for final documents:** AGENTS.md allows `prd.md` and `ARCHITECTURE-SPINE.md` to change through `bmad-correct-course`, so this SCP is that route. Memlogs are only appended, through `memlog.py` (§5).
- **Adversarial review (2026-11-25):** 15 findings. Every high and medium one is applied below. Of the lows, the action-item note, the #107 status, the frontmatter and the branch name are applied too.

## 1. Issue summary

The release plan has collapsed into v1.0 in several steps:
- SCP 2026-11-20 moved FR-21 to FR-27 and the camera stories into v1.0.
- SCP 2026-11-24 camera-release-timeline put Guided Scan (GA) and Quick Scan (BETA, conditional) in 0.11.0.
- From 2026-11-23 the PO pulled the issues labelled v1.1 back into v1.0.

The documents were only partly updated. A read-only sweep at `2088c43f` found 113 mentions: 38 already v1.0 by a later SCP (class A), 44 dated history (class B) and 31 whose release was still open (class C), which the PO decided in §3. #105 closed on 2026-11-25 with evidence (PR #373: NFR-3 met on the Lenovo, S24 FE and host; release k ≈ 7.7).

## 2. Impact analysis

- **Epics:** Epic 3 (Story 3.9), Epic 10 (heading, FR coverage, Story 10.29) and Epic 11 (heading, Case recognition). No story is added, removed or re-sequenced.
- **Epic 3 status:** SCP 2026-11-24 github-issue-triage said Story 3.9 reopens Epic 3 (`epic-3` back to in-progress), but `sprint-status.yaml` still has `epic-3: done` beside `3-9-…: backlog`. This SCP applies that line: `epic-3: in-progress`. 3.9 is a v1.0 story that doesn't gate the tag, but it is still open work.
- **PRD:** release tags on UJ-1, UJ-3, UJ-4 and UJ-5, NFR-5 and NFR-11, §6.2 heading, PRD §7 line 693 and the gate 6 tag. A §10 decision-log bullet records the change.
- **Architecture:** `ARCHITECTURE-SPINE.md` frontmatter (line 7), AD-9 `Status` (line 247), FR mapping rows (561–562) and the Deferred section (571–572). No AD content changes.
- **UX:** `EXPERIENCE.md` IA rows and flow tags. No flow or copy changes.
- **Docs:** `docs/benchmarks.md`, `docs/pruning-tables.md`, `docs/accessibility-manual-check.md`. **`docs/release-gates-v1.0.md` is not edited:** it is Story 12.4's dated record of the camera-OFF builds (2026-11-24). Its gate 6 and SM-8 "N/A" lines are replaced by the re-run on the camera build, which is owned by action item `scp-2026-11-24-release-gates-on-camera-build` and Story 10.11/12.5.
- **New release obligation:** the camera-flow accessibility and performance passes (Key Flows 4, 5 and 9 steps 1–3, marked out of the v1.0 pass in `accessibility-manual-check.md:21` and `benchmarks.md:338, 752`) now belong to v1.0. They get an owner: a new action item (§4).
- **Out of scope and left as written (class B):** memlogs; dated `*(SCP …)*` annotations; "Earlier text:" blocks; decision logs; `post-launch/v1.1-camera-recognition.md`; story spec files and `epic-*-context.md` (implementation records); code comments (`FeatureFlagLevels.kt:33`, `KeyFlowRotationTest.kt:25`). Retiring those code comments is a separate code change; I'll draft an issue for it.
- **Labels:** the `v1.1` label stays on closed #326 and #194 as history and is not deleted.

## 3. Decisions (PO, 2026-11-25)

| Item | Decision |
| --- | --- |
| Story 3.9 / #104 cold solver preparation | **v1.0 scope; doesn't gate the v1.0.0 tag.** Worked when there's room; if it doesn't fit, re-triaged with a reason. The ~10.5 s release-equivalent preparation stays accepted until 3.9 records a PO target. |
| FR-28 Case recognition (UJ-5, Flow 5, Story 11.3, `CASE_RECOGNITION`) | **v1.0, flag-gated**, like FR-21 to FR-27. Story 10.11 sets its level at each release from evidence, and OFF is a valid level. Not a tag gate. |
| Story 10.29 / #326 | **Done** (#326 closed). Its deferred TalkBack / 200% device check stays an open action item. It is **not** a v1.0.0 tag gate (PO: "v1.0, no gate"). |
| #106 (2D net NFR-2 baseline), #173 (multiple calibrations) | **v1.0 scope; not tag gates.** Same rule as 3.9. |
| SM-8, camera-flow a11y and performance passes | Apply **from the first release that raises `CAMERA_SCAN` (0.11.0)**. SM-8 is already owned by `scp-2026-11-24-release-gates-on-camera-build`. The a11y and performance passes get a new action item. |
| Quick Scan (FR-22, `QUICK_SCAN`) | v1.0 scope (SCP 2026-11-20). Flag level per Story 10.11: BETA only when its preconditions hold. Wording only. |

## 4. Detailed change proposals

**Rules** (applied to class A and C lines only):

1. Camera features (FR-21 to FR-28; Epics 10 and 11; UX scan flows):
   - "v1.1" or "v1.1, flag-gated" → "v1.0, flag-gated".
   - "arrives in v1.1" or "once v1.1 lands" → "when its flag is raised (Story 10.11)".
2. Camera-free wording:
   - "*v1.0 (no camera):*" → "*While `CAMERA_SCAN` is OFF:*".
   - "v1.0 … v1.1 adds X" → "… X is added when its flag is raised".
   - "in v1.0" meaning camera-free (`epics.md:3415`) → "while every camera flag is OFF".
3. Non-camera deferrals (#104/3.9, #106, #173): "v1.1 scope" or "deferred to v1.1" → "v1.0 scope; doesn't gate the v1.0.0 tag". A waiver "accepted for v1.0" keeps that wording and its reason.
4. Dated annotations are never rewritten. Each edited planning section gets one new annotation after any existing ones: `*(SCP 2026-11-25 retire-v1-1: v1.1 is retired; v1.0 scope.)*`. For `epics.md:201` and `:969`, it also says it supersedes the 2026-11-24 "v1.1 scope" wording.

**Requirement and AC lines touched** (label only; behaviour unchanged):

- `prd.md:597` NFR-5. Old: "…(and scan it, from v1.1). v1.0 declares no runtime permissions. From v1.1, the camera is the only one, and a release declares it only when a camera feature's flag is above OFF." New: "…(and scan it, when a camera flag is raised). A release with every camera flag OFF (0.9.x–0.10.x) declares no runtime permissions; a release that raises a camera feature's flag above OFF declares the camera, and only the camera."
- `prd.md:603` NFR-11: "(and, from v1.1, any recognition model)" → "(and any recognition model a camera feature ships)".
- `prd.md:693` PRD §7: "(scanning arrives in v1.1)" → "(and scan it, where a release raises `CAMERA_SCAN`)".
- `prd.md:701` gate 6: "*(v1.1)*" → "*(any release that raises a camera flag)*". The rest of the line is unchanged; it already applies gate 6 to v1.0.
- `epics.md:909, 1531, 1607, 1687, 3415` (ACs): rules 1 and 2, label only.

**Key edits** (old → new):

- `epics.md:969`: "**v1.1 scope** (#104 stays a later-release item; it never gates the v1.0 release)." → "**v1.0 scope; does not gate the v1.0.0 tag**". Add the new annotation.
- `epics.md:201`: kept as written, with a retire-v1-1 annotation appended: "Story 3.9 is v1.0 scope; it does not gate the v1.0.0 tag; Epic 3 is in-progress until 3.9 is done or re-triaged."
- `epics.md:2863` (Story 10.29): "v1.1 scope (PO 2026-11-24: stays a later-release story, as labelled on #326)." → "Done in v1.0; its deferred device check is an open action item, not a v1.0.0 tag gate."
- `epics.md:233, 240` (Epic 10 and 11 headings) and `:160–179` (FR coverage): "(v1.1, flag-gated)" → "(v1.0, flag-gated)".
- `prd.md:630` §6.2: "**v1.1 (in progress, flag-gated): Camera Cube Recognition** (FR-21 – FR-28)" → "**v1.0, flag-gated: Camera Cube Recognition** (FR-21 – FR-28)". UJ-4 and UJ-5 at `:95` and `:104`: "*(v1.1)*" → "*(v1.0, flag-gated)*". Plus `:67, 68, 93, 112` by rules 1 and 2.
- `prd.md` §10: append "2026-11-25: v1.1 is retired; every item is v1.0 scope (SCP 2026-11-25 retire-v1-1)."
- `ARCHITECTURE-SPINE.md:7, 247, 561, 562, 572`: "v1.1" → "v1.0, flag-gated". `:571` #106: "deferred to v1.1" → "v1.0 scope; not a tag gate".
- `EXPERIENCE.md:35, 37, 42, 45, 76, 116, 255, 264, 271, 287, 324, 365` by rules 1 and 2. `:255` becomes "Quick Scan and the Quick Check in playback appear when their flags are raised (Story 10.11)."
- `docs/benchmarks.md:115–116`: #105 → "closed 2026-11-25, see the #105 section". `:338, 752`: camera flows → "from 0.11.0 (action item retire-v1-1-camera-flow-passes)". `:702, 708, 751`: "#104 (v1.1)" → "#104 (Story 3.9, v1.0 scope, not a tag gate)". `:320, 326, 713`: #105 references → closed 2026-11-25.
- `docs/pruning-tables.md:168`: "### Known issues (v1.1)" → "### Known issues". `:170–171`: keep "accepted or waived by the PO for v1.0"; "deferred to v1.1" → "#104 stays v1.0 scope (Story 3.9, not a tag gate); #105 and #107 are closed". `:180`: "The speed-up itself stays #104 (v1.1)." → "…stays #104 (Story 3.9)." `:121`: "the v1.1 recognition model" → "a camera recognition model (NFR-11)".
- `docs/accessibility-manual-check.md:21`: "…require v1.1 camera functionality and are outside this v1.0 pass." → "…need the camera features; they are checked on the first release that raises `CAMERA_SCAN` (0.11.0; action item retire-v1-1-camera-flow-passes)."

**Sprint status** (with `sprint_plan.py generate --set` and `action_items` appends):

- `epic-3: in-progress`.
- Append action item `retire-v1-1-camera-flow-passes` (epic 12; owner Dev and PO; open): "Run the accessibility manual check and the performance pass on Key Flows 4, 5 and 9 steps 1–3 on the 0.11.0 build (Lenovo and S24 FE), before Story 12.5 promotes v1.0.0; record them beside gate 5 in the camera-build re-run of docs/release-gates-v1.0.md."
- Append to `epic-10-deferred-10.29-experience-md-shared-card`'s action text: "(line 255 part done by SCP 2026-11-25 retire-v1-1; the shared-card description remains)". Its status is unchanged.

**GitHub:** a comment linking this SCP on #104, #106 and #173 (open). No label changes and no closes.

## 5. Implementation handoff

- **Minor.** The Dev applies §4 on branch `docs/scp-2026-11-25-retire-v1-1` in one PR, after approval.
- **Memlog appends** (`uv run _bmad/scripts/memlog.py append`): one decision line each in the PRD, architecture and UX memlogs: "2026-11-25 SCP retire-v1-1: v1.1 is retired; FR-21 to FR-28 are v1.0, flag-gated; #104, #106 and #173 are v1.0 scope, not tag gates."
- **Success criteria (runnable):**
  - `rg -n "v1\.1" _bmad-output/planning-artifacts/prds _bmad-output/planning-artifacts/epics.md _bmad-output/planning-artifacts/architecture _bmad-output/planning-artifacts/ux-designs docs/benchmarks.md docs/pruning-tables.md docs/accessibility-manual-check.md` returns only class-B lines: memlogs, dated annotations, "Earlier text:", decision logs, and this SCP's own annotations naming "v1.1".
  - `python3 .claude/skills/bmad-sprint-planning/scripts/sprint_plan.py validate --status-file _bmad-output/implementation-artifacts/sprint-status.yaml` passes.
  - CI is green.
  - The PR body lists every AC and NFR line touched, old and new.
- **#104 stays open.** It closes when Story 3.9 meets a PO target, or is re-triaged with a reason.
