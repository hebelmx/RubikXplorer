---
name: rubikxplorer-copilot-orchestrate
description: 'Orchestrates BMAD stories through Copilot CLI. Use when the user says "orchestrate a sprint in Copilot CLI" or invokes "/rubikxplorer-copilot-orchestrate".'
---
# Copilot BMAD Orchestrator

You coordinate a BMAD build phase in GitHub Copilot CLI. Keep the plan, gates, and run ledger, but do not implement stories or read source diffs yourself; delegate implementation, fixes, and adversarial review to fresh Copilot agents and act on their structured results. The per-story engine is `bmad-build-auto`; do not reimplement its planning or review work.

Every story gets its own branch and reaches the default branch only through a pull request. Never commit directly to the default branch, force-push, rewrite published history, bypass required checks, or merge with admin privileges. Do not merge while required checks are failing, pending, or missing. Follow `AGENTS.md`, `CLAUDE.md`, and the repository's Copilot instructions. Stop for human decisions, missing tools, denied permissions, or failed gates; do not improvise around them.

## Activation

1. Run `uv run --no-cache "{project-root}/_bmad/scripts/resolve_customization.py" --skill "{skill-root}" --project-root "{project-root}" --key workflow`; use its `workflow` values. A failure stops and reports the error.
2. Run `uv run {project-root}/_bmad/scripts/resolve_config.py --project-root {project-root}`. Use `user_name`, `communication_language`, `planning_artifacts`, and `implementation_artifacts`; speak in the configured language.
3. Parse the invocation before preflight: empty or `N` means up to `N` stories (default `{workflow.max_stories}`), `epic N` means that epic, `story <key>` means one story, `status` means status only, and `resume` means the open ledger entry. Run the read-only status preflight for `status` and full preflight for every other request (`references/preflight.md`); on failure, stop and ask the user.
4. Read the ledger in `{git_common_dir}/bmad-orchestrate/ledger.md`; create it only for a mutating run when absent. For `status`, report the sprint-plan result from preflight and the ledger tail if present, then stop without writing.
5. Greet `{user_name}` with the run scope, story budget, and Esc as the interrupt. Follow `references/story-cycle.md` for each story; follow `references/epic-close.md` when an epic finishes. Use `references/worker-prompts.md` for every build, fix, and independent acceptance review.

After `{workflow.handoff_every}` stories, or when context is heavy, write a resume handoff and stop. When a project setting needs to change, invoke `bmad-customize` with its key; if it is unavailable, offer `npx skills add bmad-code-org/BMAD-METHOD --skill bmad-customize`. Mutating runs append a stop entry with phase, reason, branch, PR, worktree, and next action; `status` remains read-only.
