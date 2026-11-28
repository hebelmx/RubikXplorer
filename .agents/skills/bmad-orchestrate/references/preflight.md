# Preflight

Run each check, then report them together in one line ("Preflight: 10/10 ok") or list the failures. Any failure stops the run and asks the user. Never fix the environment silently.

1. **Tools.** `git`, `gh` (`gh auth status` succeeds), `uv`, and Python 3.11+ for the saved-default check.
2. **Saved model defaults.** Run this read-only check before checking out or updating any branch, including when resuming. A missing file, parse error or mismatch stops preflight. Report the affected file, key and expected value, and recommend restoring the baseline before resuming. Never rewrite these files from a sprint.

   ```bash
   python3 - <<'PYTHON'
   import json
   import tomllib
   from pathlib import Path

   checks = [
       (".codex/config.toml", tomllib.loads,
        {"model": "gpt-6-luna", "model_reasoning_effort": "medium"}),
       (".claude/settings.json", json.loads, {"model": "sonnet"}),
       (".gemini/antigravity-cli/settings.json", json.loads,
        {"model": "Gemini 3.8 Flash (Low)"}),
   ]

   failed = False
   for relative_path, parse, expected in checks:
       path = Path.home() / relative_path
       try:
           settings = parse(path.read_text(encoding="utf-8"))
       except (OSError, ValueError):
           print(f"{path}: missing, unreadable or invalid configuration")
           failed = True
           continue
       for key, value in expected.items():
           if not isinstance(settings, dict) or settings.get(key) != value:
               print(f"{path}: expected {key} = {value!r}")
               failed = True
   raise SystemExit(1 if failed else 0)
   PYTHON
   ```

   Inspect any active project or alternate settings layers for model overrides, including `.codex/config.toml`, `.claude/settings*.json`, and alternate user directories set through `CODEX_HOME` or `CLAUDE_CONFIG_DIR`. Report conflicting model overrides and stop before starting the sprint.
3. **Repository.** You are at `{project-root}`, inside a git work tree, and `{git.remote}` exists and points at a GitHub repo (`gh repo view --json nameWithOwner`).
4. **Clean state.** `git status --porcelain` is empty. When you are not resuming, check out `{git.default_branch}` and fast-forward it (`git pull --ff-only`). If the fast-forward fails, stop.
5. **BMAD install.** `_bmad/scripts/resolve_config.py` runs, and the `bmad-build-auto` skill is available to the orchestrator, and the sprint-plan status helper is present. If a required skill or script is missing, recommend the matching install and stop.
6. **Planning is final.** An epics file exists in `{planning_artifacts}`. If its frontmatter has `status`, it must be `final`.
7. **Sprint tracking.** The sprint-plan status helper validates; if it fails, recommend `bmad-sprint-planning` (fix intent) and stop.
8. **Ledger.** `{project-root}/.git/bmad-orchestrate/` exists or can be created.
9. **Worker dispatch.** Require `run.worker = "headless"`. Resolve a compatible CLI, preferring the current host's CLI when it meets the role's requirements in `worker-prompts.md`. Check its noninteractive help for the explicit model flag and verify the selected role models against the installed CLI's catalog or documented aliases (`codex debug models`, `claude --help`, `agy models`). Require a nonempty worker selection and, when the acceptance gate is enabled, a distinct nonempty gate selection for any provider used for that gate. Check project model overrides here too. Report the selected provider and model for each role. Stop if a required role cannot run with its explicit model and existing permissions.
10. **Branch protection (advisory only).** `gh api repos/{owner}/{repo}/branches/{default_branch}/protection` fails with 404, or shows no required checks. Mention once that enabling required status checks on `main` makes the CI gate enforceable by GitHub itself. Never change protection yourself.
