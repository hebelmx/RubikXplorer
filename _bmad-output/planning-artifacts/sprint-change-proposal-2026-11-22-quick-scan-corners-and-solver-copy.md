# Sprint Change Proposal: Quick Scan corners and solver copy

**Date:** 2026-11-22 (revised the same day with the PO's decisions)
**Project:** RubikXplorer (Cubentia)
**Trigger:** GitHub issues #302, #299, #301, #262
**Mode:** Batch
**Scope:** Moderate (three new Epic 10 stories, amendments to Stories 10.21, 10.22 and 3.7, UX copy)
**Status:** **PO decisions recorded (2026-11-22, Q-a to Q-h); every edit in Section 4 is applied.** No open questions remain. `sprint-status.yaml` is not touched by this proposal (see 6.4).

---

## 1. Issue Summary

### #302 Quick Scan captures the wrong corner at hold 1, then hold 2 can never capture

Story 10.22 device session, Galaxy S24 FE, 2026-11-22, torch on. Quick Scan asked for corner 1 ("white on top, green and red facing you") while the cube rested **yellow on top, orange and blue** toward the camera. Hold 1 auto-captured 2.6 s after detection. The read was nonsense for the requested corner: U 1 confident / 8 uncertain, F 0 / 9, R 1 / 8, as close calls and disagreements. "Disagreements" means a saved calibration was in use. Hold 2 then asked for "yellow on top, blue and orange facing you", which was already in view. The pose check correctly saw the hold-1 centres (`poseRejected reason=centres`, ΔE 3 to 6) and blocked. For 85 s it flapped between accepted and rejected on single frames. The Learner was stuck with an instruction that already looked satisfied, and nothing offered a way back to corner 1.

Root cause: hold 1 labels its three faces from the prompt and has no pose check (spec F16, new). Before Story 10.22 a hold took 20 s or more, which gave the Learner time to orient the cube. Now a resting cube is captured in about 2.5 s, whatever corner it shows. Hold 2's block is correct, but it says nothing and offers no way out (spec F17, new). The open action item `epic-11-deferred-11.1-wrong-corner-self-calibration` describes the same defect from the self-calibration side.

Local evidence (not committed): `captures/10-22-galaxy-session1-20261123T004518/`.

### #299 Retake a corner from the corner view

PO idea from the Story 10.22 sessions. On the Lenovo TB128XU, 15 still holds kept 13 of 45 faces. A corner can be captured with a face read badly. Today the only ways forward are *Scan face by face* or leaving and starting again. `QuickScanController.backOut()` resumes a hold only from the face-by-face hand-over. EXPERIENCE.md:102 (Quick Scan capture) has no corner retake. DESIGN.md:275 defines *Retake faces* for the Guided 6-face Scan review only.

### #301 Resolve a few unread stickers from cube legality

PO idea, raised again in the 10.22 session. On the Lenovo, faces typically read 7, 9 and 7 of 9 stickers confident, and only the 9/9 face was kept (13 of 27 faces kept, about 80% of stickers confident). SCP 2026-11-22 quick-scan-reliability (line 81) did not propose sticker-level keeping by cube legality, because it would remove the containment that keeps confidently wrong stickers out. It remains a candidate in the spec ("Keep stickers, not faces", fix-plan.md:44 and :51; constraint at :104: global constraints cannot force an unsupported label). Story 10.21's keep rule (≤ 1 blank with a matching calibration, epics.md:2524) is the planned step short of it.

### #262 Solver preparing copy promises "Later solves are faster"

`msg.solve.preparing` (en.json:89) reads "Preparing the solver. The first solve after the app starts takes longer. Later solves are faster." It is pinned by EXPERIENCE.md:135, epics.md:908 (Story 3.7) and `AppShellSolveTest.kt:469,806`. An independent check found that no release-equivalent, same-process measurement supports "Later solves are faster". The only device data (debug, `docs/evidence/lenovo-tb128xu/production-strictmode-lenovo-tb128xu.log:35-40`) shows a second solve in the same process that was not faster. What the code does support is that the preparing state is skipped on later solves in the same process: `SolveFlowViewModel.kt:208` shows `WARMING_UP` only while `source.isReady` is false, `SolverSource.kt:29-36` keeps the loaded solver, and `SolverModule.kt:45` is `@Singleton`. The PO chose on 2026-11-22 to reroute #262 to correct-course for the copy. Related action item: `epic-3-deferred-3-7-preparing-copy-on-cold-cache-read`.

---

## 2. Impact Analysis

### Checklist

| Item | Status | Note |
|---|---|---|
| 1.1 Trigger | [x] | Story 10.22 device sessions (#302, #299, #301); independent copy check (#262) |
| 1.2 Problem type | [x] | #302: defect exposed by a faster capture (10.22). #299, #301: new requirements. #262: copy claim not backed by data |
| 1.3 Evidence | [x] | Section 1; F16 and F17 are recorded in the spec |
| 2.1 Current epic | [x] | Epic 10 can complete; it gains stories after 10.21 |
| 2.2 Epic changes | [x] | New Stories 10.24, 10.25, 10.26; amendments to 10.21 (Q-b), 10.22 (Q-a) and 3.7 (Q-g). Applied |
| 2.3 Future epics | [x] | Story 10.11-n (raise `QUICK_SCAN`) does not wait for 10.25 (Q-h); no new dependency is added to it |
| 2.4 Obsolete or new epics | [N/A] | None |
| 2.5 Order | [x] | 10.20 → 10.21 → 10.24 → 10.25; 10.26 only after the 10.21 BETA call. Stories 10.18 and 10.23 are being built now and are unaffected. Order line in epics.md updated |
| 3.1 PRD | [x] | No edit. FR-22 already says "two captures from opposite corners" and "never produces a guessed state"; it names no specific corner, so accepting any corner first stays within it |
| 3.2 Architecture | [x] | No new port, entity or `SessionEvent`. The logic is in `:core:domain` `QuickScanController` and `ScanAssembler` (AD-9); naming a corner from its centres feeds the samples' pose, so `ScanAssembler` stays the only place samples are rotated |
| 3.3 UX | [x] | EXPERIENCE.md:102 (any corner first, same-corner message, retake a corner), EXPERIENCE.md:135 (preparing copy), DESIGN.md:276 (*Retake corner* styling). Applied |
| 3.4 Other artifacts | [x] | Spec failure modes F16/F17 and the fix-plan pointer updated. For the build: `en.json` keys, `AppShellSolveTest`, and the current-text clauses of `docs/benchmarks.md:708` and `docs/pruning-tables.md:180` |
| 4.1 Direct adjustment | Viable | Effort medium, risk low to medium |
| 4.2 Rollback | Not viable | Reverting 10.22 brings back 20 s holds. The defect is labelling from the prompt, not 10.22's speed |
| 4.3 MVP review | Not needed | v1.0 scope unchanged |
| 6.3 Approval | [x] | PO decisions 2026-11-22, Section 6 |
| 6.4 sprint-status.yaml | [!] | Not touched by this worker. Add backlog entries for 10-24, 10-25 and 10-26. Story 10.25 closes `epic-11-deferred-11.1-wrong-corner-self-calibration`; the #262 build checks `epic-3-deferred-3-7-preparing-copy-on-cold-cache-read` |

### Epic and story impact

- **Story 10.22 (done):** its 10 s AC (epics.md:2702) gains a note: from Story 10.25 hold 1 takes whichever corner is shown, a hold 2 still showing the hold-1 faces is coached rather than counted as a miss, and Story 10.21's new wait counts toward the 10 s. The story is not reopened.
- **Story 10.21 (backlog):** gains the "almost no CONFIDENT stickers" wait (PO Q-b), fitted on the 10.20 tuning split beside the agreement gate.
- **Story 10.17 (done):** Story 10.24 makes one exception to its "a kept face never changes until review": a corner retake keeps the better read face by face. 10.17's text is not edited; 10.24 states the exception.
- **Story 3.7 (done):** the AC quote at epics.md:908 changes to "Preparing the solver." (Q-g).
- **Stories 10.18 and 10.23 (in progress):** no impact.

### Technical impact

- `:core:domain` `QuickScan.kt`: name the hold-1 corner from its centres (the white–yellow and green–blue centres by colour, the red–orange one from the colour scheme), carry the named faces and pose in the `FaceSample`s instead of `QuickScanPlan`'s fixed labels, label self-calibration references from the named centres, ask hold 2 for the other three faces, a same-corner state judged over a window, `retakeCorner(hold)` with a better-read-per-face merge, and a back-out-then-retake from the hand-over. `ScanAssembler`: name an unnamed corner from all six centres after hold 2. Tests: `QuickScanControllerTest`, a replay of the #302 readings (committed as a fixture of per-frame reads, no frames; the local evidence is not committed), and a held-out replay with 0 wrongly named corners.
- `:core:domain` Story 10.21 gate: a confident-count wait with a fitted N.
- `app/src/camera` `GuidedScanActivity`: the new prompts, the same-corner line, *Retake corner* in the corner view, the hand-over viewfinder and Quick Scan review retake mode, the system Back at hold 2, TalkBack.
- `en.json`: `msg.quick-scan.hold-first` and `msg.quick-scan.hold-second` (reworded, with colour arguments), new `msg.quick-scan.hold-second-unnamed`, `msg.quick-scan.same-corner`, `btn.quick-scan.retake-corner`; `msg.solve.preparing` = "Preparing the solver.". `AppShellSolveTest`: the pinned text, plus a two-accepts test.
- Risk to watch in 10.25: a wrongly named corner is a wrong cube. That is why the red–orange centre is never named by colour, the held-out replay bar is 0 wrongly named corners, and an unnamed corner falls back to naming after hold 2 and then to the Guided grid or review.

---

## 3. Recommended Approach

**Direct adjustment.** Add the Quick Scan changes as new Epic 10 stories after 10.20 and 10.21: the retake (10.24) first, because 10.25's way back uses it, then any corner first (10.25). Put #302's confident-count wait in 10.21 (Q-b). Plan #301 now as 10.26, starting only after the 10.21 BETA call (Q-c). Fix #262 as a copy change plus one JVM test, built as an issue fix.

- **Effort:** medium (10.24 and 10.25 are domain-and-adapter stories; the #262 fix is small; 10.26 is large).
- **Risk:** low to medium. 10.25 replaces "block a wrong corner" with "name the corner shown", so no correct corner is ever held back; the remaining risk is a misnamed corner, covered as above.
- **Timeline:** nothing on the v1.0 path waits; 10.11-n does not wait for 10.25 (Q-h).

**Alternatives considered:** block a hold-1 capture that does not show the requested corner (rejected by the PO, Q-a: there is no reason to require three specific sides); reopen Story 10.22 (rejected: 10.22 is done); replace all three faces on a retake (rejected by the PO, Q-e).

---

## 4. Detailed Change Proposals (all applied)

### 4.1 Stories (`_bmad-output/planning-artifacts/epics.md`)

**A. Story 10.22, last AC (epics.md:2702).** Rationale: PO Q-a removes "the wrong corner" from the 10 s bar.

OLD:
~~~
Every hold auto-captures within **10 s** of the cube being detected, still and hand-held (PO 2026-11-22); a hold that misses is recorded with its blocking gate and routed to the PO.
~~~
NEW:
~~~
Every hold auto-captures within **10 s** of the cube being detected, still and hand-held (PO 2026-11-22); a hold that misses is recorded with its blocking gate and routed to the PO. *(SCP 2026-11-22 quick-scan-corners-and-solver-copy, PO Q-a: from Story 10.25, hold 1 captures whichever corner is shown and hold 2 asks for the other three faces, so no hold waits for showing "the wrong corner". A hold 2 that still shows the hold-1 faces is not a miss: it is told so and offered the way back to corner 1 (Stories 10.24, 10.25). Story 10.21's confident-sticker wait counts toward the 10 s.)*
~~~

**B. Story 10.21, new AC after the agreement-gate AC (epics.md:2540-2542).** Rationale: the #302 hold had 2 of 27 CONFIDENT. A threshold chosen without data could hold back correct corners in poor light; 10.21 is where the confidence data and the agreement gate are fitted (PO Q-b).

NEW AC:
~~~
**Given** a corner hold in which almost no sticker reads CONFIDENT
**When** auto-capture would fire
**Then** it waits while fewer than **N** of the hold's 27 stickers read CONFIDENT. N is fitted on the Story 10.20 tuning split so that no positive tuning capture is held back for more than 10 s (Story 10.22); a starting value of 6 is scored on that split, and the chosen N and its numbers are recorded in the spec. While it waits, coaching shows the hold's prompt again, the manual shutter still captures, and `CubentiaScanDiag` logs `poseRejected reason=few-confident` with the count. The held-out replay reports how many holds the wait delayed and for how long. *(#302; SCP 2026-11-22 quick-scan-corners-and-solver-copy, PO Q-b: this gate lives here, beside the agreement gate, and not in Story 10.25.)*
~~~

**C. Epic 10 order line (epics.md:235).** Appended:
~~~
*(SCP 2026-11-22 quick-scan-corners-and-solver-copy: 10.21 → 10.24 (retake a corner, #299) → 10.25 (start from whichever corner is shown, #302); 10.26 (fill a few unread stickers from cube rules, #301) only after the 10.21 BETA call. PO Q-h: the 10.11-n run that raises `QUICK_SCAN` does not wait for 10.25, and no new story is added to its dependencies.)*
~~~

**D. Story 3.7 AC (epics.md:908).** Rationale: PO Q-g; the copy makes no timing claim.

OLD: "Preparing the solver. The first solve after the app starts takes longer. Later solves are faster." shows (SCP 2026-11-12, PO-approved 2026-11-12; it replaces the SCP 2026-11-11 text "Preparing the solver. This can take a few seconds the first time after the app starts.")
NEW: "Preparing the solver." shows (SCP 2026-11-22 quick-scan-corners-and-solver-copy, PO Q-g, #262; it replaces the SCP 2026-11-12 and SCP 2026-11-11 texts, and makes no claim about how long later solves take; a JVM test checks that the line shows only on the first solve in a process)

**E. New Stories 10.24, 10.25 and 10.26, inserted after Story 10.21 (epics.md:2560-2660).**

~~~
### Story 10.24: Retake a corner in Quick Scan

As a Learner using Quick Scan,
I want to take a corner again when I see the photo went wrong,
So that one bad photo doesn't make me restart the scan or go face by face.

**Refs:** FR-22, FR-23, NFR-10 · recognition NFRs (Quick Scan) · AD-9, AD-18 · UX-DR15 · Stories 10.16, 10.17, 10.21, 10.22 · #299

**Depends on** Story 10.21. **Human gate:** a device session on the main app build, `QUICK_SCAN` raised in debug, on the Lenovo TB128XU and the Galaxy S24 FE, with TalkBack on for part of it. *(SCP 2026-11-22 quick-scan-corners-and-solver-copy. PO decisions 2026-11-22: Q-d, retake corner 1 while corner 2 is awaited, by a visible control and by the system Back, and retake corner 1 or corner 2 from the Quick Scan review; Q-e, a retake keeps the better read face by face; Q-f, the retake is also offered from the face-by-face viewfinder.)*

**Acceptance Criteria:**

**Given** the Quick Scan corner view while corner 2 is awaited (corner 1 captured)
**When** it renders
**Then** an outlined *Retake corner 1* button (`en.json` `btn.quick-scan.retake-corner`, "Retake corner {n}") shows beside *Scan face by face* (DESIGN.md), the screen still fits a phone in portrait without scrolling, and TalkBack reads "Retake corner 1"; while corner 1 is awaited it does not show

**Given** corner 2 is awaited
**When** the Learner taps *Retake corner 1* or uses the system Back
**Then** both do the same: the corner view returns to corner 1 with its prompt and the counter "1 / 2", the turn-over state and the hold-1 centres are cleared as `backOut` does (Story 10.22), the torch state is kept, and TalkBack announces the corner-1 prompt. While corner 1 is awaited, the system Back leaves Quick Scan as before

**Given** a corner retake captures
**When** its faces are compared with the earlier reads of the same faces
**Then** the better read is kept face by face: a face that passes the keep rule (Story 10.21) beats one that does not; between two that both pass or both fail, the read with more CONFIDENT stickers wins; a tie takes the new read. This applies to faces kept by an earlier hand-over too, the one exception to Story 10.17's "a kept face never changes until review". After a retake of corner 1, hold 2 compares against the new hold-1 centres

**Given** the face-by-face viewfinder of a Quick Scan hand-over (Story 10.17)
**When** it renders
**Then** it offers *Retake corner 1*, and *Retake corner 2* once corner 2 was captured. Tapping one drops the faces captured in the hand-over (all or nothing, as `backOut` does) and opens the corner view at that corner; after the capture the better read is kept face by face, and faces that still need the grid are handed over again as usual. The system Back in that viewfinder stays `backOut` (Story 10.17), and *Scan face by face* still keeps every face that passes the keep rule

**Given** the Quick Scan review in retake mode (Stories 10.16, 10.17)
**When** it renders
**Then** outlined *Retake corner 1* and *Retake corner 2* buttons sit above the face net (each only when that corner was captured); one opens the corner view for that corner only ("1 / 2" or "2 / 2"), and after the capture review reopens with the better read kept face by face. A face that keeps its earlier read keeps the Learner's fixes; a face that takes the new read shows only its new capture, as in Story 10.16. If the faces can't make one valid cube, review shows the best fit with the usual messages and no re-prompt. Backing out before the capture reopens review as it was. *Close* asks "Discard this scan?" after a completed corner retake, as after a face retake

**Given** `QuickScanControllerTest`
**When** it runs
**Then** it covers: capture corner 1, retake it by the control and by Back, and capture corner 1 again with better and with worse readings, then corner 2, and the scan keeps the better read per face; a retake from the face-by-face viewfinder after a hand-over that kept a hold-1 face; a retake of each corner from review; and a back-out of each retake that changes nothing

### Story 10.25: Quick Scan starts from whichever corner it is shown

As a Learner using Quick Scan,
I want the first photo to take whichever corner I show and the second to ask for the faces still missing,
So that a fast capture never mislabels my cube and I never get stuck at the second corner.

**Refs:** FR-22 · recognition NFRs (Quick Scan, Confidence calibration), NFR-10 · AD-9, AD-18 · UX-DR15 · Stories 10.17, 10.21, 10.22, 10.24, 11.1 · spec `_bmad-output/specs/spec-quick-scan-reliability/` (F15, F16, F17) · #302

**Depends on** Story 10.21 (calibration-match check and the confident-sticker wait) and Story 10.24 (the way back). **Human gate:** a device session on the main app build, `QUICK_SCAN` raised in debug, on the Lenovo TB128XU and the Galaxy S24 FE, with a saved calibration and without, starting from at least three different corners. Closes action item `epic-11-deferred-11.1-wrong-corner-self-calibration`. *(SCP 2026-11-22 quick-scan-corners-and-solver-copy. PO decision 2026-11-22, Q-a: accept whichever corner hold 1 shows and ask for the opposite corner at hold 2; no wrong-corner block. The requested corner was only the natural order. PO Q-h: Story 10.11-n does not wait for this story.)*

**Acceptance Criteria:**

**Given** the corner view while corner 1 is awaited
**When** it renders
**Then** the prompt reads "Hold the cube with a corner toward you, so three faces show." (`msg.quick-scan.hold-first`), and no check blocks a capture for showing another corner than a requested one; Story 10.22's 10 s bar applies

**Given** a hold-1 capture
**When** its faces are named
**Then** they are named from their three centres, never from the prompt: each centre is read against the saved calibration when it matches (Story 10.21), otherwise against fixed reference colours recorded in the spec; a logo centre is read from its ring or left out (Story 10.22). The white–yellow and green–blue centres are named by colour, and a red–orange centre takes the colour that the cube's colour scheme allows for that corner, so a red/orange close call never names it. When this gives exactly one corner, the samples carry that corner's faces and pose for `ScanAssembler`, and self-calibration labels its references from the named centres. When it gives none (two centres on one axis, a scheme conflict, or too few plain centres), the corner stays unnamed and the capture is kept. `CubentiaScanDiag` logs `corner=named <colours>` or `corner=unnamed <reason>` with the three centre reads

**Given** corner 1 is captured
**When** the corner view asks for corner 2
**Then** the prompt names the faces still missing, for example "Now turn the cube halfway, so yellow, blue and orange face you." (`msg.quick-scan.hold-second` with the colours as arguments); for an unnamed corner 1 it reads "Now turn the cube halfway, so the other three faces show." After hold 2 the faces are named from all six centres; a scan whose faces still can't be named goes to the Guided grid or the review as today, never to a guessed state (FR-22)

**Given** hold 2 blocked by the pose check because it still shows the hold-1 faces
**When** the block has lasted about 2 s, judged over the window (not frame by frame, so single-frame flaps neither show nor hide it)
**Then** inline coaching reads "That's the same corner as the first photo. Turn the cube halfway, or retake corner 1." (`msg.quick-scan.same-corner`), *Retake corner 1* (Story 10.24) takes its highlighted outline, and TalkBack announces the line once; the line hides once the turn is confirmed

**Given** the readings of the #302 session (committed as a domain fixture of per-frame centre and sticker reads, no frames)
**When** `QuickScanControllerTest` replays them with the session's calibration
**Then** a hold 1 on the yellow-top corner, once it captures, is named yellow, orange and blue, and hold 2 asks for white, green and red; replayed as hold 2 after that hold 1, the same-corner state is set and the retake then captures; no hold is left with no message and no way out

**Given** the Story 10.20 held-out groups, replayed as hold 1 from each corner they show, with a matching calibration, without one, and with a calibration from another cube and another light
**When** the replay runs
**Then** **0** corners are named wrongly (a wrongly named corner would be a wrong cube), and the share of corners left unnamed is reported per device, light and torch state

### Story 10.26: Quick Scan fills a few unread stickers from the cube's rules

As a Learner using Quick Scan,
I want the few stickers the camera couldn't read to be worked out from the rest of my cube when that is certain,
So that a handful of doubtful stickers doesn't send whole faces to face-by-face capture.

**Refs:** FR-22, FR-23 · recognition NFRs (Quick Scan, Confidence calibration) · AD-9 · Stories 10.17, 10.20, 10.21 · spec `_bmad-output/specs/spec-quick-scan-reliability/fix-plan.md` ("Keep stickers, not faces", CAP-5) · #301

**Depends on** Stories 10.20 and 10.21. **Starts** only after the PO's 10.21 BETA call (Story 10.21's last AC), with the held-out report in hand; the safety assertion (first AC) is built and run before any search. **Human gate:** a device session on both reference devices. *(SCP 2026-11-22 quick-scan-corners-and-solver-copy. PO decision 2026-11-22, Q-c: plan it now in the backlog. SCP 2026-11-22 quick-scan-reliability did not propose it; the "Keep stickers, not faces" candidate in the spec and its constraint that cube rules cannot force an unsupported label (fix-plan.md) are carried into the ACs below. It does not change Story 10.21's keep rule.)*

**Acceptance Criteria:**

**Given** `QuickScanSideFaceReplayTest`
**When** a per-sticker safety assertion is added first (no sticker that would be accepted without Learner confirmation is wrong), and run on the existing and 10.20 groups before any search is built
**Then** its result is recorded in the spec and sizes the work

**Given** a two-corner scan whose faces carry per-sticker colour candidates (a CONFIDENT sticker carries one, an uncertain sticker its plausible readings from the image)
**When** `ScanAssembler` searches the combinations
**Then** the search prunes by cube rules (six known centres, nine of each colour, only legal edge and corner pieces), stops at a cap on combinations and unknown stickers, and cube rules only rank or reject candidates; they never give a sticker a colour that is not among its candidates

**Given** the search
**When** exactly one legal cube remains and every filled sticker's true colour lies within its candidates
**Then** the filled stickers arrive in review marked as filled and pre-selected for checking; otherwise (several cubes, none, over the cap, or too many unknowns) the review or the Guided grid asks the Learner, as today

**Given** the held-out replay
**When** it runs per device, light and torch state
**Then** 0 confidently wrong stickers are added, and faces kept and stickers filled are reported against the 10.21 baseline
~~~

### 4.2 PRD

No change (3.1).

### 4.3 Architecture

No change (3.2).

### 4.4 UX (`ux-designs/ux-RubikXplorer-2026-10-29/`)

**F. EXPERIENCE.md:102, Quick Scan capture row.** Appended after the *Kept faces* sentence:
~~~
**Any corner first (SCP 2026-11-22 quick-scan-corners-and-solver-copy, PO Q-a, Story 10.25):** the first photo takes whichever corner the Learner shows; the prompt reads "Hold the cube with a corner toward you, so three faces show." The app names the three faces from their centres, never from the prompt. The second prompt names the faces still missing ("Now turn the cube halfway, so yellow, blue and orange face you."); when the centres could not name the first corner, it reads "Now turn the cube halfway, so the other three faces show." Nothing blocks a first photo for showing "the wrong corner". **Same corner (#302, Story 10.25):** when the second hold still shows the faces of the first photo, after about 2 s inline coaching reads "That's the same corner as the first photo. Turn the cube halfway, or retake corner 1.", and *Retake corner 1* is highlighted. **Retake a corner (#299, Story 10.24):** while it waits for corner 2, the corner view shows an outlined *Retake corner 1* beside *Scan face by face*, and the system Back does the same; both return to corner 1 with the counter at "1 / 2" (Back at corner 1 leaves Quick Scan, as before). The face-by-face viewfinder of a hand-over also offers *Retake corner 1* (and *Retake corner 2* once taken); it drops the faces captured in the hand-over, as backing out does. In the Quick Scan review, retake mode offers *Retake corner 1* and *Retake corner 2* beside the face selection; each opens the corner view for that corner only and then returns to review. A retake keeps the better read face by face: a face read well enough to keep beats one that is not, then the read with more confident stickers wins, and a tie takes the new read. A face that keeps its earlier read keeps the Learner's fixes; a face that takes the new read shows only its new capture. Backing out of a retake before it captures changes nothing. TalkBack reads each control by its label and announces the new prompt.
~~~

**G. DESIGN.md:276, new bullet after *Retake mode*:**
~~~
- *Retake corner* (SCP 2026-11-22 quick-scan-corners-and-solver-copy, #299, Story 10.24): an outlined button with `ink-primary` text, like *Close*. In the Quick Scan corner view it shows only while corner 2 is awaited, as *Retake corner 1* beside *Scan face by face*; while the same-corner coaching shows it takes the solid `accent` outline (static, no pulse). In the face-by-face viewfinder of a Quick Scan hand-over it sits beside the face prompt, one button per corner already captured. In Quick Scan review retake mode, *Retake corner 1* and *Retake corner 2* sit in one row above the face net and are outlined, never filled, so *Retake N faces* stays the only primary button. In every placement the screen still fits a phone in portrait without scrolling.
~~~

**H. EXPERIENCE.md:135, Solver preparing row.**

OLD: "Preparing the solver. The first solve after the app starts takes longer. Later solves are faster." (SCP 2026-11-12, PO-approved 2026-11-12; replaces "Preparing the solver. This can take a few seconds the first time after the app starts.")
NEW: "Preparing the solver." (SCP 2026-11-22 quick-scan-corners-and-solver-copy, PO Q-g, #262; replaces the SCP 2026-11-12 text. The line makes no claim about how long later solves take.)

The rest of the row ("Shown on the first solve in each app process … Later solves in the same process skip it.") is kept: it describes when the line shows, which the code supports and the new JVM test checks, and says nothing about solve speed.

### 4.5 Spec

- `_bmad-output/specs/spec-quick-scan-reliability/failure-modes.md`: rows and sections **F16** (hold 1 labels its faces from the prompt, not from the centres shown) and **F17** (hold 2 blocked as the same corner, with no message and no way back), with the #302 evidence and the decided fixes.
- `_bmad-output/specs/spec-quick-scan-reliability/fix-plan.md`, "Relation to planned stories": a pointer to Stories 10.24, 10.25, 10.26 and the 10.21 wait.

---

## 5. Implementation Handoff

**Scope: moderate.** Planning edits are applied. Next: sprint-status gets backlog entries for 10-24, 10-25 and 10-26 (orchestrator or PO), and Dev builds in order.

| Issue | Then | What the code change must do |
|---|---|---|
| #262 | build | Set `msg.solve.preparing` to "Preparing the solver." in `en.json`; update `AppShellSolveTest.kt:469,806` and the current-text clauses of `docs/benchmarks.md:708` and `docs/pruning-tables.md:180`, with no claim about later solve speed anywhere. Add a JVM test that makes two accepts in one process against a `SolverSource` that keeps its solver and checks that `solve:preparing` shows only on the first. Check whether `epic-3-deferred-3-7-preparing-copy-on-cold-cache-read` still holds |
| #299 | story (10.24) | *Retake corner 1* at hold 2 by a visible control and the system Back; *Retake corner 1/2* from the face-by-face viewfinder (dropping hand-over captures as `backOut` does) and from Quick Scan review retake mode; a retake keeps the better read face by face; `en.json` text and TalkBack |
| #302 | story (10.25), plus the wait in 10.21 | Hold 1 captures whichever corner is shown and names its faces from the centres (red–orange from the colour scheme); hold 2 asks for the other three faces; a same-corner message judged over a window with *Retake corner 1* highlighted; the #302 replay and a held-out replay with 0 wrongly named corners. 10.21: wait while fewer than a fitted N of 27 stickers read CONFIDENT |
| #301 | story (10.26) | Safety assertion first, then a sticker-level search with pruning and a cap, an "ask the Learner" branch, cube rules only ranking or rejecting candidates; starts only after the 10.21 BETA call |

**Success criteria:** #302, reworked by PO Q-a: (1) hold 1 captures any corner and names it from its centres, 0 wrongly named on the held-out replay; (2) a hold with almost no CONFIDENT sticker waits (Story 10.21); (3) a hold 2 showing the hold-1 faces is told so and offered the way back; (4) the #302 replay shows a correctly named hold 1 and no stuck hold 2. #299: the 10.24 ACs. #301: Story 10.26 planned. #262: copy promises nothing unmeasured, plus the two-accepts test.

Order: 10.20 → 10.21 → 10.24 → 10.25; 10.26 after the 10.21 BETA call. The #262 build is independent. 10.11-n waits for none of the new stories.

---

## 6. PO Decisions 2026-11-22 (recorded)

- **Q-a (#302):** capture and flag; do not require a specific corner at hold 1. The requested corner was only the natural order. Hold 1 accepts whichever corner (three faces) it shows, and hold 2 asks for the other three faces (the opposite corner). No wrong-corner block; the hold-2 prompt names the faces still missing; a hold 2 that shows the same faces as hold 1 is told so and offered a way back. → Story 10.25, edits A and F.
- **Q-b:** the "almost no CONFIDENT stickers" gate goes in Story 10.21, fitted on the 10.20 data. → Edit B.
- **Q-c (#301):** plan Story 10.26 now: backlog after 10.21, starting only after the BETA call, safety assertion first. → Story 10.26.
- **Q-d (#299):** retake in two places: *Retake corner 1* while waiting for corner 2, reached by both a visible control and the normal system Back; and in the Quick Scan review, retake of corner 1 and of corner 2. → Story 10.24, edits F and G.
- **Q-e (#299):** a retake keeps the better read face by face (not replace all). → Story 10.24.
- **Q-f (#299):** the retake is also offered from the face-by-face viewfinder. → Story 10.24.
- **Q-g (#262):** the copy is just "Preparing the solver." with no timing claim, because it is not known that later solves are faster. EXPERIENCE.md:135 and epics.md:908 are updated; the build changes `en.json` `msg.solve.preparing` and the pinned tests and adds the two-accepts JVM test. No claim about later solve speed anywhere. → Edits D and H.
- **Q-h:** Story 10.11-n (`QUICK_SCAN` to BETA) does not wait for the #302 story. → Edit C.
- **Earlier (PO 2026-11-22):** #262 rerouted to correct-course for the copy.

Detail choices made within these decisions (no new product question): "better read" means passing the keep rule first, then more CONFIDENT stickers, a tie taking the new read; the Learner's review fixes stay on a face that keeps its earlier read; the system Back at hold 1 still leaves Quick Scan; a corner whose centres can't be named is still captured and named after hold 2 from all six centres, so no capture waits on naming.
