---
title: 'Sprint Change Proposal: Quick Scan fallback card when the sticker detector is unavailable (#390), and the Review and Correct action recorded in planning'
date: 2026-11-26
status: drafted
mode: batch
trigger: 'GitHub issue #390; sprint-status action item epic-10-deferred-10.32-planning-lacks-review-and-correct'
scope: Minor
---

# Sprint Change Proposal: Quick Scan fallback card when the sticker detector is unavailable

**Bottom line.** When the sticker detector behind the continuous Quick Scan cannot run, the screen today says "Bring the cube into view." for ever. This proposal defines the missing Learner-facing state: a card that offers *Scan face by face* and *Enter colors instead* and keeps the faces already mapped. It also writes the *Review and Correct* action, which shipped with Story 10.32 (the continuous Quick Scan screen, PR #383), into the planning documents. Planning only: two documents are edited, no code, no new story.

- **Trigger:** GitHub issue #390 (a disabled detector looks like "no cube in view" instead of offering the manual fallback). The issue body was read from the orchestrator's snapshot; no `gh` call was made.
- **Also included (PO decision 4):** sprint-status action item `epic-10-deferred-10.32-planning-lacks-review-and-correct` (the planning documents do not describe *Review and Correct*, the keep-only-fully-read-faces hand-over rule or the two-pane layout from 600 dp).
- **Mode:** Batch.
- **Scope:** Minor. Two planning documents amended (`epics.md` Story 10.32, `EXPERIENCE.md`); no PRD or architecture change; no new story; one code fix handed to the Developer agent.
- **Status:** drafted. The product decisions below are made; the text of this proposal awaits the PO's approval. The edits in section 4 are applied on branch `docs/scp-2026-11-26-detector-unavailable-fallback`.
- **Product owner decisions already made (hebelmx, 2026-11-26):**
  1. When the detector is unavailable, the continuous Quick Scan screen shows a new card offering both manual paths, *Scan face by face* and *Enter colors instead*. Faces already mapped are kept on hand-over, under the same hand-over rules as the existing buttons.
  2. The card copy (`en.json`) is exactly: "Quick Scan isn't available right now. You can scan face by face or enter your cube's colors by hand."
  3. No retry after the detector has latched disabled. Reopening Quick Scan in the same app process goes straight to the fallback card. The detector is tried again only after the app process restarts.
  4. The *Review and Correct* tracker item is folded into this proposal.
  5. One bad camera frame (issue #389, fixed separately) must not count as "detector unavailable".
  6. After this proposal merges, issue #390's code is built as a fix on its own branch, not as a new story.

## 1. Issue summary

### 1.1 The detector-unavailable state (#390)

Story 10.31 (the LiteRT sticker-center detector) put the model behind the `StickerCenterDetector` seam. The seam returns one thing per frame, a `StickerCenterDetection` (a list of sticker centers), and has no way to say "I cannot run".

Evidence in the code on this branch (`app/src/camera/continuous/kotlin/dev/cubentia/app/camera/continuous/LiteRtStickerCenterDetector.kt`):

- **Model load failure or LiteRT runtime missing:** `ensureSession` catches the error, logs it and returns null. `initAttempted` stays true, so every later frame returns `StickerCenterDetection.EMPTY`.
- **Tensor contract mismatch:** `openLiteRtSession` returns null, with the same result.
- **Inference failure:** `runInference` catches the error and calls `latchDisabled()`; every later frame returns `EMPTY`.
- The detector is a `@Singleton`, so the disabled state already lasts for the whole app process.

`ContinuousScanPipeline` feeds the empty detection to the mapper, whose cue for "nothing seen" is `msg.quick-scan.continuous.bring-into-view`. The Learner is told to bring the cube into view although no cube will ever be seen.

History: the Story 10.31 review deferred this as tracker item `epic-10-deferred-10.31-detector-unavailable-looks-empty`. The Story 10.32 spec deferred it again (deferred item 2), because the fix "adds a state to the seam and a Learner-facing behaviour that Story 10.32's acceptance criteria do not define". The Story 12.18 triage (the action-item sweep before the camera release) rated it release-blocking for `QUICK_SCAN` at BETA, and the PO chose to fix it (2026-11-25). It must merge before the Story 10.33 physical-device matrix.

**Issue type:** a gap in the original requirements. Story 10.32's cue list covers "all mapper states", and "detector unavailable" is not a mapper state. The UX Quick Scan capture row and the State Patterns table have no such state either. The only related card, "The camera isn't available right now. You can enter your cube's colors by hand." (`msg.scan.camera-unavailable`, `en.json:748`), belongs to a camera that cannot be bound; it offers *Enter colors instead* only and opens a blank Editor.

### 1.2 Review and Correct is not in the planning documents

After the first hands-on device test of Story 10.32 the PO added a one-way *Review and Correct* action (clarification Q8, 2026-11-25). It shipped in PR #383 and exists only in the implementation spec (`_bmad-output/implementation-artifacts/spec-10-32-continuous-quick-scan-live-viewfinder-growing-net-guided-fal.md`, deferred item 6). The same story settled two more rules the planning documents do not show: *Scan face by face* keeps only faces that are fully read (PO clarification Q5), and the screen goes two-pane from 600 dp width. Story 10.33's device matrix and later readers of the epic or the UX specification cannot see any of them.

**Issue type:** a requirement that emerged from the PO during implementation and was never written back.

## 2. Impact analysis

| Checklist item | Status | Finding |
| --- | --- | --- |
| 1.1 Triggering story | [x] | Story 10.32 (continuous Quick Scan screen, done, PR #383), over the seam built in Story 10.31. |
| 1.2 Core problem | [x] | Section 1. A requirements gap (#390) and an unrecorded PO addition (*Review and Correct*). |
| 1.3 Evidence | [x] | Code paths and spec items quoted in section 1; issue #390; tracker items named above. |
| 2.1 Current epic | [x] | Epic 10 (Scan my cube) can be completed as planned. Story 10.32 stays done; its acceptance criteria gain two blocks and two annotations. |
| 2.2 Epic-level changes | [N/A] | No epic added, removed or redefined. |
| 2.3 Remaining epics | [x] | Story 10.33 (device matrix) reads Story 10.32 and now sees *Review and Correct* and the hand-over rule. The #390 fix and the #389 fix both merge before it. No other epic is affected. |
| 2.4 New or obsolete epics | [N/A] | None. |
| 2.5 Order and priority | [x] | Unchanged: #389 and #390, then 10.33 → 10.34 → 10.35 → 10.36 → 10.11. |
| 3.1 PRD | [N/A] | **No edit needed.** FR-22 (Quick Scan) already says the Learner can switch to *Scan face by face* at any time with every already-confirmed face kept, and FR-14 says the Editor is the fallback whenever the camera is unavailable. The new card makes both hold when the detector fails. "Already-confirmed face" is now defined in Story 10.32 and the UX row (section 4), which matches FR-22's wording. |
| 3.2 Architecture | [N/A] | **No edit needed to `ARCHITECTURE-SPINE.md`.** AD-9 (recognition boundary) names "an optional `@BindsOptionalOf` `StickerCenterDetector` seam" and does not fix the seam's shape, so adding an unavailable state does not contradict it. The condition is that the signal stays inside the camera adapter (`app/src/camera/`) and never enters `:core:domain` types: AD-9 lets only sticker-center coordinates, per-cell color statistics and quality scalars cross into `:core:domain`, and Story 10.32 forbids changing `ContinuousFaceletMapper`. AD-18 (feature flags, optional binding) is unchanged: the seam type already lives in base camera code and stays unbound when `QUICK_SCAN` is OFF. If the fix finds it cannot meet this condition, it stops and raises it; it does not edit the spine. |
| 3.3 UX | [!] | `EXPERIENCE.md`: the Quick Scan capture row gains the unavailable state, *Review and Correct*, the confirmed-face rule and the two-pane rule; State Patterns gains a "Quick Scan unavailable" row. `DESIGN.md`: no edit; the card reuses the existing scan card pattern and needs no new token. |
| 3.4 Other artifacts | [!] | `en.json` gains one string (in the fix). `sprint-status.yaml`: two tracker items close (section 5; not edited by this proposal). `docs/ARCHITECTURE.md` (reader-facing overview) is checked by the fix where it describes the seam. |
| 4.1 Direct adjustment | [x] Viable | Chosen. Effort low, risk low. |
| 4.2 Rollback | [ ] Not viable | Nothing to revert; Story 10.32 works as specified. |
| 4.3 MVP review | [ ] Not viable | Not needed; v1.0 scope is unchanged. |
| 4.4 Path selected | [x] | Direct adjustment (section 3). |
| 5.1–5.5 Proposal parts | [x] | Sections 1–5. |
| 6.1–6.2 Review | [x] | Every applied edit was checked against the spec and the code on this branch. |
| 6.3 PO approval | [!] | Decisions 1–6 are made. Approval of this text is pending. |
| 6.4 sprint-status.yaml | [!] | No story or epic entry changes. Two action items close (section 5); the orchestrator applies them. |
| 6.5 Handoff | [x] | Section 5. |

## 3. Recommended approach

**Direct adjustment.** Amend Story 10.32's acceptance criteria and the UX specification, then build #390 as a fix on its own branch (PO decision 6). No new story: the change is one state on one screen plus a signal on an existing seam, and the acceptance text now lives in Story 10.32.

- **Effort:** low. Planning: two documents (done here). Code: one signal on the seam, one card on the continuous screen, one `en.json` string, and tests.
- **Risk:** low. The live scan path is untouched while the detector works. The main risk is treating a transient failure as permanent; PO decision 5 and the #389 fix rule that out.
- **Timeline:** no change. #389 and #390 merge before the Story 10.33 device matrix.

### Rules taken from existing rules, not new decisions

The six PO decisions do not spell out the points below. Each follows from a rule that Story 10.32 or the UX specification already states. They are listed so the PO can see them at approval:

| Point | Rule applied | Source |
| --- | --- | --- |
| The card replaces the live scan (preview, outlines, cue line, *Reset*, torch control, *Review and Correct*). | Issue #390: the screen "leaves the live scan"; decision 1: the card offers the two manual paths. | #390, decision 1 |
| *Close* and system Back on the card: leave at once with 0 mapped sides, ask "Discard this scan?" with 1 or more. | The Quick Scan screen's existing Close rule; no exception is added. | Story 10.32 AC2, UX Quick Scan capture row |
| The torch is turned off when the card shows. | The torch goes off on every hand-over and on leaving the live scan, and the card has no torch control. | Story 10.32 AC2 |
| Back from the Guided hand-over opened from the card returns to the card, map intact. | Back from the hand-over returns to Quick Scan with the map intact (Story 10.17); Quick Scan then shows the card again (decision 3: no retry). | Story 10.32 AC2, decision 3 |
| TalkBack announces the card's text when it appears. | The row already says TalkBack announces the cues; the card's text stands where the cue line was. | UX Quick Scan capture row |

## 4. Detailed change proposals (applied on this branch)

### 4.1 `epics.md`, Story 10.32 (continuous Quick Scan screen)

**4.1.1 Front matter.** `updated: 2026-11-24` → `updated: 2026-11-26`.

**4.1.2 Acceptance criterion 2 (controls), the *Scan face by face* clause.**

OLD:

> *Scan face by face* keeps every confirmed face and opens Guided 6-face Scan for only the missing faces assembled via `ScanAssembler`, with Back returning to Quick Scan with confirmed faces intact (Story 10.17); *Enter colors instead* seeds …

NEW (annotation added after "(Story 10.17)"):

> … (Story 10.17) *(SCP 2026-11-26 detector-unavailable-fallback, as built in PR #383: a face is confirmed, and kept, only when all nine of its stickers are read and none is in conflict; Guided asks for every other face, all six before the map is anchored, and never reclassifies a kept face. The mapper reads non-center stickers only after it has seen all six centers, so today a face is kept only once all six sides are mapped.)*; *Enter colors instead* seeds …

Rationale: this is the rule the PO settled in Story 10.32 (clarification Q5) and `ContinuousQuickScanFlow.keptForGuided` implements. It also defines "confirmed face" for the unavailable card's hand-over.

**4.1.3 New acceptance criterion: *Review and Correct*** (inserted after criterion 2).

OLD: (none)

NEW:

> **Given** a scan in progress that is not complete *(SCP 2026-11-26 detector-unavailable-fallback: records the "Review and Correct" action the PO added on 2026-11-25 (clarification Q8) and this story shipped in PR #383; described as built)*
> **When** more than half of the six sides are mapped and more than half of the 54 stickers are set (two named fractions of 0.5 held in one place; today N ≥ 4 and at least 28 stickers), also while a layer twist is latched
> **Then** an outlined *Review and Correct* action (label from `en.json`) shows between *Scan face by face* and *Enter colors instead* and is never the emphasized action; when either limit is not met, before the map is anchored, and during the completion hold it is hidden (not drawn, not tappable, no TalkBack node) while its place stays reserved, so no other action moves; it appears and hides with no animation, haptic or announcement. A tap stops the analysis, turns the torch off and opens Story 10.3 Scan Review on a fresh export: confirmed stickers as read, unread and conflicted stickers blank, nothing guessed, the conflicted ones first to check (an export that is already complete opens the completed scan's review). In that review per-face retake, suspect ranking, Paint mode, *Enter colors instead* and the accept rules are the existing ones, and *Accept* finishes the scan (`ScanOutcome.Accepted` to the Solve tab). The action is one-way: system Back returns at once, with no question, to the live scan with the camera-built map, the count and any latched twist as they were and the torch off, and nothing done in that review (colors set, per-face retakes) is kept; *Close* there always asks "Discard this scan?"; the live scan never holds a Learner correction and `ContinuousFaceletMapper` is unchanged

Rationale: closes tracker item `epic-10-deferred-10.32-planning-lacks-review-and-correct`. Every clause comes from the spec's "Review and Correct (Q8)" block or from the code on this branch (`ContinuousQuickScanFlow.reviewAndCorrectOffered`, `ContinuousQuickScanScreen`, `GuidedScanViewModel.reviewAndCorrect` and `backOutOfEarlyReview`). Where the spec and the last commit differ, the code wins: the hidden action keeps its place (commit `bf87a1f4`), while the spec's earlier text only said "hidden".

**4.1.4 New acceptance criterion: detector unavailable** (inserted after 4.1.3).

OLD: (none)

NEW:

> **Given** the sticker-center detector has latched itself disabled: its model failed to load, the model did not match the tensor contract, or an inference failed *(SCP 2026-11-26 detector-unavailable-fallback, `#390`, PO decisions 2026-11-26; built as a fix on its own branch after that proposal merges, and this story stays done; closes `epic-10-deferred-10.31-detector-unavailable-looks-empty`)*
> **When** Quick Scan is open, or is opened again in the same app process
> **Then** the `StickerCenterDetector` seam reports "unavailable" as a state of its own, never as an empty detection, and the signal stays inside `app/src/camera/` (it never enters `:core:domain` types), so AD-9's sample boundary, the AD-18 optional binding and `ContinuousFaceletMapper` are unchanged; the screen leaves the live scan and shows a card in its place with the `en.json` text "Quick Scan isn't available right now. You can scan face by face or enter your cube's colors by hand." and exactly two actions, *Scan face by face* and *Enter colors instead*, which keep the faces already mapped under the same hand-over rules as the buttons above (Back from that Guided hand-over returns to the card with the map intact); "Bring the cube into view." is never shown for this state; the torch is turned off, *Close* / Back keep the rule above, and TalkBack announces the card's text when it appears; the detector is not tried again until the app process restarts, so reopening Quick Scan goes straight to the card; one bad camera frame (`#389`, a frame skipped because its input fill or peak decoding failed) never counts as unavailable; Guided 6-face Scan and Case capture are unaffected and their existing tests pass; all copy comes from `en.json` (`checkLearnerText` passes); and tests show the model-load-failure path and the inference-failure path each reaching the unavailable state, and a screen or route test showing the card instead of the bring-into-view cue

Rationale: PO decisions 1, 2, 3 and 5, issue #390's "Expected" list, and the derived rules of section 3.

**4.1.5 Acceptance criterion 5 (tests and layout).**

OLD:

> … the screen fits a phone in portrait without scrolling and at 200% font on Lenovo 600 dp, TalkBack announces side milestones/cues/conflict/completion …

NEW:

> … the screen fits a phone in portrait without scrolling and at 200% font on Lenovo 600 dp, the preview sits on the left and the net, count, cue and actions on the right from 600 dp width (the medium window width class, the same rule as the Guided viewfinder) and in any landscape window *(SCP 2026-11-26 detector-unavailable-fallback, as built in PR #383)*, TalkBack announces side milestones/cues/conflict/completion …

Rationale: the layout rule the story built (`scanTwoPane`: width of 600 dp or more, or width greater than height).

### 4.2 `EXPERIENCE.md`

**4.2.1 Front matter.** `updated: 2026-11-24` → `updated: 2026-11-26`.

**4.2.2 Component Patterns, "Quick Scan capture" row.** Four changes inside the one table cell:

1. Confirmed-face rule. OLD: "(Story 10.17 — backing out of the Guided handover returns to Quick Scan with the confirmed faces intact)". NEW: "(Story 10.17 — backing out of the Guided handover returns to Quick Scan with the confirmed faces intact; a face counts as confirmed only when all nine of its stickers are read and none is in conflict, and Guided asks for every other face — SCP 2026-11-26, as built)".
2. New paragraph before "**Close & Back:**":
   > **Review and Correct (SCP 2026-11-26 detector-unavailable-fallback; added by the PO on 2026-11-25 and shipped with Story 10.32):** while more than half of the six sides are mapped and more than half of the 54 stickers are set (today 4 sides and 28 stickers), also after a layer turn was detected, an outlined *Review and Correct* action shows between *Scan face by face* and *Enter colors instead*; it is never the emphasized action. Otherwise, and during the short hold after all 54 stickers are verified, it is hidden: not drawn, not tappable and skipped by TalkBack, with its place kept empty so the other actions never move. It appears and hides with no animation, haptic or announcement. Tapping it turns the torch off and opens Scan Review before the scan is complete: stickers the scan confirmed are filled, unread and conflicted stickers are blank, nothing is guessed, and the conflicted ones are selected first. Per-face retake, Paint mode and *Accept* work as in any review, and *Accept* finishes the scan. The action is one-way: Back returns at once, with no question, to the live scan with the map, the count and any layer-turn cue as they were and the torch off, and nothing set or retaken in that review is kept; *Close* in that review always asks "Discard this scan?".
3. New paragraph after it:
   > **Unavailable (SCP 2026-11-26 detector-unavailable-fallback, #390):** when the sticker detector cannot run (its model failed to load or did not match, or an inference failed), the live scan is replaced by a card, "Quick Scan isn't available right now. You can scan face by face or enter your cube's colors by hand.", with two actions: *Scan face by face* and *Enter colors instead*. Both keep the faces already mapped, by the same rules as the buttons above; Back from that Guided handover returns to the card. The torch is turned off, *Close* and Back keep the rule below, and "Bring cube into view" is never shown for this state. There is no retry: opening Quick Scan again shows the card at once, until the app process restarts. A single bad camera frame is skipped and never shows the card (#389).
4. Layout and TalkBack. OLD: "Fits a phone in portrait without scrolling and at 200% font on Lenovo 600 dp; TalkBack announces side-count milestones, cues, conflict face, and completion." NEW: "Fits a phone in portrait without scrolling and at 200% font on Lenovo 600 dp; from 600 dp width and in any landscape window the preview sits on the left and the net, count, cue and actions on the right, as in the Scan layout (Responsive & Platform; SCP 2026-11-26, as built). TalkBack announces side-count milestones, cues, conflict face, completion, and the unavailable card's text."

**4.2.3 State Patterns, new row** after "Camera permission denied".

OLD: (none)

NEW:

> | Quick Scan unavailable *(SCP 2026-11-26, #390)* | Quick Scan capture | A card in place of the live scan: "Quick Scan isn't available right now. You can scan face by face or enter your cube's colors by hand." with *Scan face by face* and *Enter colors instead*; faces already mapped are kept (see Quick Scan capture). Shown when the sticker detector cannot run, and at once each time Quick Scan is opened until the app process restarts; never for a single bad camera frame. The copy is a content string; the fix for #390 chooses its key. |

### 4.3 Documents checked and left unchanged

- **`prd.md`:** no edit needed (section 2, item 3.1).
- **`ARCHITECTURE-SPINE.md`:** no edit needed, on the condition stated in section 2, item 3.2.
- **`DESIGN.md`:** no edit.
- **`sprint-status.yaml`:** not edited here (section 5).

### 4.4 Seen and deliberately not written into the planning documents

- **Landscape phone layout.** On a two-pane panel under 480 dp high, *Review and Correct* shares the last row with *Enter colors instead*. The spec records that the implementer chose this and that the PO has not seen it, so it is not made a UX rule here.
- **Calibration saved from an early review** restarts Quick Scan with an empty map (spec deferred items 1 and 5). It is an open product question with its own tracker items, so the new criterion does not mention the calibration offer.
- **The camera-unavailable card** (the camera cannot be bound) still has no State Patterns row, and a camera failure in the middle of a Quick Scan still opens that card and a blank Editor (rejected as rare in the Story 10.32 review). Both predate this change and are outside #390.

## 5. Implementation handoff

**Scope: Minor.** Direct implementation by the Developer agent.

| Who | What |
| --- | --- |
| PO (hebelmx) | Approves this proposal, including the derived rules in section 3. |
| Orchestrator | Merges this branch. Then closes `epic-10-deferred-10.32-planning-lacks-review-and-correct` in `sprint-status.yaml` (resolved by section 4). Closes `epic-10-deferred-10.31-detector-unavailable-looks-empty` when the #390 fix merges. No story or epic entry changes. |
| Developer agent | Builds issue #390 as a fix on its own branch through `bmad-build`, after this proposal and the #389 fix are merged. |

**What the #390 fix must do** (Story 10.32, the new "detector unavailable" criterion):

1. The `StickerCenterDetector` seam exposes an unavailable state once the detector has latched disabled: failed model load, tensor contract mismatch or inference failure. The signal stays in `app/src/camera/`; no `:core:domain` type and no `ContinuousFaceletMapper` code changes. The optional binding (AD-9, AD-18) is unchanged, and a variant with `QUICK_SCAN` OFF still compiles.
2. On that state the continuous Quick Scan screen leaves the live scan and shows the card with the exact copy of decision 2 from a new `en.json` string, with *Scan face by face* and *Enter colors instead*. Both use the existing hand-over rules, so the faces already mapped are kept. The torch goes off; *Close* and Back keep the screen's rule; TalkBack announces the card's text.
3. No retry in the same app process: reopening Quick Scan shows the card at once.
4. One bad frame (#389) never shows the card.
5. Tests: the model-load-failure path and the inference-failure path each reach the unavailable state; a screen or route test shows the card and not "Bring the cube into view."; the existing Guided 6-face Scan and Case capture tests pass unchanged.
6. `./gradlew spotlessCheck build` passes, including `checkLearnerText`.

**Success criteria:** the six points above are met, and both tracker items are closed. Story 10.33's device matrix then starts from a screen whose planning text matches what ships.
