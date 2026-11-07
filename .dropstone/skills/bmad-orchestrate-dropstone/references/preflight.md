# Preflight

Run each check, then report them together in one line ("Preflight: 10/10 ok") or list the failures. Any failure stops the run and asks the user. Never fix the environment silently.

1. **Tools.** `git`, `gh` (`gh auth status` succeeds), `uv`. For the build worker, the configured `{run.build_headless_command}` exists (`claude --version` by default).
2. **Repository.** You are at `{project-root}`, inside a git work tree, and `{git.remote}` exists and points at a GitHub repo (`gh repo view --json nameWithOwner`).
3. **Clean state.** `git status --porcelain` is empty. When you are not resuming, check out `{git.default_branch}` and fast-forward it (`git pull --ff-only`). If the fast-forward fails, stop.
4. **BMAD install.** `_bmad/scripts/resolve_config.py` runs, the `bmad-build-auto` skill is available, and `{sprint_plan}` exists. If a required skill or script is missing, recommend the matching install and stop.
5. **Planning is final.** An epics file exists in `{planning_artifacts}`. If its frontmatter has `status`, it must be `final`.
6. **Sprint tracking.** `uv run {sprint_plan} validate --status-file {status_file}` returns ok. If it fails, recommend `bmad-sprint-planning` (fix intent) and stop.
7. **Ledger.** `{project-root}/.git/bmad-orchestrate/` exists or can be created.
8. **Worker capability (hard test).** The build worker must be able to spawn `bmad-build-auto`'s subagents.
   - With `{run.build_worker} = "headless"`: run `{run.build_headless_command} {run.build_headless_args}` once with a trivial prompt (for the default, `claude -p "Reply with only: ok"`) and confirm it answers. If it fails, stop and ask; do not silently fall back.
   - With `{run.build_worker} = "subagent"`: dispatch a throwaway `task` subagent and ask it to list its tools. If it has no subagent tool, the build worker cannot run natively. Switch to `headless` or stop. On Dropstone, `general` subagents have no `task` tool, so `headless` is the working setting.
9. **Branch protection (advisory only).** `gh api repos/{owner}/{repo}/branches/{default_branch}/protection` fails with 404, or shows no required checks. Mention once that enabling required status checks on `main` makes the CI gate enforceable by GitHub itself. Never change protection yourself.
10. **Codex (advisory, Dropstone orchestrator only).** If `{cfg.codex.enabled}` is true, run the availability check in `codex-delegation.md`. If Codex isn't available, this check doesn't fail the run: every role uses a Claude worker. Report `Codex: on` or `Codex: off (<reason>)` in the preflight line. When Codex is on, also set up the build-auto markers as `codex-delegation.md` § build-auto review layers describes, and report `Codex review: on|off (<reason>)` and `Codex implement: on|off (<reason>)`.
