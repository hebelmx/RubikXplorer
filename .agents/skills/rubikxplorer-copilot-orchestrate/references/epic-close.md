# Closing an epic

An epic is complete when `sprint_plan.py status` shows every story under `epic-N` as `done`.

1. **Retrospective** (`{workflow.epic_retrospective}`): `skip` proceeds to step 2; `ask` asks the user and recommends yes; `auto` runs it. For a retrospective, create a clean `docs/epic-N-retrospective` branch and isolated worktree from the updated default branch. Delegate to a fresh general-purpose agent to invoke `bmad-retrospective -H epic N`, commit its output with supplied attribution lines, and return only `{"status":"...","file":"<path>","action_items":0,"acceptance":"accepted|not accepted|..."}`. If acceptance is not `accepted` or blocking action items remain, stop and ask.
2. **Epic status.** In that worktree, set `epic-N=done`, and `epic-N-retrospective=done` if the retrospective ran. Commit `chore(sprint): close epic N`.
3. **Pull request.** Open a PR against `{workflow.default_branch}` with a summary, retrospective file, epic status change, action items, verification, and attribution line. The adversarial acceptance gate is unnecessary for docs-only changes.
4. **CI and human gates.** Check `{workflow.gates}` for an entry whose `id` is `epic-N` and whose `when` is `before-merge`; if present, stop after PR and CI with the PR URL. Wait for checks up to `{workflow.ci_timeout_minutes}`. Do not merge while checks are failing, pending, or missing when workflows exist. If no checks are reported, stop without merging. Stop at a `before-merge` human gate; never bypass it.
5. **Merge and cleanup.** If the epic branch is behind, merge the default branch into it; never rebase or force-push. Re-run verification and CI after updating. Merge with `gh pr merge <pr> --{workflow.merge_method}`; never use `--admin` or `--auto`. Add `--delete-branch`. Remove this run's worktree only after verifying the PR is merged and the worktree is clean.
6. **Planning change.** If the retrospective recommends changing the plan, stop and recommend `bmad-correct-course` before starting another epic.
7. **Report** the story count, PR range, retrospective file, and open action items in one line.
