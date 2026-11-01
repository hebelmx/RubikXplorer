# Preflight

The invocation is already parsed. Run route-specific checks and continue when they pass. On failure, report it, stop, and ask the user; do not repair the environment.

## `status` route

1. Confirm `git` and `uv` are available and `{project-root}` is a Git worktree.
2. Locate `sprint_plan.py` in this order: `.agents/skills`, `.github/skills`, then `.claude/skills`. Use the first match as `{sprint_plan}` and stop if none exists.
3. Use `planning_artifacts` and `implementation_artifacts` from the configuration resolved during activation. Run `uv run {sprint_plan} status --status-file {implementation_artifacts}/sprint-status.yaml` and confirm the ledger's common Git directory. Do not require GitHub authentication, remote access, or agent delegation for a read-only status request.

## Build and resume routes

1. **Tools and delegation.** Confirm `git`, `gh` (`gh auth status`), and `uv` are available. Confirm Copilot CLI can delegate to fresh `general-purpose` and `code-review` agents. If an agent cannot be launched, stop rather than doing its work in the orchestrator context.
2. **Repository and base.** Confirm `{project-root}` is a Git worktree and `{workflow.remote}` points to the intended GitHub repository (`gh repo view --json nameWithOwner`). Resolve the absolute Git common directory with `git rev-parse --git-common-dir`; the ledger is stored there so it is shared by worktrees and never tracked. Unless resuming, fetch `{workflow.remote}` and confirm `{workflow.remote}/{workflow.default_branch}` exists. Never switch or clean the user's current worktree; story work happens in a separate branch and worktree.
3. **BMad.** Confirm `bmad-build-auto` is available. Locate `sprint_plan.py` in the status-route order above and record its exact path as `{sprint_plan}`. If anything is missing, recommend the matching BMad setup and stop.
4. **Planning and tracking.** Use `planning_artifacts` and `implementation_artifacts` from the activation-time command `uv run {project-root}/_bmad/scripts/resolve_config.py --project-root {project-root}`; do not resolve config a second time. Confirm the epics file exists under `{planning_artifacts}` and, when frontmatter includes `status`, it is `final`. Run `uv run {sprint_plan} status --status-file {implementation_artifacts}/sprint-status.yaml`; if validation fails, recommend `bmad-sprint-planning` and stop.
5. **Ledger and branch protection.** Confirm `{git_common_dir}/bmad-orchestrate/` can be created. For a new mutating run, initialize the ledger if absent; when resuming, stop and ask if it is missing or has no open `STOP` or `RUNNING` entry. If practical, check for required status checks on the default branch. Report once if none are configured; never change branch protection.
