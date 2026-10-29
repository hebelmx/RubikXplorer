# Handoff: planning → build

*Written 2026-10-29 when planning ended. Delete it or overwrite it when Epic 1 finishes.*

## Where things stand

Planning is complete on branch `docs/mvp-prd`, with a PR open to `main`. No app code exists yet.

| Artifact | Path | Status |
| --- | --- | --- |
| PRD + addendum | `prds/prd-RubikXplorer-2026-10-29/` | final |
| Architecture spine | `architecture/architecture-RubikXplorer-2026-10-29/ARCHITECTURE-SPINE.md` | final (AD-1..20) |
| UX spines | `ux-designs/ux-RubikXplorer-2026-10-29/` (DESIGN.md, EXPERIENCE.md, mockups/) | final |
| Epics & stories | `epics.md` | final: 12 epics, 78 stories |
| Sprint tracking | `../implementation-artifacts/sprint-status.yaml` | all stories `backlog` |

## Next task

1. Run **`/bmad-orchestrate`**, the user-level skill in `~/.claude/skills/bmad-orchestrate/`. It starts at Story 1.1, "Scaffold the gated multi-module project", and for each story:
   - creates a branch;
   - runs `bmad-build-auto` in a fresh subagent;
   - verifies the result independently and runs an adversarial acceptance gate;
   - opens a PR, waits for CI, and merges.
2. The orchestrator stops and asks at every human gate. The RubikXplorer gates are in `_bmad/custom/bmad-orchestrate.toml`; Stories 1.9 and 1.10 need devices and decisions.
3. After Story 1.1 merges, the orchestrator runs `bmad-project-context` (refresh) as a post-merge action. That puts the real `./gradlew` commands into `AGENTS.md`.
4. Use `/bmad-orchestrate status` to see progress, and `/bmad-orchestrate resume` in a fresh session to continue.

## Decisions due in Epic 1, by the end of week 2

- **Story 1.8, OQ-3:** the renderer, SceneView vs. OpenGL ES.
- **Story 1.9, OQ-7 and OQ-8:** the recognition spike and its test matrix. If the spike misses its target, `CAMERA_SCAN` ships as BETA.
- **Story 1.10, OQ-9 and ARCH-OQ-1:** confirm the ~16-week schedule and name the reference devices. If scope changes, run `bmad-correct-course`.
- **Story 1.1:** the KSP 2.3.12 vs. Kotlin 2.4.21 check.

## Other open items

- **OQ-1:** a trademark search for the store name before publishing (Story 12.5).
- **OQ-2 and OQ-6:** the 2-look Case set, and the licensing of Algorithm data (Stories 9.1 and 9.7).
- **AR-23:** AD-18 has no flag for cutting the Library recognition diagrams. If that cut happens, add the flag through `bmad-correct-course`.
- **UX `[ASSUMPTION]`s still to confirm:** the palette hex values, the IBM Plex fonts, the gesture mapping, and the 4-tab IA.

## How the user likes to work

- Fast path. Ask one batch of questions, then draft with `[ASSUMPTION]` tags.
- Open finished documents in VS Code.
- Commit on feature branches with Conventional Commits, and ask first. Never commit to `main`.
- Reviewer subagents are opt-in only.
