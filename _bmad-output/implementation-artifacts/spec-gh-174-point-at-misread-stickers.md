---
title: 'GH-174 Scan review: point at the likely misread stickers when color counts are off'
type: 'feature'
created: '2026-11-19'
status: 'done'
baseline_revision: '95d27bed6d670da1cd9738abbc8d851e66b29ea1'
review_loop_iteration: 1
followup_review_recommended: false
context:
  - '{project-root}/_bmad-output/planning-artifacts/ux-designs/ux-RubikXplorer-2026-10-29/EXPERIENCE.md'
  - '{project-root}/_bmad-output/implementation-artifacts/spec-gh-199-link-errors-to-net.md'
warnings: [oversized]
deferred:
  - summary: >-
      With suspects, the color-count line still reads "Check the colors you entered." on a camera scan and its TalkBack text lists the ranked guesses as plain "Stickers".
    evidence: |-
      msg.validation.wrong-color-counts is shared with the manual editor and predates #174; the line is now linked and described through msg.scan.problem-description-stickers. Raised by blind-hunter review pass 2.
    location: >-
      core/domain/src/main/resources/content/strings/en.json msg.validation.wrong-color-counts
    severity: low
---

<intent-contract>

## Intent

**Problem:** A 6-face scan (Story 10.7 device session, Lenovo) read all 54 stickers confidently but with 10 yellow and 8 orange; `ScanAssembler` found no valid state (`NO_VALID_REVIEW`) and the review showed only the plain color-count line with nothing outlined, so the Learner had to inspect all 54 stickers (FR-23; EXPERIENCE.md "Sticker review net": messages point at the specific stickers).

**Approach:** Keep each scanned sticker's measured Lab (in net order) and the scan's calibration centers in the `ScanReview`. When counts are off, `:core:domain` ranks suspects (over-count stickers whose Lab is closest to an under-count color's center); the color-count problem then carries the top 1–3 suspects as its stickers, so #199's numbering, tags, outlines and tap-to-select apply to it unchanged. The review pre-selects the top suspect (or, when counts are right but no cube fits, the first conflicting sticker of the first linked problem), and a suggestion line under the selection says "This sticker may be orange — check it." when the selected sticker is a suspect.

## Boundaries & Constraints

**Always:** Ranking is pure Kotlin in `:core:domain` (no Android). Learner text only through content strings in `en.json` (color names may use the existing `colorName(color)` resources lowercased, as `SetStickerLine` does). Wording is a suggestion; nothing is changed for the Learner. A sticker has Lab evidence only if it was scanned and not painted since: `paint` drops that index's Lab; `ScanReview(stickers, selected)` without evidence, `GuidedScanFlow.withCorrections`, `ScanResult.Partial`, and `from(result)` without centers carry none → no suspects, and the count line stays a plain #199 line. Reuse #199's `ReviewProblem` / `problemMarks` / `ProblemLine` mechanism; problem order and reason texts unchanged.

**Never:** No edits under `_bmad-output/planning-artifacts`, `_bmad/`, `.claude/skills/`. No numeric confidence shown to the Learner. No suspects for twist/flip/parity-only failures (they name no piece; showing none beats guessing). No change to ManualCubeEditor, `CubeValidator` rules, `ScanAssembler` branch logic, or Quick Check. No new `SessionEvent`, no persistence. No device check (out of scope).

## I/O & Edge-Case Matrix

| Scenario | Input / State | Expected Output / Behavior | Error Handling |
|----------|--------------|---------------------------|----------------|
| Issue scan (counts off) | issue reads, one yellow with Lab near orange (still CONFIDENT yellow) | `suspects[0]` = that sticker, likely ORANGE; up to 3 suspects, all YELLOW stickers; count problem `positions = piece = suspect indices` in rank order; `selected` = top suspect; suggestion "This sticker may be orange — check it." | — |
| Learner paints top suspect orange | from above | Lab of that index dropped; counts right → no count problem, no suspects | — |
| Counts off, suspect was edited | over-count sticker painted by Learner | that sticker never a suspect; other scanned stickers still ranked | — |
| No Lab evidence | review without evidence, counts off | `suspects` empty; count problem `positions = piece = []` (plain line, as #199) | — |
| Several over/under colors | e.g. +1 Y, +1 R, −1 O, −1 B | candidates = stickers of every over-count color; score = min ΔE to any under-count center; likely = that argmin color | ties: lower sticker index first |
| Counts right, no valid cube | impossible/duplicate piece or PieceCheck repeat | no new suspects; #199 linked problems outlined; `from()` pre-selects first linked problem's first `positions` sticker when no blanks | — |
| Twist / flip / parity only | counts right, pieces legal | nothing outlined, `selected` null (unchanged) | — |
| Blanks present | uncertain stickers | `selected` = first blank (unchanged); count check doesn't run | — |

</intent-contract>

## Code Map

- `core/domain/.../recognition/FaceSample.kt` -- `Lab`, `CalibrationCenters`; reuse.
- `core/domain/.../recognition/StickerClassifier.kt` -- `deltaE` (internal, CIE76) for the ranking.
- `core/domain/.../recognition/ScanAssembler.kt` -- `ScanResult.Full(facelets, confidence)` (:38); `place()` builds `Placement.Placed.readings` rotated by `turns` (~:400); `search()` picks `options[face][first.choice[face]]` (:475) and builds `Full` (:483). Labs must follow the exact same rotation as readings.
- `core/domain/.../recognition/ScanReview.kt` -- class to extend: `problems` maps `CubeValidator.problems` to `ReviewProblem(reason, positions, piece = positions)`; `outlined` = union of `piece`; `paint`/`select`/`equals`; `from(result)` sets `selected = blanks.firstOrNull()`; `fromSamples` has `calibration.calibration`.
- `core/domain/.../cube/PieceCheck.kt` -- `ReviewProblem(reason, positions, piece)`; kdoc "sorted" — count-suspect problem keeps rank order (document it).
- `core/domain/.../cube/CubeValidator.kt` -- `WrongColorCounts(counts)` is returned alone when counts are off.
- `app/src/main/kotlin/dev/cubentia/app/ui/scan/ScanReviewScreen.kt` -- `focusedIndex` default = first linked (:110-111); `SelectedStickerLine` (:431); `ProblemLine` (:307) already handles `piece == positions` via `msg.scan.problem-description-stickers`.
- `app/src/camera/kotlin/dev/cubentia/app/camera/GuidedScanFlow.kt:114-120,194,224` -- production path is `fromSamples`, but **every** finished scan is then published through `GuidedScanActivity.kt:728-733` `publish` → `GuidedScanFlow.withCorrections(review, corrections)` (even with no corrections), which today rebuilds `ScanReview(stickers, firstBlank)` and so drops evidence and pre-selection. It must preserve both. Test: `app/src/testDebug/kotlin/dev/cubentia/app/camera/GuidedScanFlowTest.kt:25`.
- Tests: `core/domain/src/test/.../recognition/ScanReviewTest.kt`, `ScanAssemblerTest.kt` (has `unposed`/`capture` helpers), `app/src/test/.../ui/scan/ScanReviewScreenTest.kt` (Robolectric harness, `w640dp-h960dp`).
- `core/domain/src/main/resources/content/strings/en.json` -- `msg.scan.*` near :715.

## Tasks & Acceptance

**Execution:**
- `core/domain/.../recognition/ScanAssembler.kt` -- add `labs: List<Lab>? = null` to `ScanResult.Full` (net order, rotated like readings); `Placed` carries its Labs; `search` fills it -- evidence reaches review.
- `core/domain/.../recognition/SuspectRanking.kt` (new) -- `data class Suspect(index: Int, likely: Color)`; `ScanEvidence(labs: List<Lab?>, centers: Map<Color, Lab>)`; `rank(stickers, evidence): List<Suspect>` per the matrix, top `MAX_SUSPECTS = 3`, excluding centers and null Labs; empty when counts are right, any blank, or no under-count color -- pure ranking.
- `core/domain/.../recognition/ScanReview.kt` -- optional `evidence: ScanEvidence? = null` constructor param; `suspects`; count problem uses suspect indices; `paint` nulls the Lab at the painted index; `select` keeps evidence; equals/hashCode include evidence; `from(result, centers: CalibrationCenters? = null)` builds evidence from `Full.labs` and pre-selects `blanks.first ?: suspects.first ?: first problem with non-empty positions`; `fromSamples` passes the calibration; add `fun corrected(corrections: Map<Int, Color>): ScanReview` that sets each corrected sticker, drops those indices' Labs (they are Learner edits), keeps the rest of the evidence and re-applies the same pre-selection rule as `from` (share one private helper) -- domain behavior.
- `app/src/camera/kotlin/dev/cubentia/app/camera/GuidedScanFlow.kt` -- `withCorrections` delegates to `review.corrected(corrections)` -- the shipped camera path keeps suspects and pre-selection.
- `core/domain/src/main/resources/content/strings/en.json` -- add `msg.scan.suspect-suggestion`: "This sticker may be {color} — check it." -- content string.
- `app/.../ui/scan/ScanReviewScreen.kt` -- initial `focusedIndex` per Design Notes "Initial focus"; `SuspectLine` under `SelectedStickerLine` when `review.selected` is a suspect: tag `review:suggestion`, polite live region, text from the content key with the lowercased color name -- UI.
- Tests -- `core/domain/src/test/.../recognition/SuspectRankingTest.kt` (new): replay the issue's six unposed reads (pixel order, from the log) via `ScanReview.fromSamples` with synthetic Labs (each color one Lab, also used as centers; one non-center yellow given a Lab between yellow and orange, ≥ 10 ΔE closer to yellow), assert `ScanAssembler.trace` branch `NO_VALID_REVIEW`, the ranked suspects (top = that sticker, ORANGE; 3 suspects, all YELLOW), `selected`, the count problem's positions, and that painting the top suspect orange clears the count problem; plus matrix rows (edited sticker excluded, no evidence, multi-color, ties, centers excluded). `ScanAssemblerTest`: Labs rotate with colors. `ScanReviewTest`: case-2 pre-selection; update any expectation the new pre-selection changes. `ScanReviewScreenTest`: ACs below (put the fixture's Lab comments on the lines they describe). `ScanReviewTest` or `SuspectRankingTest`: `corrected(emptyMap())` keeps evidence, suspects and the top-suspect selection; a correction drops that index's Lab and re-ranks. `GuidedScanFlowTest`: `withCorrections` on an evidence-carrying review with no corrections keeps `evidence`, `suspects` and `selected`; the existing all-blank test still passes.

**Acceptance Criteria:**
- Given a review with evidence whose counts are off, when the screen shows, then line `review:problem:<count line>` carries tag "1", the 1–3 suspect stickers are invalid-outlined with `StickerProblemsKey = [1]`, the top suspect is the selected sticker, and `review:suggestion` reads "This sticker may be orange — check it." as a polite live region.
- Given that screen, when the Learner taps a lower-ranked suspect, then the suggestion names that suspect's likely color; when the Learner selects a non-suspect, then no `review:suggestion` exists.
- Given a review without evidence and counts off, then the count line is unnumbered and not clickable, nothing is outlined by it, and no suggestion shows (#199 behavior).
- Given counts right and an impossible piece, when the review comes from `ScanReview.from`, then the first conflicting sticker of problem 1 is selected and problem 1's tags report focused.
- Given a scan published through `GuidedScanFlow.withCorrections` with no corrections, then the review still carries its evidence, suspects and pre-selected top suspect.
- `./gradlew spotlessCheck build` passes.

## Spec Change Log

### 2026-11-19 — Review pass 1 loopback (bad_spec)
- **Trigger:** blind, edge-case, verification-gap and intent layers all found that `GuidedScanActivity.publish` routes every finished scan through `GuidedScanFlow.withCorrections`, which rebuilt the review without evidence and reset the selection, so suspects, outlines, the suggestion and the new pre-selection never reached the real screen. The Code Map had called that path "fine".
- **Amended:** Code Map entry for `GuidedScanFlow`; Execution adds `ScanReview.corrected(corrections)` and the `withCorrections` delegation; tests add `corrected`/`withCorrections` cases and fix misplaced fixture comments; new AC for the published path; Design Notes "Initial focus" prefers the problem whose first sticker is the selection.
- **Known-bad state avoided:** a feature that passes every unit test but is inert on the device because the publish step drops the evidence.
- **KEEP:** attempt 1 is saved at `spec-gh-174-attempt-1.patch` (this folder); starting from `git apply` of it is fine. Keep `SuspectRanking`/`Suspect`/`ScanEvidence`, `ScanResult.Full.labs` and the assembler rotation, `ScanReview` evidence/suspects/paint/select/equals/`from` pre-selection, the content key, `SuspectLine`, the `fullRead` test helper and every test in it (SuspectRankingTest 7, ScanReviewScreenTest 4 new, ScanReviewTest, ScanAssemblerTest Lab-rotation test).

## Review Triage Log

### 2026-11-19 — Review pass
- verdicts: 23 findings — high 7, medium 0, low 10, false 6, maybe-false 0
- findings:
  - `[high]` `[bad_spec]` vgap: no test covers `publish` → `withCorrections` with an evidence-carrying review (pre-verified gap) — same root cause as the `withCorrections` rows; amendment adds `GuidedScanFlowTest` and `corrected` tests.
  - `[high]` `[bad_spec]` vgap other: `GuidedScanFlow.withCorrections:119` rebuilds the review without evidence — verified: `GuidedScanActivity.publish:728-733` always calls it; amended Code Map, Execution (`ScanReview.corrected`), tests and an AC.
  - `[high]` `[bad_spec]` edge: published reviews drop evidence and pre-selection — same root cause; same amendment.
  - `[low]` `[bad_spec]` edge: top suspect also in an earlier PieceCheck problem opens focus on that problem, not the count line — real (the initial focus took the first linked problem containing the sticker); folded into Design Notes "Initial focus" with the loopback.
  - `[false]` `[reject]` edge: caller mutating a `MutableList` passed in `ScanEvidence.labs` — no caller keeps or mutates the list after construction (`from` passes the assembler's list; `paint` builds a fresh copy).
  - `[false]` `[reject]` edge: first linked problem's first position may be a center — review centers are forced home, PieceCheck covers only corners/edges, so no problem position is a center.
  - `[high]` `[bad_spec]` edge claim: spec called `withCorrections` dropping evidence "fine" — same root cause as above.
  - `[high]` `[bad_spec]` edge claim: pre-selection never reaches the screen — same root cause as above.
  - `[high]` `[bad_spec]` intent (a): the camera-to-screen path drops the evidence — same root cause as above.
  - `[false]` `[reject]` intent (b): twisted pieces not outlined — a pure twist names no piece; the intent asks to reuse #199's linkage and add only what is missing, and to show no suspects rather than guess.
  - `[low]` `[reject]` intent (c): the domain test asserts the likely color, not the sentence — the domain has no Android color names; the sentence is asserted in Robolectric; rewriting the screen fixture on the issue reads adds nothing the two tests don't already prove.
  - `[low]` `[reject]` intent (d): always padding to 3 suspects with tied, exact-color stickers — the intent allows "top 1–3"; a cutoff needs a threshold the intent does not give; two extra outlines still beat 54 stickers.
  - `[low]` `[reject]` intent (e): no-evidence wrong-count scans now pre-select a piece conflict — selects a real conflicting sticker; harmless and follows clause (2)'s rule.
  - `[high]` `[bad_spec]` blind 1: the feature never runs in the real scan flow (`withCorrections`) — same root cause as vgap other; same amendment.
  - `[low]` `[reject]` blind 2: padding to `MAX_SUSPECTS` with no-evidence ties — same reasoning as intent (d).
  - `[low]` `[reject]` blind 3: absolute ΔE favors close color pairs (red/orange) — that confusability is the common misread; the literal intent reading; changing the score is not a direct correction.
  - `[low]` `[reject]` blind 4: lowercased color name inserted into the sentence breaks other languages — only `en.json` exists; same pattern as the existing `msg.scan.sticker-set`.
  - `[low]` `[reject]` blind 5: diagnostics don't log suspects — not asked by the intent; adding logging is new surface.
  - `[low]` `[reject]` blind 6: evidence dropped silently — wrong-size labs are unreachable (assembler always emits 54) and a missing center color is unreachable (`StickerClassifier.isValid` gates any Full); a Partial scan carrying no Labs is real but rare, and ranking around a Learner-entered face would be guessing.
  - `[low]` `[bad_spec]` blind 7: misplaced fixture comments in `ScanReviewScreenTest.suspectReview` — real; added to the amended test task.
  - `[false]` `[reject]` blind 8: scope creep — impossible-piece pre-selection is intent clause (2); dropping the Lab on a same-color paint is the intended "edited" rule; suspects in `outlined` is by design (Design Notes).
  - `[false]` `[reject]` blind 9: focus doesn't follow sticker taps — #199's design: focus follows line taps; the initial-focus case is covered by the edge row above.
  - `[false]` `[reject]` blind 10: `Full.labs` changes equality — intended; every assertion was updated via `fullRead`.

### 2026-11-19 — Review pass (loop 1)
- verdicts: 24 findings — high 0, medium 0, low 16, false 8, maybe-false 0
- findings:
  - `[false]` `[reject]` intent (B): twisted pieces not outlined — carried: a pure twist names no piece; reuse #199 and add only what is missing; show nothing rather than guess.
  - `[false]` `[reject]` intent (C): the counts line becomes numbered — #199 keeps a line plain only because it names no sticker; with suspects it names some (Design Notes "Why the count line becomes linked"); without evidence it stays plain, and a test covers that.
  - `[low]` `[reject]` intent (A2): always three suspects — carried: the intent allows "top 1–3"; a cutoff needs a threshold the intent does not give.
  - `[low]` `[reject]` intent (D): the suggestion sentence is asserted only in Compose — carried: the domain has no Android color names; the domain test asserts the likely color.
  - `[low]` `[reject]` intent: no-evidence wrong counts now pre-select a piece conflict — carried: selects a real conflicting sticker; clause (2)'s rule.
  - `[false]` `[reject]` intent: the correction path re-runs pre-selection — intended by the loopback amendment (Execution `corrected`, new AC); covered by `GuidedScanFlowTest` and `SuspectRankingTest`.
  - `[low]` `[reject]` blind 1: no cutoff, so correct stickers fill the list — carried (same as intent A2).
  - `[low]` `[reject]` blind 2: score ignores the match to the sticker's own color — carried: literal intent reading; changing the score is not a direct correction.
  - `[low]` `[defer]` blind 3: the counts line says "Check the colors you entered" on a camera scan and lists guesses as "Stickers" — the reason text is pre-existing (`msg.validation.wrong-color-counts`), shared with the manual editor; the same wording family as #199's deferred instruction item.
  - `[low]` `[reject]` blind 4: the suggestion is not in the sticker's own TalkBack text — the selection change announces it through the polite live region and each suspect announces "Conflicting sticker in problem 1"; adding sticker semantics is new surface.
  - `[low]` `[patch]` blind 5: painting the last blank with counts off leaves the selection on the painted sticker — real; `paint` now selects the top suspect when no blank remains (domain test added).
  - `[false]` `[reject]` blind 6: `ScanEvidence` not defensively copied — carried: no caller mutates the list after construction.
  - `[low]` `[reject]` blind 7: evidence dropped silently on a size mismatch — carried: unreachable, the assembler always emits 54.
  - `[low]` `[reject]` blind 8: untested ranking edge cases — missing centers are unreachable (`StickerClassifier.isValid`); all-null Labs and short lists fall out of the same `getOrNull`/null filter the edited-sticker test covers.
  - `[low]` `[reject]` blind 9: `withCorrections` is a thin wrapper without updated KDoc or a real-correction flow test — the domain `corrected` test covers a real correction; the wrapper keeps the existing call sites.
  - `[false]` `[reject]` blind 10: the counts problem breaks the sorted-positions rule — carried: documented on `ReviewProblem`; the screen reads `piece == positions`, which holds.
  - `[low]` `[reject]` blind 11: the inserted color name fails in gendered languages — carried: only `en.json` exists; same pattern as `msg.scan.sticker-set`.
  - `[low]` `[patch]` edge 1: initial focus can pick a piece problem whose sorted positions start with the top suspect — deviates from Design Notes "Initial focus"; the initializer now prefers the counts problem first. Grouped with vgap.
  - `[low]` `[patch]` edge 2: painting the last blank never selects the top suspect — same root cause as blind 5; same fix.
  - `[low]` `[reject]` edge 3: focus `rememberSaveable` is not keyed, so a counts line appearing later is not focused — focus then falls back to the first linked problem as #199 does; the suggestion still shows; keying focus to selection changes #199's tap behavior.
  - `[low]` `[patch]` vgap: no screen test where a piece problem precedes the counts line led by the selected suspect (pre-verified) — added a Robolectric test asserting the counts line is focused. Grouped with edge 1.
  - `[false]` `[reject]` vgap other: a same-color paint regains its Lab after a calibration round trip — the activity records only changed colors as corrections, but a same-color paint leaves the scanned color, so its Lab still describes that sticker; no wrong suggestion results.
  - `[false]` `[reject]` intent: TalkBack check is text plus live region, not a merged content description — `SuspectLine` is a plain `Text` with no content description; its text is what TalkBack reads, which is what the test asserts.
  - `[false]` `[reject]` intent: the issue test assumes F pixel 1 is the misread — the log cannot name it; the intent asks for synthetic Lab values, which this is.

## Design Notes

- **Why the count line becomes linked:** #199 keeps a problem plain only because it has no stickers; with suspects it has some, so giving the count problem `positions = piece = suspects` reuses numbering, circle tags, outlines, focus, tap-to-select and the `problem-description-stickers` TalkBack text with no new UI mechanism.
- **Score:** CIE76 ΔE from the sticker's Lab to the nearest under-count color's calibration center (literal reading of the issue). Ranking order is score ascending, then sticker index.
- **"Edited" is per sticker:** painting drops only that sticker's Lab; other scanned stickers can still be suspects.
- **Initial focus:** the linked problem whose first `positions` sticker is the pre-selected one (the count line when the top suspect is selected, even if that sticker also sits in a PieceCheck problem), else the first linked problem containing it, else #199's default.
- **Suggestion follows the selection** ("This sticker" = the selected one): pre-selection makes it name the top suspect on open.

## Verification

**Commands:**
- `export JAVA_HOME=/usr/lib/jvm/java-25-openjdk-amd64 ANDROID_HOME=$HOME/Android/Sdk; ./gradlew :core:domain:test :app:testDebugUnitTest --tests '*ScanReview*' --tests '*Suspect*' --tests '*ScanAssembler*'` -- expected: pass
- `./gradlew spotlessCheck build` -- expected: BUILD SUCCESSFUL

## Auto Run Result

**Summary:** When a scan's color counts are off, the review now ranks likely misreads in `:core:domain` (`SuspectRanking`). Candidates are scanned, unedited, non-center stickers of an over-count color, ranked by CIE76 ΔE to the nearest under-count color's calibration center. The top 1–3 become the stickers of the color-count problem, so #199's numbering, tags, outlines and tap-to-select apply. The top suspect is pre-selected, and a polite live-region line "This sticker may be {color} — check it." shows while a suspect is selected. The scan's Labs flow `ScanAssembler` → `ScanResult.Full.labs` → `ScanReview.evidence` and survive the camera publish step (`withCorrections` → `ScanReview.corrected`). Painting or correcting a sticker drops its Lab. When counts are right but no cube fits, the review pre-selects the first conflicting sticker of #199's first linked problem. Twist, flip and parity alone stay plain, with nothing selected.

**Files changed:**
- `core/domain/.../recognition/SuspectRanking.kt` (new) — `Suspect`, `ScanEvidence`, `SuspectRanking.rank`.
- `core/domain/.../recognition/ScanAssembler.kt` — `ScanResult.Full.labs`, rotated with the colors.
- `core/domain/.../recognition/ScanReview.kt` — evidence, suspects, count problem linked to suspects, shared pre-selection (`from`, `corrected`, `paint` after the last blank).
- `core/domain/.../cube/PieceCheck.kt` — KDoc on `ReviewProblem` ordering.
- `core/domain/src/main/resources/content/strings/en.json` — `msg.scan.suspect-suggestion`.
- `app/src/main/.../ui/scan/ScanReviewScreen.kt` — initial focus on the counts line, `SuspectLine`.
- `app/src/camera/.../camera/GuidedScanFlow.kt` — `withCorrections` delegates to `ScanReview.corrected`.
- Tests: `SuspectRankingTest` (new; the issue scan replay), `ScanAssemblerTest`, `GuidedScanTest`, `RecognitionFixtures`, `ScanReviewTest`, `ScanReviewScreenTest`, `GuidedScanFlowTest`.

**Review findings:**
- **Pass 1** looped back once (bad_spec). The camera publish path dropped the evidence; the spec was amended, and the code was re-derived from the saved attempt-1 patch.
- **Pass 2** applied 3 patch entries, all low:
  - initial focus prefers the counts line, with a new screen test;
  - `paint` selects the top suspect when no blank remains, with a domain test;
  - the vgap focus test.
- **Deferred:** 1 item, the pre-existing color-count wording on scans.
- Every rejected finding and its reason is in the Review Triage Log.

**Follow-up review recommendation:** false. Pass 2 (after the loopback) patched 0 high and 0 medium entries; by verdict, high 0, medium 0, low 3.

**Verification:** `./gradlew :core:domain:test :app:testDebugUnitTest --tests '*ScanReview*' --tests '*Suspect*' --tests '*ScanAssembler*' --tests '*GuidedScanFlow*'` passes. `./gradlew spotlessCheck build` reports BUILD SUCCESSFUL.

**Residual risks:**
- Not tried on a device (the Lenovo / S24 FE check is out of scope).
- Up to 3 suspects are always shown, so tied, correctly read stickers can fill the list.
- The score is absolute ΔE, which favors close color pairs such as red/orange.
- After a paint with counts still off, the selection now moves to the next top suspect.
