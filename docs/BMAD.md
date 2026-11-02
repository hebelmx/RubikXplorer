# BMAD tooling

This project uses **BMAD Method 6.12.4 stable**, including the core module,
installed for Codex, Claude Code, and OpenCode. The installed version is recorded
in `_bmad/_config/manifest.yaml`.

Use the pinned npm installer from the repository root to repair the installation:

```sh
npx --yes bmad-method@6.12.4 install --modules bmm --tools codex,claude-code,opencode --all-stable --yes
```

Keep this version until an explicit upgrade is planned. Do not run
`npx skills add bmad-code-org/BMAD-METHOD` or `npx skills update` for BMAD here:
that installation path supplies the preview workflow and can replace the stable
skills with a ticket-store workflow that does not read this project's sprint files.
The preview `skills-lock.json` has been removed; the stable installer maintains
its own manifests under `_bmad/_config/`.

Planning stays in `_bmad-output/planning-artifacts/epics.md` and execution status
in `_bmad-output/implementation-artifacts/sprint-status.yaml`. No initiative or
ticket-store migration is needed. The custom `bmad-orchestrate` and
`rubikxplorer-copilot-orchestrate` skills remain project-owned; the other BMAD
skills and `_bmad/` runtime files come from the stable installer.

Check configuration, sprint tracking, and build workflow rendering with:

```sh
uv run _bmad/scripts/resolve_config.py --project-root "$PWD"
uv run .agents/skills/bmad-sprint-planning/scripts/sprint_plan.py status --status-file _bmad-output/implementation-artifacts/sprint-status.yaml
uv run --no-cache _bmad/scripts/render_skill.py --project-root "$PWD" --skill "$PWD/.agents/skills/bmad-build-auto"
```

The renderer only creates an ignored workflow snapshot; it does not execute a
story. Stable build-auto accepts an epic story as intent and returns `done` on
success, matching the orchestrator's existing contract. Use a fresh agent session
after replacing the installation so its skill catalog reflects the stable skills.
