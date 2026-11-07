# Story cycle

One story, one branch, one PR. The cycle has phases A–H. Append a ledger line at the start of each phase: `RUNNING <story_key> <phase> <ISO time>`. On resume, re-enter at the recorded phase after re-checking that phase's preconditions.

Notation:

- `{key}` is the story key from sprint-status (for example `1-2-model-the-cube-state-and-face-moves`).
- `{id}` is its `N.M` form (for example `1.2`).
- `{set}` means `uv run {sprint_plan} generate --epic-file <epics file> --status-file {status_file} --stories-dir {implementation_artifacts} --project "<project>" --date "<MM-DD-YYYY HH:MM>" --set {key}=<status>`. The script validates and writes the file atomically. Never hand-edit the YAML.

## Phase A: Branch

1. Start from an up-to-date `{git.default_branch}` with a clean tree.
2. Choose the prefix from `{git.branch_prefix_rules}` by matching the story title; otherwise use `{git.branch_prefix_default}`. Create `{prefix}/{key}`. If that branch already exists locally or on the remote and there is no ledger entry for it, stop and ask.
3. `{set}` the story to `in-progress`. The parent epic moves too, if it was `backlog`: also `--set epic-N=in-progress`.
4. Commit `chore(sprint): start story {id}` with the attribution trailer, then push with `-u`.

## Phase B: Build (worker)

1. Dispatch the **build worker** from `worker-prompts.md` according to `{run.build_worker}`:
   - `headless` (the Dropstone default): run `{run.build_headless_command} {run.build_headless_args}` from `{project-root}` with the prompt on stdin, in the foreground, and wait for it to exit. Capture stdout to a temp log and read only the final JSON block. This is a top-level process, so it can spawn the subagents `bmad-build-auto` requires.
   - `subagent`: use the `task` tool with `subagent_type: "general"`, a fresh context, blocking, and no `background`. Use this only when the host's subagents can themselves spawn subagents; on Dropstone they cannot, so `headless` is the working setting.
2. It returns a JSON block. Parse it. If it is missing or malformed, ask the same worker once to re-emit the JSON. If it still fails, stop.
3. Route on `status`:
   - `done`: go to C.
   - `blocked`: classify `blocking_condition`.
     - **Intent gap or unclear intent:** stop and ask, presenting the worker's questions verbatim. With the answers, set the spec's frontmatter `status: draft` (this one frontmatter field is the only spec edit you may make). Re-dispatch the build worker with the answers in its `clarifications` slot.
     - **Any other condition:** stop with the stop-table options.
   - `ready-for-dev` or anything else unexpected: stop and report.
4. **Follow-up review.** If the spec has `followup_review_recommended: true`, re-dispatch the build worker in follow-up mode (the spec path is the intent; build-auto runs a fresh review pass on a `done` spec), up to `{review.followup_passes}` times. If it is still recommended after that, stop and ask.
5. Read **only the spec's frontmatter** yourself: `status`, `warnings`, `deferred[].severity`, `followup_review_recommended`. If a `deferred` item has severity `high`, stop and ask.

## Phase C: Independent verification

1. Confirm the tree is clean and the branch has commits after the `start story` commit.
2. Run each `{verify.commands}` entry, or the AGENTS.md "Running and verifying" commands. Write each one's output to a temp log, and read back only the exit code and, on failure, the last `{verify.log_tail_lines}` lines.
3. **On failure:** dispatch the **fix worker** according to `{run.fix_worker}` (native `task` subagent by default) with the failing command and the log path, once. Re-run verification. If it still fails, stop.

## Phase D: Acceptance gate (adversarial)

Skip this phase if `{gate.enabled}` is false.

1. Dispatch the **acceptance gate** from `worker-prompts.md` according to `{run.gate_worker}`: a fresh, context-free worker. It gets the story id, the epics-file path, the merge-base, and the paths of AGENTS.md and the spec. It never gets the worker's narrative.
2. **Verdict `pass`:** go to E.
3. **Verdict `fail`:** if any finding's severity is in `{gate.fail_on}`, dispatch the fix worker with the findings list. Then re-run C and D. After `{gate.max_fix_rounds}` rounds, stop and ask, showing at most 5 findings in one line each.
4. Record the gate verdict and finding counts in the ledger.

## Phase E: Pull request

1. If there is a human gate `before-merge` for this story, still do E and F, then stop at G.
2. `{set}` the story to `review`, commit `chore(sprint): story {id} in review`, and push.
3. Open the PR with `gh pr create --base {git.default_branch} --title "<type>(<epic-area>): <story title> ({id})" --body-file <tmp>`. The body contains:
   - **Summary:** taken from the spec's `## Auto Run Result`. Have the gate worker return it, or read just that section.
   - **Acceptance criteria:** a checklist built from the gate's per-AC evidence.
   - **Review:** build-auto triage counts, gate verdict, and deferred items.
   - **Verification:** each command and its result.
   - **Refs:** the story's Refs line.
   - The PR attribution line.

## Phase F: CI

1. `gh pr checks <pr> --watch --interval 30` in bounded waits: at most 10 minutes per call, repeated until checks resolve or `{ci.timeout_minutes}` passes. If the host moves a long call to the background, wait for it with the host's background-wait mechanism instead of starting a second watcher.
2. **No checks reported:**
   - If `.github/workflows/` contains workflow files, wait one more interval, then stop and ask.
   - If there are no workflows: merge only when `{ci.allow_merge_without_ci}` is true. Otherwise stop and ask.
3. **Red:** find the failing jobs (`gh pr checks <pr>` and `gh run view <run-id> --log-failed`). Write the logs to a temp file and read only their tail. Dispatch the fix worker once, push, and watch again. If it is still red, stop.

## Phase G: Merge

1. Re-check the story's human gate. If there is a `before-merge` gate, stop here and ask, with the PR URL.
2. Make sure the branch is current: `git fetch`, then `git merge-base --is-ancestor {remote}/{default_branch} HEAD`. If it is behind:
   - merge `{remote}/{default_branch}` into the branch, with no rebase and no force-push;
   - if there's a conflict, abort the merge and stop;
   - otherwise push, then re-run C and F.
3. `{set}` the story to `done`, commit `chore(sprint): story {id} done`, push, and wait for CI again (F).
4. `gh pr merge <pr> --{git.merge_method}` (add `--delete-branch` if `{git.delete_branch}`). Never pass `--admin` or `--auto`.
5. Check out `{default_branch}`, `git pull --ff-only`, and confirm that sprint-plan status shows the story as `done`.
6. Ledger: `MERGED <key> <pr-url> <merge-sha>`.
