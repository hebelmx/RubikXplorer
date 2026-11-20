# Sprint Change Proposal: retake one or more faces from the scan review (2026-11-19)

**Status: Approved by the product owner 2026-11-19 (decisions 1–9). The planning edits in section 4 are applied.**

- **Trigger:** GitHub issue #235, "Sticker review: retake one or more faces from the review screen" (split from #158, Story 10.2 follow-ups; PO idea from device session B)
- **Mode:** Batch
- **Scope:** Moderate (one new story added to Epic 10; planning text only, then a build of the new story through `/bmad-orchestrate`)
- **Product owner decisions:** hebelmx, 2026-11-19:
  1. **Selection.** A *Retake faces* button switches the review net into a retake mode, where tapping a face toggles it for retake (any 1–6 faces). A *Retake N faces* action starts the viewfinder for just those faces. Outside that mode, taps keep editing stickers as today.
  2. **Prompting.** Retaken faces use the Story 10.14 any-rotation prompt (the center sticker names the face), not the named-top re-prompt.
  3. **Edits.** Manual sticker edits on kept faces are preserved. A retaken face's stickers, and any edits on it, are replaced by the new capture.
  4. **Scope.** Guided 6-face Scan review only in v1.0. Retake in the Quick Scan review is a v1.1 follow-up noted alongside #198 (Quick Scan is OFF in v1.0).
  5. **Delivery.** The new story is built later through `/bmad-orchestrate` as its own story. This proposal adds it to the plan and implements no code.
  6. **Copy (review round).** Retake-mode instruction line "Tap each face to scan again."; *Cancel* leaves retake mode and clears the selection; singular "Retake 1 face"; TalkBack for a selected face "Green face, selected for retake" (the color varies).
  7. **Backing out (review round).** Leaving the retake viewfinder before every selected face is captured reopens review exactly as before, with nothing replaced (all or nothing).
  8. **Close (review round).** A completed retake counts as a change, so *Close* asks "Discard this scan?".
  9. **No re-prompt (review round).** When the retaken faces' turns are ambiguous or none is valid, review reopens with the best-fitting turns and the usual validation messages, with no named-top re-prompt.

## 1. Issue summary

On the sticker review screen (Story 10.3), a Learner whose scan misread one face has two ways out: fix that face sticker by sticker, or close the review and scan all six faces again. When a whole face is wrong (glare, the wrong face held, a blurred capture), neither is good. The PO asked in device session B for a third way: pick the bad faces on the review net, retake only those in the Guided Scan viewfinder, and come back to review with the other faces as they were.

The plan has no story for it. `epics.md:2200` (Story 10.14, SCP 2026-11-16) says "Per-face retake from review is a separate story." Story 10.3 (`epics.md:1866–1890`, done) has no retake action, and the EXPERIENCE.md "Sticker review net" row (`EXPERIENCE.md:97`) offers only tap-a-sticker-then-a-color and *Close* ("Discard this scan?"). The PRD already allows it: "When a state is invalid, the app names the likely bad stickers … and leads to correction or a rescan" (`prd.md:495`, FR-23).

## 2. Impact analysis

### Checklist

| # | Item | Status | Note |
|---|---|---|---|
| 1.1 | Trigger | [x] | #235, PO idea from device session B; planning hit `epics.md:2200` |
| 1.2 | Problem type | [x] | New requirement from the stakeholder (PO) |
| 1.3 | Evidence | [x] | Issue #235; Story 10.14 note; Story 10.3 ACs; EXPERIENCE.md:97 |
| 2.1 | Current epic | [x] | Epic 10 can still finish as planned. Stories 10.3, 10.14 and 10.15 are done and keep their ACs. |
| 2.2 | Epic changes | [!] | Add Story 10.16 to Epic 10 and note it in the Epic 10 intro. |
| 2.3 | Later epics | [x] | Epic 11: Quick Scan review retake is deferred to v1.1 with #198. Placement (Story 11.2) and Quick Check (Story 10.7) do not change; Placement's Guided 6-face Scan review gets retake for free when its flag rises, since it uses the same review. |
| 2.4 | Obsolete or new epics | [N/A] | None |
| 2.5 | Order | [x] | Story 10.16 depends on 10.3 and 10.14 (both done). It does not block Story 10.11 (raise a camera flag). |
| 3.1 | PRD | [N/A] | FR-23 / `prd.md:495` already says "correction or a rescan"; a per-face rescan is a rescan. FR-14 validation still gates *Accept*. MVP (v1.0, camera OFF) is unchanged. |
| 3.2 | Architecture | [N/A] | AD-9 holds: `ScanAssembler` stays the only place samples are rotated; the review still returns `ScanOutcome {Accepted, Cancelled, FellBackToEditor}`, and only the caller dispatches it. AD-3: the scan flow stays outside any session. Camera code stays in `app/src/camera/` (AD-13). No new port, entity or `SessionEvent`. |
| 3.3 | UX | [!] | EXPERIENCE.md "Sticker review net" row gains the retake mode; "Scan viewfinder" row notes the retake run. DESIGN.md "Sticker review net" gains the retake-mode visuals (selected face, button). Copy set by PO decision 6. |
| 3.4 | Other artifacts | [!] | `sprint-status.yaml` needs `10-16-retake-faces-from-the-scan-review: backlog` (this workflow does not touch it; the orchestrator or `bmad-sprint-planning` adds it). Learner copy goes in `core/domain/src/main/resources/content/strings/en.json` at build time. |
| 4 | Path forward | [x] | Direct adjustment: one new story |

### Interaction with Story 10.15 and the already-solved path

Retake happens before *Accept*, inside the scan sub-flow. When review reopens after a retake, it is the same Story 10.3 review: *Accept* stays disabled until every uncertain sticker is resolved and the state is valid, and *Accept* then follows Story 10.15 unchanged (solve and Solution Playback, or the `AlreadySolved` state for a solved cube, or `FellBackToEditor`). No Story 10.15 AC changes. This needs no PO decision.

### Technical notes for the build (not ACs)

- Today `GuidedScanFlow.reviewOf` builds the review with `ScanReview.fromSamples(samples, saved)`, so review state is derived from the six captured samples. Retake needs review state that keeps the kept faces' current stickers (with the Learner's edits) and replaces only the retaken faces. The assembly for the retaken faces tries their four quarter turns (4^k, k = retaken faces) with the kept faces held fixed, inside `ScanAssembler` (AD-9).
- The Quick Check flow already re-captures a subset of faces (`GuidedScanFlow.quickCheckRetakes`, PO 2026-11-17); the controller's subset-of-faces capture can be reused.
- Self-calibration from the retaken samples must not reclassify kept faces (PO decision 3 / done-when 3).

## 3. Recommended approach

**Direct adjustment.** Add Story 10.16 to Epic 10 and the retake mode to the UX documents. Stories 10.3, 10.14 and 10.15 (done) keep their ACs; 10.14 gets a pointer to the new story.

- **Effort:** Medium. Review-state rework (keep edited faces, replace retaken ones), a retake mode on the review net, a subset-of-faces run of the viewfinder, assembly with fixed faces, JVM tests, route tests, one device session.
- **Risk:** Low to medium. All camera code stays behind `CAMERA_SCAN` (OFF in release). The main risk is review state losing kept edits, which JVM tests cover.
- **Timeline:** No effect on v1.0 (camera flags OFF). Built before Story 10.11 raises `CAMERA_SCAN`, or after it, as the PO prefers.

## 4. Detailed change proposals (applied)

### Epics: epics.md

**Epic 10 intro (line 1804), append after the last SCP note**

- OLD: "… *(SCP 2026-11-16 main-app entry points: … 10.15 runs right after 10.5.)*"
- NEW: "… *(SCP 2026-11-16 main-app entry points: … 10.15 runs right after 10.5.)* *(SCP 2026-11-19 review face retake, #235: Story 10.16 (retake faces from the scan review) runs after 10.14.)*"

**Story 10.14, Depends-on note (line 2200)**

- OLD: "*(SCP 2026-11-16: PO idea from device session B. Per-face retake from review is a separate story.)*"
- NEW: "*(SCP 2026-11-16: PO idea from device session B. Per-face retake from review is a separate story: Story 10.16, SCP 2026-11-19, #235.)*"

**New Story 10.16, inserted after Story 10.15 (before "## Epic 11", now epics.md:2264–2310)**

- OLD: no story.
- NEW: Story 10.16 "Retake faces from the scan review" with ACs for: entering retake mode (copy per decision 6, editing off, *Cancel*); a viewfinder run for only the selected faces with the any-rotation prompt; review reopening with kept faces and edits unchanged and retaken faces replaced; assembly with kept faces fixed and no re-prompt on ambiguity (decision 9); all-or-nothing back-out (decision 7); *Close* asking after a retake (decision 8); *Accept* unchanged through Story 10.15; content-string copy and 200% font; device acceptance on the main app build.
- Rationale: PO decisions 1–9; #235 done-when 2 and 3.

### UX: EXPERIENCE.md

**Component Patterns, "Sticker review net" row (line 97)**

- OLD: ends "*Close* asks "Discard this scan?" only if the Learner changed a sticker."
- NEW: adds a "**Retake faces (SCP 2026-11-19, #235)**" paragraph (retake mode, copy, kept faces unchanged, best fit with no re-prompt, all-or-nothing back-out, not in Quick Scan review in v1.0), and the last sentence reads "… only if the Learner changed a sticker or completed a retake."

**Component Patterns, "Scan viewfinder" row (line 96)**

- NEW: appends "A retake from review asks only for the faces chosen, with the same any-rotation prompt and no re-prompt (SCP 2026-11-19, #235)."

**Frontmatter:** `updated:` stays 2026-11-19.

### UX: DESIGN.md

**Components, "Sticker review net" (lines 265–271), add a bullet**

- NEW: "- *Retake mode* (SCP 2026-11-19, #235): *Retake faces* is an outlined button beside *Close*. In retake mode a selected face gets the solid `accent` outline around the whole face (static, no pulse) and a small ✓ badge; unselected faces stay as they are. The uncertain-sticker rings and the next-sticker pulse are hidden. *Retake N faces* is the primary filled button, disabled with no face selected, and *Cancel* is an outlined button beside it."
- **Frontmatter:** `updated:` 2026-11-16 → 2026-11-19.

### PRD, Architecture

No change (section 2, items 3.1 and 3.2).

## 5. Implementation handoff

- **Scope:** Moderate. One story added to the backlog.
- **Done here:** section 4 applied to `epics.md`, `EXPERIENCE.md` and `DESIGN.md`. **Still to do:** add `10-16-retake-faces-from-the-scan-review: backlog` to `sprint-status.yaml` (orchestrator or `bmad-sprint-planning`).
- **Build:** Story 10.16 through `/bmad-orchestrate` (Codex implements, agy gates), on a feature branch, camera flags OFF in release.
- **Issue #235:** stays open until Story 10.16 is done; its done-when 1 is met when this proposal merges.
- **Success criteria:** #235 done-when 2 and 3 pass on the Lenovo and the S24 FE in a device session on the main app build.

## 6. Open questions

None. Q1–Q4 of the draft were answered by the PO on 2026-11-19, all as proposed (decisions 6–9).
