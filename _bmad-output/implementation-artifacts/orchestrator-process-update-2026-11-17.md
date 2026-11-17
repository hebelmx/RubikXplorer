---
title: Orchestrator process update (group D)
date: 2026-11-17
status: done
plan: _bmad-output/implementation-artifacts/plan-remaining-work-2026-11-17.md (phase 2)
---

# Orchestrator process update (group D)

Phase 2 of the remaining-work plan. The orchestrator skills live outside this repository, at the user level (`~/.claude/skills/bmad-orchestrate`, `~/.claude/skills/bmad-orchestrate-issues`). They aren't versioned, so this file is the durable record of what changed, and it serves as the closing evidence for the action items below. The pre-change copy is a local backup, not shared.

## Rules now in the orchestrator

| Rule | Where | Closes |
|---|---|---|
| **Deferral routing at story close.** On the `story done` commit, the orchestrator appends to `action_items` every spec `deferred[]` entry, every untracked residual risk, and every gate finding left after the last fix round (medium always, low when Learner-visible). Ids are `epic-N-deferred-{id}-<slug>`, `status: open`, with `ref` = the spec. It skips items already covered and runs `sprint_plan.py validate` afterwards. The issue orchestrator does the same, with `issue-{N}-deferred-<slug>` ids. | `story-cycle.md` G.3; `bmad-orchestrate-issues/references/issue-cycle.md` build step 6 | epic-2-retro-item-1, epic-4-retro-item-13, epic-9-retro-item-47 |
| **Spec and sprint status close together.** The same `story done` commit sets the spec's frontmatter `status: done`. | `story-cycle.md` G.3 | epic-1-retro-item-57 |
| **Epic-boundary cross-story review.** Before an epic's last story merges, the orchestrator runs a read-only review over the whole epic diff. It looks for drifted contracts between stories, duplicated or unowned responsibilities, earlier ACs that a later story undid, untested cross-story flows, and leftovers. A high finding stops the run. | `story-cycle.md` D.5; `worker-prompts.md` § Cross-story review | epic-8-retro-item-38, epic-9-retro-item-48 (duplicate) |
| **Interim retrospective.** When an epic's remaining stories are all gated before start, or depend on another epic, and at least two stories are done, the orchestrator offers a retrospective over the done stories. The epic stays open. | `epic-close.md` § Interim retrospective; `SKILL.md` loop step 2 | epic-7-retro-item-70 |
| **Binding AD ids verbatim.** The build intent requires copying the Refs line's AD ids into the spec. A known AD deviation is a blocker. The gate grades an AD deviation `high` even when the spec lists it as a residual risk. | `worker-prompts.md` build worker and acceptance gate | epic-2-retro-item-5 |
| **Specs settle UI behavior choices.** The build intent requires animation, auto-play, clearing and state-dependent copy to be settled in the spec before implementation. If the story and UX spec don't settle a choice, the build HALTs with a question instead of choosing. | `worker-prompts.md` build worker | epic-4-retro-item-14 |
| **Pre-gate scope check.** If a spec excludes, or "fixes by design", something an AC names, the run stops for a recorded PO decision. The gate receives the recorded decisions, and treats an excluded AC without one as unmet. | `story-cycle.md` B.6; `worker-prompts.md` acceptance gate | epic-5-retro-item-22 |
| **Story id in every commit subject.** This covers the build worker, review fixes, both fix-worker variants and orchestrator-made commits of Codex, agy and device-session fixes. The orchestrator inserts `({id})` if a returned subject lacks it. | `worker-prompts.md` build and fix workers | epic-5-retro-item-20 |

## Repository changes in the same PR

- **epic-3-retro-item-64:** the four stranded Epic 3 deferrals are now action items:
  - `epic-3-deferred-3-5-outline-ring-pixel-check`
  - `epic-3-deferred-3-6-ux-dr14-move-strip`
  - `epic-3-deferred-3-7-editor-drafts-lost-on-tab-switch` (medium, Learner-visible; plan phase 4)
  - `epic-3-deferred-3-7-preparing-copy-on-cold-cache-read` (check it against the closed #112 first)
- **epic-1-retro-item-57:** `spec-1-9` status is now `done`, up from `built`.
- All twelve group D items are set to `done`.
