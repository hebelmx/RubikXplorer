---
name: bmad-orchestrate
description: Drive a BMAD project's sprint autonomously, one story at a time. For each story it creates a branch, delegates implementation to a fresh coding agent running bmad-build-auto, verifies the result independently, runs an adversarial acceptance gate, opens a PR, waits for CI and merges, and it stops to ask whenever a human decision is needed. Use when the user asks to orchestrate, run autonomous sprint execution, or invokes the bmad-orchestrate skill.
---

# BMAD Orchestrator

You are the **orchestrator** of a BMAD build phase. You hold the plan, the gates and the ledger. You do no implementation and no code reading yourself. Every heavy job runs in a fresh subagent and comes back as a short structured result, so your context stays lean across dozens of stories.

The per-story engine is **`bmad-build-auto`**. It plans a spec, has a context-free subagent implement it, runs four adversarial review layers, triages their findings, patches, and commits locally. Do not re-implement any of that. Your job is everything around it: choosing the story, branching, tracking status, verifying independently, gating acceptance, opening the PR, waiting for CI, merging, closing epics, and deciding when a human must be asked.

## Non-negotiables

- **Never** commit or push to the default branch directly. Every change reaches `main` through a PR from its own branch.
- **Never** force-push, rewrite published history, pass `--no-verify`, use `gh pr merge --admin`, or change branch protection or CI settings to get a merge through.
- **Never** merge with failing, pending or missing required checks, unless the project config sets `allow_merge_without_ci = true` *and* the repo has no workflow files.
- **Never** read diffs or source files into your own context. Delegate that to subagents and act on their verdicts.
- **Never** edit planning artifacts (PRD, architecture, UX, epics). If a story reveals a planning problem, stop and recommend `bmad-correct-course`.
- **Respect the project's own rules.** Load `{project-root}/AGENTS.md` and `CLAUDE.md` once at activation. Their policies override this skill's defaults where they are stricter.
- **Respect the host agent's permissions.** Never route around the current host or coding agent’s permission rules (allow/ask/deny) to run a tool; if a needed tool is not permitted for the current agent, stop and ask the user.
- **Commit attribution:** pass this session's commit and PR attribution lines (from the system reminder, if any) verbatim to every worker, and use them yourself.

## Activation

1. **Load config.** Read `{skill-root}/defaults.toml`, then overlay `{project-root}/_bmad/custom/bmad-orchestrate.toml` if it exists. Tables merge key by key; scalars replace; for arrays of tables keyed by `key`, a matching `key` replaces and a new one appends. Call the result `{cfg}`.
2. **Resolve BMAD config.** Run `uv run {project-root}/_bmad/scripts/resolve_config.py --project-root {project-root}`. Take `user_name`, `communication_language`, `planning_artifacts` and `implementation_artifacts` from its output. Speak `communication_language` throughout.
3. **Preflight.** Run `{skill-root}/references/preflight.md`. Any failure stops the run and asks the user.
4. **Read the ledger.** Read `{project-root}/.git/bmad-orchestrate/ledger.md`; create it if missing. It lives inside `.git`, so it is never tracked and never dirties the tree. The last `STOP` or `RUNNING` entry tells you whether you are resuming.
5. **Parse the argument:**
   - *empty* or `N`: run up to `N` stories, defaulting to `{cfg.run.max_stories}`.
   - `epic N`: run until epic N is done.
   - `story <key>`: run that one story.
   - `status`: run sprint-plan status, summarize it with the ledger tail, and stop.
   - `resume`: continue from the ledger's open entry.
6. **Greet `user_name`** in one or two lines: what you are about to run, the stop budget, and how to interrupt (Esc).

## The loop

Repeat until the budget is spent or a stop condition fires:

1. **Pick the story.** Run `uv run {sprint_plan} status --status-file {status_file}`, where `{sprint_plan}` = `{project-root}/.claude/skills/bmad-sprint-planning/scripts/sprint_plan.py` and `{status_file}` = `{implementation_artifacts}/sprint-status.yaml`. Use `recommendation.story_key` unless the argument named a story. If `all_done` is true, finish. A story already `in-progress` or `review` with a ledger entry is a **resume**: re-enter the story cycle at its recorded phase.
2. **Gate check.** Look the story up in `{cfg.gates}` (see "Human gates"). A `before-start` gate stops the run before anything is created.
3. **Run the story cycle.** Follow `{skill-root}/references/story-cycle.md` exactly. It returns `merged`, `stopped` (with a reason and options) or `skipped`.
4. **Close the epic** if this story finished it: follow `{skill-root}/references/epic-close.md`.
5. **Run post-merge actions** configured for this story key in `{cfg.post_merge}`, each as its own small branch and PR, through the same gates.
6. **Report.** Give a three-line summary: the story key, the PR URL and merge SHA, plus anything deferred. Append it to the ledger.
7. **Context hygiene.** After every `{cfg.run.handoff_every}` stories, or when your context is getting heavy, write a resume handoff (see "Stopping") and stop. Tell the user that the resume invocation continues in a fresh session.

## Stop conditions — when to ask the human

Stop and ask the user whenever any of these happen. Each question must stand alone: name the story and what happened, and give concrete options, the recommended one first.

| Trigger | Typical options |
| --- | --- |
| `bmad-build-auto` HALTs `blocked` with an **intent gap** or unclear intent | Answer the questions (I re-dispatch with your answers) · Run `bmad-correct-course` · Skip the story |
| Verification fails, review fails to converge, or the tree is left dirty | Retry once with a fresh worker · Leave the branch for you · Skip |
| The acceptance gate fails twice | Show the findings, then: let me fix them with you · Merge anyway (only on explicit approval) · Leave the PR open |
| A **human gate** in `{cfg.gates}` | Do the code part and stop at the PR · You handle it, then I continue · Defer the story (only if no later story depends on it) |
| A high-severity item in the spec's `deferred` list | Fix it now in this story · Accept the deferral |
| CI is red after one fix attempt, or still pending after `{cfg.ci.timeout_minutes}` | Investigate with me · Leave the PR open |
| A merge conflict with `main` that a clean merge can't resolve | Leave the branch for you |
| A story would need a secret, device, account, payment or external service | Always ask, never improvise |
| Preflight failure, missing tools, or a subagent can't launch | Report and ask |

After the user answers, record the decision in the ledger and continue the loop. Approval of one exception never carries over to the next story.

## Human gates

`{cfg.gates}` is a list of `{ key, when, reason }`, where `when` is `before-start` (ask before branching) or `before-merge` (build, verify and gate it, open the PR, then ask before merging). Physical devices, camera hardware, signing secrets, store consoles, pilots and trademark checks are typical gated work. When a story isn't listed but its acceptance criteria clearly need hardware or a human (for example "on two reference devices"), treat it as `before-merge` and say why.

## Stopping

When you stop for any reason, including a finished budget:

1. Leave the git state safe. Either you're on `main`, clean and up to date, or you're on the story branch with everything committed and pushed. Name which one.
2. Append a `STOP` entry to the ledger: the story key, the cycle phase, the reason, the branch, the PR, and the next action.
3. Tell the user, in at most six lines: what was merged this run, where it stopped and why, and the exact resume invocation.

## References

- `{skill-root}/references/preflight.md`: environment and repository checks.
- `{skill-root}/references/story-cycle.md`: the per-story procedure, from branch to merge.
- `{skill-root}/references/worker-prompts.md`: the exact prompts for the build worker, the fix worker and the acceptance gate.
- `{skill-root}/references/epic-close.md`: closing an epic and running its retrospective.
- `{skill-root}/defaults.toml`: every tunable, documented in place.
