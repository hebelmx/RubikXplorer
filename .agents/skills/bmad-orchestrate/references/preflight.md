# Preflight

Run each check, then report them together in one line ("Preflight: 9/9 ok") or list the failures. Any failure stops the run and asks the user. Never fix the environment silently.

1. **Tools.** `git`, `gh` (`gh auth status` succeeds), `uv`. With `run.worker = "headless"`, also a coding-agent CLI with a supported noninteractive invocation.
2. **Repository.** You are at `{project-root}`, inside a git work tree, and `{git.remote}` exists and points at a GitHub repo (`gh repo view --json nameWithOwner`).
3. **Clean state.** `git status --porcelain` is empty. When you are not resuming, check out `{git.default_branch}` and fast-forward it (`git pull --ff-only`). If the fast-forward fails, stop.
4. **BMAD install.** `_bmad/scripts/resolve_config.py` runs, and the `bmad-build-auto` skill is available to the orchestrator, and the sprint-plan status helper is present. If a required skill or script is missing, recommend the matching install and stop.
5. **Planning is final.** An epics file exists in `{planning_artifacts}`. If its frontmatter has `status`, it must be `final`.
6. **Sprint tracking.** The sprint-plan status helper validates; if it fails, recommend `bmad-sprint-planning` (fix intent) and stop.
7. **Ledger.** `{project-root}/.git/bmad-orchestrate/` exists or can be created.
8. **Subagents.** With `run.worker = "subagent"`, use the current host’s available coding-agent dispatch (for example Codex `spawn_agent`, Claude Code Task/Agent, or OpenCode subagents). No particular provider or tool name is required. Select a compatible worker without asking permission to change providers. If native dispatch is unavailable, use an available headless coding agent after checking its CLI help and permissions. Stop and ask only when no compatible worker can launch; never install tools, change accounts, or bypass permissions as a fallback.
9. **Branch protection (advisory only).** `gh api repos/{owner}/{repo}/branches/{default_branch}/protection` fails with 404, or shows no required checks. Mention once that enabling required status checks on `main` makes the CI gate enforceable by GitHub itself. Never change protection yourself.
