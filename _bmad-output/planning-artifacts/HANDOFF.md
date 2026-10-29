# Handoff: planning phase → epics & stories

*Written 2026-10-29 at the end of the planning session. Delete or overwrite it when the next phase finishes.*

## Where things stand

The PRD, the architecture and the UX are all finalized and committed on branch `docs/mvp-prd`, at `060a3f9`. The branch has **not** been merged to `main`, and there is no PR yet. No app code exists yet.

| Artifact | Path | Status |
| --- | --- | --- |
| PRD + addendum | `prds/prd-RubikXplorer-2026-10-29/prd.md`, `addendum.md` | final (FR-1..29, NFR-1..13, UJ-1..6, OQ-1..9) |
| Architecture spine | `architecture/architecture-RubikXplorer-2026-10-29/ARCHITECTURE-SPINE.md` | final (AD-1..AD-20, Ownership Matrix) |
| UX | `ux-designs/ux-RubikXplorer-2026-10-29/DESIGN.md`, `EXPERIENCE.md`, `mockups/` | final (9 key flows, 4 mockups) |
| Portfolio docs | `docs/ARCHITECTURE.md`, `docs/walkthrough/architecture-walkthrough.html` | done |
| Agent instructions | `AGENTS.md` (managed block), `CLAUDE.md` (pointer only), `.claude/settings.json` + `.claude/hooks/protect-paths.sh` | done |

Each planning folder has a `.memlog.md`, an append-only decision log. Reload it before changing that artifact. Never hand-edit a memlog; the hook blocks file-tool edits to it. Append with `uv run _bmad/scripts/memlog.py append`.

## Next task

Run **`bmad-create-epics-and-stories`** with these inputs: the PRD, the addendum, the architecture spine and both UX spines.

- **Starting outline:** use addendum §F as the starting epic outline, not the final one. Epics:
  - E01 domain model;
  - E02 validation and Solver adapter (now the own clean-room solver, track P0.S);
  - E03 rendering and gestures (a 2D net renderer first);
  - E04 Lesson schema and curriculum;
  - E05 diagnosis and recovery;
  - E06 Physical-cube Mode;
  - E07 persistence;
  - E08 playback;
  - E09 a11y, performance and compatibility;
  - E10a/b/c camera (now P0);
  - E11 2-look LL and Library.
- **Stories:** every story should cite its FR, AD and UX-flow IDs.
- **Order:** sequence by the ~16-week roadmap in addendum §F:
  - P0.1, weeks 1–2: the cube engine, the 2D net, one Objective proof of concept, and the recognition spike;
  - P0.S: the solver in parallel, weeks 3–6.
- **After this:** `bmad-sprint-planning`, then `bmad-build` per story.

## Open items with deadlines

- **OQ-9** and **ARCH-OQ-1**, due by the end of build week 2:
  - confirm the ~16-week P0, or move scan uses to P1;
  - name the reference devices.
- **Spikes in weeks 1–2:**
  - OQ-3, the renderer: SceneView vs. OpenGL ES;
  - OQ-7, the Quick Scan method;
  - the spike-failure rule is already decided: ship the camera features as BETA.
- **Check at scaffold:** KSP 2.3.12 vs. Kotlin 2.4.21. Then run `bmad-project-context` (refresh) to replace the `AGENTS.md` TODO commands with the real `./gradlew` invocations.
- **OQ-1, the store name:** a trademark search before publishing. "RubikXplorer" is a codename only.
- **UX `[ASSUMPTION]`s, still to confirm:** the palette hex values, the IBM Plex fonts, the gesture mapping, and the 4-tab IA.

## How the user likes to work

These preferences are also saved to Claude's memory:
- Fast path in BMAD workflows. Batch the questions, then draft with `[ASSUMPTION]` tags.
- Open finished documents in VS Code (`code <paths>`).
- Commit at the end of each phase, after asking, on the feature branch, with Conventional Commits. Never commit to `main` directly.
- Run reviewer and validation subagents only when the user opts in.
