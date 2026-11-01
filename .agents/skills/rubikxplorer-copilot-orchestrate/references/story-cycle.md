# Story cycle

One story, one branch, one worktree, one PR. Append `RUNNING <story_key> <phase> <ISO time>` to the ledger at the start of each phase. On resume, re-check that phase's preconditions before continuing.

`{key}` is the sprint-status story key; `{id}` is its `N.M` form. `{set}` means running `sprint_plan.py generate` with the epics file, status file, stories directory, project, date, and `--set {key}=<status>`. The script validates and writes sprint status atomically; never hand-edit its YAML.

## A. Branch and worktree

1. Check `{workflow.gates}` for an entry whose `id` matches `{key}` and whose `when` is `before-start`; stop before creating anything when one applies. If acceptance criteria clearly require a device, secret, account, external service, or human action, use a `before-merge` gate unless it is required to clarify intent before implementation.
2. Fetch the remote and start from an up-to-date `{workflow.remote}/{workflow.default_branch}`. Leave the user's current checkout untouched.
3. Choose a prefix from `{workflow.branch_prefix_rules}` by matching the story title, otherwise `{workflow.branch_prefix_default}`. Create `{prefix}/{key}`. If the branch exists locally or remotely without a matching ledger entry, stop and ask.
4. Resolve `{workflow.worktree_dir}` relative to `{project-root}`, create its parent directory if absent, and create the worktree at `<resolved-dir>/{key}` from the remote default branch with the new branch. If that path exists and is not the recorded worktree for this run, stop and ask; never reuse an unrelated directory.
5. In that worktree, set the story to `in-progress` (and its parent epic too if it was `backlog`), commit `chore(sprint): start story {id}` with the session's attribution lines, then push with `-u`.

## B. Build

1. Dispatch the build worker from `worker-prompts.md` to a fresh Copilot `general-purpose` agent, with `{story_worktree}` as its working directory. Wait for completion.
2. Parse its JSON result. If missing or malformed, ask that same agent once to emit the required result; stop if it remains invalid.
3. Route on `status`: `done` proceeds; `blocked` with an intent gap stops and asks the worker's questions verbatim; other blocked states, `ready-for-dev`, and unexpected values stop with the concrete reason and options. After intent clarification, set only the spec frontmatter `status: draft`, then redispatch with the answers.
4. If the spec has `followup_review_recommended: true`, dispatch the worker in follow-up mode up to `{workflow.followup_passes}` times. Stop and ask if it remains recommended.
5. Read only the spec frontmatter fields `status`, `warnings`, `deferred[].severity`, and `followup_review_recommended`. A high-severity deferred item stops for a decision.

## C. Independent verification

1. Confirm the story worktree is clean and contains commits after the start-story commit.
2. Run `{workflow.verify_commands}` or the verification commands in the worktree's `AGENTS.md`; if absent, use the spec's `## Verification` section. Store output in a temporary log and read only its exit code and, on failure, the last `{workflow.log_tail_lines}` lines.
3. On failure, dispatch one fix worker with the command and log path. Re-run verification once; if it still fails, stop and ask.

## D. Acceptance gate

The gate always runs; it has no disable switch. Dispatch a fresh, context-free Copilot `code-review` agent using the prompt in `worker-prompts.md`; it must form its own view and not receive the build worker's narrative. Proceed when the verdict is `pass` with no blocking finding, or when the verdict is `fail` but no finding is in `{workflow.gate_fail_on}`. Otherwise dispatch a fix worker, then repeat C and D. Treat any finding in `{workflow.gate_fail_on}` as blocking even when the agent returned `pass`. After `{workflow.max_fix_rounds}`, stop and show up to five findings. Record verdict and finding counts in the ledger.

## E. Pull request

1. If `{workflow.gates}` has an entry whose `id` matches `{key}` and whose `when` is `before-merge`, continue through PR creation and CI, then stop before merging.
2. Set the story to `review`, commit `chore(sprint): story {id} in review`, and push.
3. Open a PR against `{workflow.default_branch}` with `gh pr create`. Its body includes a summary from the spec's `## Auto Run Result`, acceptance criteria and gate evidence, build/review findings and deferred items, verification results, the story Refs line, and the PR attribution line.

## F. CI

Watch `gh pr checks <pr> --watch --interval 30` until checks resolve or `{workflow.ci_timeout_minutes}` expires. If no checks appear while `.github/workflows/` contains workflows, wait one more interval, then stop and ask. If there are no workflows or no checks are reported, stop without merging. For red checks, inspect failed job summaries with `gh run view <run-id> --log-failed`, keep only the log tail, dispatch one fix worker, push, and watch again. Stop if checks remain red or pending.

## G. Merge and cleanup

1. Re-check the human gate. For `before-merge`, stop with the PR URL.
2. Fetch the remote default branch. If the story branch is behind, merge the remote default branch into it (no rebase or force-push), resolve no conflict by guessing, and re-run C and F after a clean merge.
3. Set the story to `done`, commit `chore(sprint): story {id} done`, push, and confirm CI is green again.
4. Merge with `gh pr merge <pr> --{workflow.merge_method}` and add `--delete-branch`. Never use `--admin` or `--auto`.
5. Verify the PR is merged and the remote default branch contains the merge. Verify sprint status shows the story as `done`.
6. Remove only this run's story worktree, and only after confirming it is clean. Keep it and stop if it contains uncommitted or unmerged work. Record `MERGED <key> <pr-url> <merge-sha>`.

## Human gates and stop state

Stop for physical-device checks, secrets, accounts, payments, external services, unclear product intent, a planning change, unresolvable conflicts, denied permissions, failed verification or acceptance, or expired CI. Ask before starting when a decision is required to implement safely; otherwise use the configured `before-merge` gate. Ask one concrete question with the story and evidence, then record the user's decision before resuming. Never treat approval for one exception as approval for another.

At every stop, leave the current worktree safe (clean on the default branch, or committed and pushed on the story branch), append a `STOP` entry with story, phase, reason, branch, PR, worktree path, and next action, then give the exact `resume` invocation. If the worktree is dirty, do not clean it or remove it.
