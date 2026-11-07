# Closing an epic

An epic is complete when `{sprint_plan} status` shows every story under `epic-N` as `done`.

1. **Retrospective** (`{cfg.epic.retrospective}`):
   - **`skip`:** go to step 2.
   - **`ask`:** ask the user whether to run it now. Recommend yes.
   - **`auto`:** run it.

   To run it, create the branch `docs/epic-N-retrospective` from an up-to-date default branch. Dispatch one worker (a native `task` subagent is fine; the retrospective spawns no subagents) whose prompt is: load `bmad-retrospective` with the skill mechanism (the Dropstone `skill` tool, or read its SKILL.md when no skill tool exists) and args `-H epic N`, commit its output with the attribution lines, and reply with ONLY `{"status": "...", "file": "<path>", "action_items": <count>, "acceptance": "<accepted|not accepted|...>"}`.

   - If the retrospective's acceptance decision isn't "accepted", or it reports blocking action items, stop and ask before going further. Show the decision and the top action items.
2. **Epic status.** On the retrospective branch (or on a `chore/epic-N-close` branch when the retrospective is skipped), run `{set}` with `epic-N=done`, plus `epic-N-retrospective=done` if the retrospective ran. Commit `chore(sprint): close epic N`.
3. **Merge** with story-cycle phases E–G: a PR, CI and a merge. The acceptance gate (D) doesn't apply to docs-only changes.
4. **Check for a planning change.** If the retrospective recommends a change to the plan, stop and recommend `bmad-correct-course` before starting the next epic.
5. **Report** the epic in one line: the number of stories, the PR range, the retrospective file, and the number of open action items.
