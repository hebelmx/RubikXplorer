# Issue Cycle

Use this procedure after activation. Keep one active issue per worktree and PR.

## 1. Preflight and queue snapshot

- Confirm `git`, `gh`, and `uv` are available; `gh auth status` succeeds; the remote resolves to a GitHub repository; the default branch and the issue sprint/status scripts are discoverable.
- Never clean, stash, reset, or switch another checkout. If the current tree is dirty, create a separate worktree for any read-only triage if needed; do not start an issue branch in that tree.
- Fetch the configured remote and use its default branch as the work base. Do not pull or switch the user's current checkout.
- Read the issue ledger and sprint-plan status. For a new run, snapshot open issues with `gh issue list --state open --limit 100 --json number,title,body,labels,assignees,url,createdAt,updatedAt`. If more than 100 are open, page through the rest before claiming the triage complete.
- For each plausible candidate, inspect the full issue, its comments, linked PRs/issues and relevant current evidence. Issue text alone is not proof that a bug exists or is fixed.

If auth, remote, tools, or repository state prevents reliable triage, record a `STOP` and report the exact preflight failure. Do not alter the environment to make preflight pass.

## 2. Triage and select

Make a compact triage table with issue number, title, route, evidence, dependencies, and next action. Use these routes:

| Route | Evidence and action |
|---|---|
| `verify-close` | The issue appears already resolved on the default branch. Reproduce or inspect direct evidence first; close only after verification. |
| `easy-fix` | The defect and expected behavior are clear, bounded, testable, and do not need a device, secret, account, product decision, or planning change. Select for implementation. |
| `planning-change` | The issue changes scope, requirements, architecture, UX, or story acceptance. Run `bmad-correct-course`; do not code against an unapproved direction. |
| `human-input` | The issue requires a physical device, external account/secret, product decision, user data, or evidence only a person can provide. Leave open and report the needed input. |
| `duplicate` | Another issue or merged PR already covers it. Verify the relationship and status; do not create duplicate work. Close only if its resolution is already evidenced. |
| `unclear` | The expected behavior or reproduction is insufficient to decide. Ask a focused question and leave open. |

Select the strongest `verify-close` or `easy-fix` items first, respecting the invocation's issue number or count. Do not count blocked issues against the work limit if an independent candidate remains. Before starting each issue, check that it is still open, no other ledger entry owns it, and no active PR already claims it.

Append a `TRIAGE` ledger entry before external mutations. Triage notes stay in the private ledger; do not label, comment on, assign, or close issues merely to mark triage.

## 3. Resolve without code when possible

For `verify-close`:

1. Check the issue's claim against the default branch and reproduce the relevant test, command, or observable behavior.
2. If the evidence does not establish resolution, reclassify it; do not close it.
3. If resolved, capture concise evidence (test name and result, commit/PR, or reproduced behavior) and close the issue with a comment stating that evidence. Record the closed state and evidence in the ledger.

For duplicates, verify the canonical issue remains open or resolved. If the duplicate has no independent remaining scope, close it with a link and a short reason. Otherwise keep it open and explain the remaining scope in the ledger.

## 4. Build a clear fix

For an `easy-fix`:

1. Reconfirm the issue is open and no active PR or worktree is already resolving it. Create an isolated sibling worktree at the fetched default branch and a unique branch such as `fix/issue-<N>-<slug>` (use `feat/` or `chore/` when repository conventions require it). Never reuse a branch without a matching open ledger entry.
2. Dispatch a fresh build worker to run `bmad-build-auto` with the issue number, title, complete body, relevant comments, and any clarifications already recorded. Tell it to implement only the issue's confirmed scope, add a regression test, document the verification, and commit locally without pushing or opening a PR. Pass the session attribution lines verbatim when available.
3. If the worker reports an intent gap, planning mismatch, human prerequisite, or blocked status, follow that route and stop the issue. Do not fill gaps by guessing.
4. Independently verify from the worktree: inspect the issue-keyed spec's status and summary, confirm commits and clean state, run the repository's documented verification commands, and confirm the new or existing regression test proves the reported behavior. On a verification failure, dispatch one fix attempt with the failure evidence and rerun. If it still fails, stop with the worktree preserved.
5. Run a fresh adversarial acceptance review against the original issue and the code change, without giving the reviewer the worker's narrative. Require no unresolved high-severity issue or unmet expected behavior. Allow at most two fix rounds; if the gate still fails, stop and report findings.
6. Open one PR for the issue with `Closes #<N>` in its body. Include the issue's expected behavior, implementation summary, regression test, full verification commands/results, review verdict, and any accepted deferrals. Do not claim the issue is fixed before merge.

## 5. CI, rebase-by-merge, and close

- Wait for all required PR checks. If checks are pending, wait up to the repository's configured CI timeout. If no checks appear while workflow files exist, wait once and then stop; if CI is red, attempt one bounded fix, push, and wait again. A second red result or timeout leaves the PR open.
- Before merge, fetch the remote default branch. If the PR branch is behind, merge the default branch into it (no rebase or force-push), rerun local verification and wait for CI again. Abort and stop on conflicts.
- Merge only after all required checks are green and any configured human gate is satisfied. Never use `--admin`, `--auto`, or other check bypasses.
- Confirm the merge commit and confirm GitHub closed issue #N. If it remains open despite the merged `Closes #N` reference, close it with a concise comment linking the PR and its green checks.
- Record PR URL, merge SHA, checks, and issue close state in the ledger. Remove only the clean worktree this run created; do not delete branches unless configured policy allows it.

## 6. Planning, human, and uncertain issues

- `planning-change`: invoke `bmad-correct-course` with the issue as trigger and the project planning context. The proposal must state what story or acceptance change is needed. Leave the issue open until the PO accepts the plan and a follow-up story is tracked; do not implement or merge a planning proposal as if it were the bug fix.
- `human-input`: state exactly what evidence or decision is needed and why it blocks correctness. Keep the issue open and record the next action. Continue with unrelated easy wins.
- `unclear`: ask one short, self-contained question with concrete options where possible. Do not start implementation until the answer resolves the intent gap.
- If a correct-course proposal concludes no plan change is required, return the issue to triage and decide whether it is now a clear implementation issue.

## Ledger entries

Append concise timestamped lines to `.git/bmad-orchestrate-issue/ledger.md`:

```text
RUNNING issue:#N <phase> <branch> <worktree> <UTC time>
TRIAGE #N <route> <evidence/next action>
VERIFY #N <commands and result>
PR #N <URL>
CI #N <result>
MERGED #N <PR URL> <full merge SHA> <issue closed|still open>
STOP #N <phase> <reason> <branch/worktree/PR> <next action>
```

On `resume`, re-read the issue, worktree, PR and CI state before continuing; the ledger is a checkpoint, not proof that external state is unchanged.
