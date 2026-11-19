# Sprint Change Proposal: Story 10.14's re-prompt cap (2026-11-19)

- **Trigger:** GitHub issue #164, "Story 10.14 follow-ups: scan capture and review after device session B2"
- **Mode:** Batch
- **Scope:** Minor (planning annotations only, no new stories)
- **Product owner decisions:** hebelmx, at device session B2 (accepted 2026-11-16), recorded in #164:
  1. The epics.md AC for Story 10.14 "still says the faces involved are re-prompted. The PO decision is to re-prompt only when at most 2 faces are involved, and otherwise go to review with the best-fitting turns."
  2. Exposure (automatic step-down plus a manual slider as backup) stays deferred to a later story. It is not part of this change.

## 1. Issue summary

In device session B2, a pale stickerless cube produced no valid assignment, and the scan re-prompted 5 of the 6 faces with "white on top". That was no help, because the problem was misread colors, not turns. The PO decided that the scan re-prompts only when at most 2 faces are involved. With more, it goes straight to the Story 10.3 review with the best-fitting turns, and the Learner corrects the stickers there.

The code already does this. `ScanAssembler.MAX_REPROMPT_FACES = 2` (`core/domain/src/main/kotlin/dev/cubentia/domain/recognition/ScanAssembler.kt:208–217`), with branches `NO_VALID_REPROMPT` and `NO_VALID_REVIEW`. The planning text does not mention the cap yet: epics.md:2218 says the scan "re-prompts only the faces involved", with no limit. #164 asks for the annotation at the next correct-course.

**How far the cap reaches:** it applies only when **no** assignment is valid ("best-fitting turns" means the assignments with the most legal pieces, which exist only in that case). When two or more valid states fit (true ambiguity), the faces that differ are always re-prompted. The KDoc at `ScanAssembler.kt:211–216` records this as part of the PO 2026-11-16 decision, and Story 10.14 shipped and was accepted that way. Faces whose center read disagrees with their prompt count toward the limit of 2.

## 2. Impact analysis

| # | Item | Status | Note |
|---|---|---|---|
| 1 | Trigger understood | [x] | #164's last device-session item. The decision was made and built in B2. Only the text lags. |
| 2 | Epic impact | [x] | Epic 10, Story 10.14 (done): the AC is annotated, not rewritten. FR-21 inventory line annotated. No epic is added, removed or resequenced. |
| 3 | PRD | [x] | FR-21 consequence (prd.md:480) says it "asks again for only the faces involved". Annotated with the cap. "It never guesses" still holds, because the review blanks the stickers that differ across the tied best fits and the Learner confirms. |
| 4 | Architecture | [x] | AD-9 assembly bullet (ARCHITECTURE-SPINE.md:256) says the faces involved are reported for a re-prompt. Annotated with `MAX_REPROMPT_FACES`. No new decision. |
| 5 | UX documents | [x] | EXPERIENCE.md:96 (Scan viewfinder row) annotated. DESIGN.md has no rule on this. UX-DR15 in epics.md ("wrong orientation → re-prompt not error") is still correct and is not changed. |
| 6 | Code | [N/A] | Already built (Story 10.14, PR #162). The code items in #164 (system Back on review, coach mark, Activity-level tests, `repromptFaces` center-disagreement scoping, TalkBack update cue) are a separate build. |
| 7 | Path forward | [x] | Direct adjustment |
| 8 | Sprint status | [N/A] | No story added. Story 10.14 stays `done`. |

Other hits that were checked and left alone: `docs/scan-accuracy.md` counts "needs re-prompt" through `ScanAssembler.repromptFaces`, so the cap is already applied there. The epics.md:1844 Story 10.2 annotation is about prompt copy only.

## 3. Recommended approach

**Direct adjustment.** Annotate the four planning texts so they match the PO decision and the shipped code. Effort: small. Risk: none, because the text is now aligned with the behavior already accepted on a device. Timeline: no impact.

## 4. Detailed change proposals (applied)

### Epics: epics.md

**Story 10.14, no-valid / ambiguous AC (line 2218)**, with an annotation appended at the end of the AC:

- OLD (end): "… and if the state is still invalid the result goes to Story 10.3 review as today, with no further loop"
- NEW (end): "… with no further loop. *(SCP 2026-11-19 scan re-prompt cap, #164: PO decision at device session B2, 2026-11-16. When no assignment is valid, the scan re-prompts only when at most 2 faces are involved, counting faces whose center read disagrees; with more, it re-prompts nothing and goes straight to Story 10.3 review with the best-fitting turns (the assignment with the most legal pieces), the faces that differ across the tied best fits left blank. So many faces point to misread colors, which a named top can't fix. Two or more valid states still re-prompt the faces that differ, as built: `ScanAssembler.MAX_REPROMPT_FACES`.)*"
- Rationale: PO decision 1. The original AC text is kept as the record.

**FR-21 inventory (line 57)**

- OLD: "… and the faces involved re-prompted with a named top when it can't be (SCP 2026-11-16); …"
- NEW: "… and the faces involved re-prompted with a named top when it can't be (SCP 2026-11-16; with no valid state, only when at most 2 faces are involved, else straight to review with the best-fitting turns, SCP 2026-11-19, #164); …"

### PRD: prd.md (via correct-course, per AGENTS.md)

**FR-21 consequence (line 480)**

- OLD: "… it asks again for only the faces involved, naming the side to put on top. It never guesses. (SCP 2026-11-16)"
- NEW: "… It never guesses. (SCP 2026-11-16) When no rotation fits and more than 2 faces are involved, it asks again for none of them: the colors were probably misread, so it goes straight to review with the best-fitting rotations, where the Learner corrects the stickers (SCP 2026-11-19, #164)."
- Frontmatter `updated:` 2026-11-14 → 2026-11-19.

### Architecture: ARCHITECTURE-SPINE.md (via correct-course, per AGENTS.md)

**AD-9 assembly bullet (line 256)**

- OLD (end): "… it reports the faces involved for a re-prompt with a named top and never guesses (Story 10.14, SCP 2026-11-16)."
- NEW (end): "… (Story 10.14, SCP 2026-11-16). With no valid state it reports them only when they are at most `MAX_REPROMPT_FACES` (2); more go to review with the best fit and no re-prompt (SCP 2026-11-19, #164)."

### UX: EXPERIENCE.md

**Scan viewfinder row (line 96)**

- OLD: "… it asks for that face again and names the side to put on top: a re-prompt, not an error."
- NEW: "… a re-prompt, not an error. When no rotation fits and more than 2 faces would need asking again, it asks for none and goes straight to review with the best-fitting rotations, since the colors were probably misread (SCP 2026-11-19, #164)."
- Frontmatter `updated:` is already 2026-11-19.

### Memlogs (appended with `uv run _bmad/scripts/memlog.py append`)

- PRD `.memlog.md`: the PO decision (by user) and the FR-21 change.
- UX `.memlog.md`: the EXPERIENCE.md Scan viewfinder row change.
- Architecture `.memlog.md`: the AD-9 assembly bullet change.

## 5. Implementation handoff

- **Scope:** Minor. These are planning text changes only, and the behavior is already built and accepted on a device.
- **#164 next:** a build of the remaining code items: a `BackHandler` on Scan review that uses the Close discard rule, hiding the coach mark once nothing needs fixing (not counted as seen), Activity-level tests for the re-prompt round, `FrameReader.locate`, `ScanDiagnostics.onFrame` and coach-mark persistence, `repromptFaces` limiting center-disagreeing faces to faces with no prompted top, and `clearAndSetSemantics {}` on the update cue. The build must keep the `MAX_REPROMPT_FACES = 2` behavior described here.
- **Not in this change:** exposure step-down and the manual slider (PO-deferred). The solid single-color face check and the Galaxy correction count belong to the Story 10.8 matrix and Story 10.4 calibration.
- **Success criteria:** the planning texts and `ScanAssembler` describe the same rule, and no planning line implies an unlimited re-prompt when no state is valid.
