# Codex delegation (Dropstone orchestrator only)

The purpose is to split token spend between providers. When a Codex CLI is available, the orchestrator sends some worker roles to Codex instead of a Claude worker. Codex reviewing Claude's work also makes the acceptance gate more independent, because it comes from a different model family.

## Scope: who this applies to

- **Only a Dropstone orchestrator** applies this file. If you are running this skill inside another host, ignore this file and treat `{cfg.codex.enabled}` as false.
- **Only the orchestrator** dispatches to Codex. Workers never do, whether they are Dropstone subagents, headless `claude -p` processes, or Codex itself. Never put a Codex instruction in any worker prompt. This keeps delegation one level deep, with no Codex→Claude→Codex chains.
  - **One sanctioned exception:** the review layers of `bmad-build-auto`. They reach Codex through the project's local override file, not through a worker prompt. The orchestrator controls them with a marker file (see "build-auto review layers" below). Codex itself never delegates further.
- These roles **always stay on a Claude worker**, because they need nested subagents or Claude skills:
  - the build worker (`bmad-build-auto`, run as headless `claude -p`) and its follow-up review passes;
  - the epic retrospective (`bmad-retrospective`) and post-merge skill actions, on a Dropstone worker (native subagent or headless), never on Codex.

  Inside the build worker, spec planning, diff reading, verification, triage and the Intent Alignment layer stay on Claude. Its implementation subagent and its other three review layers go to Codex (see below).

## Roles delegated when Codex is available

| Role | Phase | `{cfg.codex.*}` key | Sandbox |
| --- | --- | --- | --- |
| Acceptance gate (adversarial) | D | `gate` | read-only |
| Fix worker: verification failure | C.3 | `fix_verify` | `--write` |
| Fix worker: CI failure (diagnose the log tail, then fix) | F.3 | `fix_ci` | `--write` |
| Fix worker: gate findings | D.3 | `fix_gate` | `--write` |
| build-auto review layers: Blind Hunter, Edge Case Hunter, Verification Gap | B (inside the build worker) | `build_review` (bool) | read-only |
| build-auto implementation (step-03 `implementation_handoff`) | B (inside the build worker) | `build_implement` (bool) | `--write` + network |

Each role key is `"codex"` or `"claude"`, except `build_review` and `build_implement`, which are true or false.

**Independence rule:** a reviewer never grades its own patch. With `fix_gate = "auto"` (the default), gate findings are fixed by the *other* provider from the one that ran the gate: a Codex gate gets a Claude fix, and a Claude gate gets a Codex fix. The re-run gate (D) then uses the same provider as the first gate.

**Codex-written code:** when Codex implements, the Codex gate is still a fresh, context-free task that never sees the implementer's report, but it is the same model family. Two Claude checks keep a cross-family view: build-auto's own diff read and triage, and its Intent Alignment layer. With `fix_gate = "auto"`, gate findings are fixed by Claude. If gate quality drops, set `gate = "claude"` in the project override.

## build-auto review layers and implementation

`bmad-build-auto` reads `{project-root}/_bmad/custom/bmad-build-auto.user.toml`. That file is gitignored, so it stays on this machine. It overrides `workflow.implementation_handoff` (implementation on Codex `--write`, guarded by a second marker, `codex-implement.on`) and three review layers by `id`:
- `blind-hunter`
- `edge-case-hunter`
- `verification-gap`

Each overridden layer applies a **scope guard**. It uses Codex only when all three conditions hold:
- the runner is a Claude agent (the Dropstone build worker is a headless `claude -p` process, so this holds);
- the marker file `{project-root}/.git/bmad-orchestrate/codex-review.on` exists;
- the Codex companion is installed.

When any condition fails, that layer falls back to the stock Claude subagent. `intent-alignment` is not overridden and always runs on Claude.

**Marker lifecycle**, owned by the orchestrator:
- **Create `codex-review.on`** in preflight when Codex is available, `{cfg.codex.build_review}` is true and the override file exists. Log `CODEX-REVIEW on`.
- **Create `codex-implement.on`** in preflight when Codex is available, `{cfg.codex.build_implement}` is true and the override file contains `implementation_handoff`. Log `CODEX-IMPLEMENT on`. If the override predates it, report `Codex implement: off (override outdated)` and offer to refresh it from the template, with one approval.
- **Delete both** at every STOP, including stops caused by errors, and when Codex is switched off mid-run after `{cfg.codex.max_failures}`.

The implementation override launches Codex detached (`nohup … &`) and waits with repeated blocking `tail --pid` calls, because a story can outlast the 10-minute tool timeout and build-auto forbids backgrounded subagents. It writes a one-line verdict to `.git/bmad-orchestrate/build-implement.result` (`codex`, `claude (guard)` or `claude (codex fallback: <reason>)`), which the orchestrator logs after phase B.

So a manual `bmad-build-auto` run outside the orchestrator, or a run by any other host, keeps the stock layers.

**If the override file is missing**, report `Codex review: off (no override)` in preflight. Don't create the file silently. Offer to restore it from this skill's `references/bmad-build-auto.user.toml` template, which needs one approval from the user.

**Accounting:** count build-auto review layers as Codex dispatches: 3 per build or follow-up pass while the marker is on. Count the implementation as 1 Codex dispatch when `build-implement.result` is `codex`, otherwise as 1 Claude dispatch.

## Availability check (once per run, in preflight)

```bash
CODEX_COMPANION=$(ls -d ~/.claude/plugins/cache/openai-codex/codex/*/scripts/codex-companion.mjs 2>/dev/null | sort -V | tail -1)
node "$CODEX_COMPANION" setup --json
```

Codex is **available** when all of these hold:
- `{cfg.codex.enabled}` is true;
- the companion file exists;
- `setup --json` reports `"ready": true` and `auth.loggedIn: true`.

Otherwise every role falls back to Claude for the whole run. Say this once in the preflight line, for example "Codex: off (not logged in)". Never install Codex or log it in yourself.

## Dispatch

1. Fill the role's prompt from `worker-prompts.md`. Use the **Codex variant** for fix workers.
2. Write the prompt to `{project-root}/.git/bmad-orchestrate/codex-<phase>-<key>.prompt.md`. The file stays inside `.git`, so the work tree stays clean.
3. Run it with Bash `run_in_background: true`, then block with `bg_wait` on the returned task id:

   ```bash
   node "$CODEX_COMPANION" task --cwd {project-root} [--write] \
     [--model {cfg.codex.model}] [--effort {cfg.codex.effort}] \
     --prompt-file .git/bmad-orchestrate/codex-<phase>-<key>.prompt.md \
     > .git/bmad-orchestrate/codex-<phase>-<key>.out 2>&1
   ```

   Leave out `--model` or `--effort` when the config value is empty. Never pass `--resume-last`: every dispatch is fresh and context-free.
4. Read **only the tail** of the `.out` file, for example with `tail -c 8000`. Lines starting `[codex]` are progress. The result is the last JSON object, which may be bare or fenced. Never read the whole transcript into your context.

## Sandbox consequences (fix workers)

Gradle needs a writable `~/.gradle` and a local socket for its file-lock service. Since 2026-11-04 this user's Codex config sets `[sandbox_workspace_write] writable_roots = ["~/.gradle"]` and `network_access = true`, and a `./gradlew :core:domain:compileKotlin` probe passed inside a `--write` task. So **`--write` Codex tasks can build and test**. Read-only tasks (the gate, the review layers) may still be blocked, and that is fine. If the config loses `network_access` again, Gradle reports "Could not determine a usable wildcard IP". In that case, set `build_implement = false`; fix workers still work, because the orchestrator verifies.

The Codex `workspace-write` sandbox may block writes to `.git` and to anything outside the repo, such as `~/.gradle`, and it may block network access. For that reason:

- The Codex fix worker edits files and runs the verification commands itself, but it does not commit. It returns a proposed Conventional Commit subject.
- After it returns, the orchestrator runs these mechanical git steps itself (not code reading, so the "never read diffs" rule holds):
  1. Run `git status --porcelain`. If nothing changed and `fixed` is non-empty, treat the dispatch as a failure.
  2. Re-run phase C verification.
  3. If verification passes, run `git add -A && git commit` with the returned subject and the attribution lines.
  4. If verification fails, continue with the normal stop or retry logic.
- Paths Codex must not touch are listed in the prompt. Before committing, check `git status --porcelain`. If any changed path is under `{planning_artifacts}`, `_bmad/`, `.claude/skills/` or `.agents/skills/`, restore it with `git checkout -- <path>` and log it as a finding.

The read-only gate may not be able to run Gradle tests. That is fine, because phase C already ran them. Codex reports what it could not run in `ran`.

## Fallback

A Codex dispatch **fails** when any of these happen:
- a non-zero exit;
- no parsable JSON block;
- the run passes `{cfg.codex.timeout_minutes}`;
- the companion reports an auth or rate-limit error.

When one fails:
1. Log `CODEX-FALLBACK <key> <role> <reason>` in the ledger.
2. Re-dispatch the same role once to a Dropstone `task` subagent with the normal prompt.
3. Do not stop the run.

After `{cfg.codex.max_failures}` consecutive Codex failures, turn Codex off for the rest of the run and say so in the next report.

## Ledger

Tag every worker line with its provider, for example `RUNNING <key> D provider=codex`. Count, per run, the dispatches handled by each provider, and report `codex N / claude M` in the run's final summary.
