# Sprint Change Proposal: GitHub issue triage — scan handoffs, Placement, scan-review hints, fixture recording and solver preparation (2026-11-24)

- **Trigger:** GitHub issues #323, #324, #325, #337 and #321 (release device session 2026-11-23 and the 2026-11-23 corner label review), plus the pending #104, #313 and #326. Issue bodies read from the orchestrator's issue snapshots; no `gh` call was made.
- **Mode:** Batch
- **Scope:** Moderate (three new stories, amendments to Stories 3.5, 10.2, 10.3, 10.5, 10.15, 10.17, 10.27, 11.2 and 14.1, and an EXPERIENCE.md update; no PRD or architecture change)
- **Status:** Approved, pending merge (PO 2026-11-24: Q1–Q3 decided, section 6; revision 2 applies the adversarial review findings, section 7). The planning edits in section 4 are applied on branch `docs/scp-2026-11-24-github-issue-triage`.
- **Supersedes:** the unapproved draft `sprint-change-proposal-2026-11-23-github-issue-triage.md` (renamed to this file and finished; its analysis is kept).
- **Product owner decisions already made (2026-11-24):**
  1. Finish the 2026-11-23 draft rather than start over.
  2. #321 is pulled into the current release (v1.0); its v1.1 deferral (the GitHub `v1.1` label) is removed.
  3. #326 stays v1.1.
  4. #338 (corner model placement) is not part of this proposal; Story 10.27 AC2 owns it.

## 1. Issue summary

The release device session (2026-11-23, Galaxy S24 FE and Lenovo TB128XU, debug build, `main` `dfc5c3d6`) surfaced three navigation gaps in Guided Scan and Placement, and a difference between Quick Check in Lessons and Solution Playback. The same session showed scan review suggesting clearly different colours after a manual edit. A separate label review found 20 incorrect sticker labels across 23 corner fixture groups. The solver still rebuilds move tables after a cold process start, with a 12.6 s debug median on the Lenovo TB128XU. The solver preparation message now avoids promising a duration.

| Issue | Problem | Evidence |
| --- | --- | --- |
| #323 (bug) | After a hand-painted sticker made the counts wrong (Up 3 orange → blue, then Up 9 white → blue), the likely-misreads list put a genuine blue read (Up 2) first: "This sticker may be orange / white — check it". `SuspectRanking` has no closeness threshold; it always names the nearest candidate. The PO decided on 2026-11-20 that clearly different colours are never suggested. This settles sprint action item `issue-174-deferred-suspect-distance-threshold` (deferred from #174, `SuspectRanking.kt:51-53`). | release-device-session README; issue body |
| #324 | A solved cube in Placement offers only *Scan again* and *Start Stage 1*; a solved cube says nothing about skill. PO accepted *I can already solve it* opening `EntryLevelScreen`. | doubts panel, 2026-11-23 |
| #325 | Guided Scan *Close* after two faces cancels at once (`outcome=CANCELLED`) and loses the faces; Quick Scan asks under the #198 rules. PO accepted a confirm when ≥ 1 face is captured. | Lenovo, doubts panel |
| #313 | Guided Scan *Close* is hard to find in tablet landscape. Bundled with #325 by the draft. | release-device-session README |
| #321 | Solve → *Scan my cube* → six faces → review → *Close* opens an empty Editor ("No scan this time…", 6 faces left; log `valid=true outcome=FELL_BACK_TO_EDITOR`). PO proposal: keep the scanned colours. | Galaxy S24 FE |
| #337 (bug) | The corner recorder pre-fills from the nearest calibrated centre; 18 of the 20 corrected labels were pre-filled (all 12 WHITE→BLUE, on the side faces). Nothing stops a group whose labels do not count 9 of each colour. | `docs/evidence/10-27/label-corrections-2026-11-23.json`; `truthSources` at `add0c79e` |
| #104 | Cold-process move-table build: 12.6 s debug / ≤ 10.5 s release-equivalent on the Lenovo; accepted for v1.0, speed-up v1.1. | `docs/benchmarks.md`, Story 3.8 annotation |
| #326 (v1.1) | Physical Lessons show a full Quick Check result; Solution Playback shows only the camera icon. PO accepted one shared result component, story-sized, not this release. | doubts panel |

Sources: [release device session](../../docs/evidence/release-device-session/README.md), [corner fixture review](../../docs/evidence/10-27/issue-drafts-2026-11-23.md), [solver preparation #104](https://github.com/hebelmx/RubikXplorer/issues/104).

## 2. Impact analysis

### Checklist

| # | Item | Status | Note |
| --- | --- | --- | --- |
| 1.1 | Trigger | [x] | Release device session (Stories 10.20 / 12.4 Block 1 sitting) and the 10.27 label review |
| 1.2 | Problem type | [x] | #323, #337: defects in done work (Story 10.3 via #174; Story 10.20's recorder). #321, #324, #325, #313: misunderstood or incomplete requirements in done stories (10.5/10.15, 11.2, 10.2). #104, #326: accepted follow-ups |
| 1.3 | Evidence | [x] | Section 1 |
| 2.1 | Current epic | [x] | Epic 10 can complete as planned; its done stories get amendments built as issue fixes, as SCP 2026-11-20 did for #249/#251–#253 |
| 2.2 | Epic-level changes | [x] | Epic 3 gains Story 3.9 (v1.1; reopens the epic); Epic 10 gains Stories 10.28 and 10.29 (10.29 v1.1); Epics 11 and 14 get AC amendments only |
| 2.3–2.4 | Future epics | [N/A] | No epic is invalidated or added |
| 2.5 | Order | [x] | Epic 10 Order line annotated; 10.28 runs before any new corner recording; nothing resequenced |
| 3.1 | PRD | [N/A] | FR-23 "invalid states name likely bad stickers" still holds (only implausible names are dropped); FR-25 "camera permission denied → Manual Cube Editor" unchanged; FR-27 and FR-4 already cover Placement and entry level; FR-15/NFR-3 cover #104 |
| 3.2 | Architecture | [N/A] | No spine decision changes. #321 adds a payload to the existing typed `ScanOutcome.FellBackToEditor` (Story 10.3's contract); #337 adds an optional field to the debug fixture manifest (AD-15 developer tool); #326 shares a composable inside `:app` with state changes through `SessionEvent` → `reduce()` |
| 3.3 | UX | [!] | EXPERIENCE.md rows Scan viewfinder, Sticker review net, Entry level, Placement outcomes, Invalid entered state, and Flow 3 (section 4.1). DESIGN.md unchanged: #313 is placement in a group, using existing button styles. #326's shared result card gets its EXPERIENCE edit when Story 10.29 is created |
| 3.4 | Other artifacts | [!] | `sprint-status.yaml` (not edited here; section 5); GitHub labels on #321 |
| 4 | Path forward | [x] | Direct adjustment |

### Story impact

| Story | Change |
| --- | --- |
| 3.5 (done) | AC note: the Editor's suspect markers are unchanged, the #323 rule is Scan review only; new AC defining an unset sticker in an Editor seeded from a Solve-tab scan (#321) |
| 3.9 (new, v1.1) | Measure and shorten cold solver preparation (#104) |
| 10.2 (done) | Amendment: discard confirm in the viewfinder (#325) and *Close* placement in tablet landscape (#313) |
| 10.3 (done) | Annotation at AC1 and an amendment: proximity rule for likely misreads, Scan review only (#323); an amendment: review *Close* with no change returns `FellBackToEditor`, with a change asks "Discard this scan?" (#321) |
| 10.5 (done) | AC2 wording: `FellBackToEditor` opens a seeded Editor, not an empty one (#321) |
| 10.15 (done) | Amendment: seeded Editor fallback on the Solve tab only, new note copy, unresolved-rotation faces unset, blank on no capture or denial, Placement fallback blank, direct Accept unchanged (#321) |
| 10.17 (done) | Annotation: its review-level "Discard this scan?" is unchanged (#325) |
| 10.27 (backlog) | Annotation: offline audit stays here; recorder safeguard is 10.28; #338 stays with AC2 |
| 10.28 (new) | The fixture recorder checks nine of each colour before it saves a corner group (#337) |
| 10.29 (new, v1.1) | Solution Playback shows the same Quick Check result as Lessons (#326) |
| 11.2 (done) | Amendment: solved outcome adds *I can already solve it* (#324) |
| 14.1 (done) | AC1 Given: the new entry point (#324) |

The historical `sprint-change-proposal-2026-11-19-scan-accept-skips-editor.md` (decision 3, line 9) is not edited; this proposal refines its fallback rule, and Story 10.5/10.15 now carry the new wording.

## 3. Recommended approach

**Direct adjustment.** Amend the done stories and build #323, #324, #325, #313 and #321 as issue fixes through `/bmad-orchestrate-issues`, as SCP 2026-11-20 did for #249/#251–#253. Add three bounded stories: 10.28 (recorder safeguard, debug tool, device-free), 3.9 (v1.1, measure first) and 10.29 (v1.1).

The Guided Scan controls and the solved Placement action are small interaction changes. Transferring partial scans, sharing Quick Check across hosts, and blocking bad fixture labels have a larger regression surface. Measure the solver before choosing a mitigation. Ship the approved camera changes when their own device and accessibility checks pass. Schedule the fixture recorder work independently of the release decision.

Rolling back the scan, Placement, or recorder flow would remove behaviour users already rely on. The existing interfaces can support the follow-ups without resequencing the epics.

| Work | Release | Effort | Risk |
| --- | --- | --- | --- |
| #323 proximity rule | v1.0 | small | low; Scan review hint only, no classifier or Editor change |
| #324 solved-outcome action | v1.0 | small | low; reuses `EntryLevelScreen` |
| #325 discard confirm, #313 Close placement | v1.0 | small | low–medium; back handling in the viewfinder |
| #321 seeded Editor fallback | v1.0 (PO decision b) | medium | medium; `ScanOutcome` payload and Editor seeding |
| 10.28 recorder count check | debug tool, independent | small–medium | low |
| 3.9 cold preparation | v1.1 | medium | medium; solver path, measured first |
| 10.29 shared Quick Check result | v1.1 | medium | medium; two hosts |

These changes fit the existing product requirements for solving, reviewing scan results, choosing a starting Stage, and using Quick Check. The recorder proposal adds optional diagnostic evidence to debug fixture manifests. The proposed device paths run under the camera flags and the release privacy policy.

## 4. Detailed change proposals (applied)

Line numbers are those after the edits. OLD is the text at `f3a363a1` (HEAD of `main` when this branch was cut).

### 4.1 EXPERIENCE.md (`ux-designs/ux-RubikXplorer-2026-10-29/EXPERIENCE.md`)

**X0, front matter (:5).** OLD `updated: 2026-11-20` → NEW `updated: 2026-11-24`.

**X1, Scan viewfinder row (:98), #325, #313.** OLD: the row ends `…all fit on one screen without scrolling (see Responsive & Platform). |`. NEW: inserted before the closing `|`:
```md
**Close (SCP 2026-11-24 github-issue-triage, #325, #313):** while no face has been captured in this viewfinder, *Close* and the system Back leave at once. Once one or more faces are captured, they ask "Discard the faces you captured?" with *Keep scanning* (closes the question; every captured face, the mini net and the current prompt stay) and *Discard* (the scan is cancelled and the Learner returns to the screen the scan was opened from: Welcome, the Solve tab, *Find my place* (Re-placement) or the Quick Check that asked for a full scan; the same rule applies from every caller). A retake from review and a Quick Scan hand-over keep their own back rules (Sticker review net, Quick Scan capture). In tablet landscape, *Close* sits in the same control group as the shutter and the face prompt, never alone in a far corner, and TalkBack reaches it with those controls.
```

**X2, Sticker review net row (:99), #325, #321.** OLD `*Close* asks "Discard this scan?" only if the Learner changed a sticker or completed a retake.` → NEW:
```md
*Close* on the review asks "Discard this scan?" only if the Learner changed a sticker or completed a retake, and *Discard* cancels the scan; with no change, *Close* falls back to the Editor with the scan's stickers (seeded on the Solve tab only, SCP 2026-11-24, #321). The viewfinder's own *Close* follows the Scan viewfinder row (SCP 2026-11-24, #325).
```

**X3, Entry level row (:130), #324.** OLD surface cell `Welcome → *I can already solve it*, Learn → *Find my place* → *Choose my level*` → NEW `Welcome → *I can already solve it*, Learn → *Find my place* → *Choose my level*, Placement's solved outcome → *I can already solve it* (SCP 2026-11-24, #324)`. OLD row end `With `PLACEMENT` on, *Not sure? Scan my cube* is offered at the bottom. |` → NEW adds `Opening the picker writes nothing; only choosing a level does. Opened from Placement's solved outcome, Back returns to that outcome (SCP 2026-11-24, #324).`

**X4, Placement outcomes row (:131), #324.** OLD `**Solved:** "Your cube is solved." / "Scramble it and scan again, or start Stage 1." (*Scan again* / *Start Stage 1*).` → NEW:
```md
**Solved:** "Your cube is solved." / "Scramble it and scan again, or start Stage 1." (*Scan again* / *Start Stage 1* / *I can already solve it*). A solved cube says nothing about skill, so *I can already solve it* opens the Entry level picker; it grants no progress until a level is chosen there (SCP 2026-11-24, #324; the two lines are unchanged).
```

**X8, Placement outcomes row (:131), #321 (Placement fallback stays blank).** OLD `*Enter colors instead* or a camera denial opens the Manual Cube Editor, and its accepted cube is placed like a scanned one (SCP 2026-11-18).` → NEW: the same sentence, followed by
```md
A Placement scan that falls back opens that Editor blank, even after faces were captured (SCP 2026-11-24, #321: seeding is the Solve tab's only).
```

**X5, Invalid entered state row (:137), #323.** OLD `suspect stickers carry the numbered markers Scan review uses, and their face tabs a warning dot.` → NEW: the same sentence, followed by
```md
**Likely misreads (SCP 2026-11-24, #323; closes the #174 deferral):** Scan review names a sticker as a likely misread ("This sticker may be {color} — check it.") only when its measured color is close to the under-counted color: at most the suspect margin, 20 ΔE (PO 2026-11-24; twice the close-call rule), farther from that color's calibrated center than from the center of the color it was read as. A sticker that qualifies for several under-counted colors is suggested as the nearest one, and the list is ordered by margin. Clearly different colors are never suggested. This rule is Scan review's only; the Editor's markers are unchanged. With no close candidate, the count line and the piece problems still show, with no "may be" suggestion and no count-suspect marker.
```

**X6, Invalid entered state row (:137), #321.** OLD `An Editor opened on a caller's cube (physical mode, Placement fallback) counts every face as entered.` → NEW: the same sentence, followed by
```md
An Editor seeded from a scan (Solve tab only; SCP 2026-11-24, #321) shows each sticker the scan did not read, and every sticker of a captured face whose rotation could not be worked out, as *unset*: the review net's blank well (no sticker color, dashed edge and "?"), read by TalkBack as "not set". Tap it, then a color, to set it. A face counts as entered once all nine of its stickers are set, and while any sticker is unset no piece or count error shows: the line names the faces left to enter.
```

**X7, Flow 3 step 3 (:259), #321.** OLD `The Manual Cube Editor is only the fallback: *Enter colors instead*, a scan that can't be made valid or complete, or a denied camera permission (SCP 2026-11-19, #203).` → NEW: the same sentence, followed by
```md
When the fallback follows a scan that captured faces, the Editor opens with the scanned colors and the Learner's review fixes already in place, in the home frame, and only the stickers the scan did not read are left to fill, with the note "Your scan is here. Fill in the stickers it couldn't read."; a denied permission or a scan with no captured face opens the blank Editor with "No scan this time. Enter your cube's colors here." (SCP 2026-11-24, #321; Solve tab only).
```

### 4.2 epics.md

**P0, front matter (:6).** OLD `updated: 2026-11-23` → NEW `updated: 2026-11-24`.

**P1, Epic List, Epic 3 (:201), #104.** Inserted after the Epic 3 **FRs covered** line: `*(SCP 2026-11-24 github-issue-triage: Story 3.9, cold solver preparation, #104, is v1.1 scope and reopens the epic; it never gates the v1.0 release.)*`

**P2, Epic List, Epic 10 Order (:236).** OLD `Device sessions: A = 10.10; A2 = 10.12;` → NEW prefixed with `*(SCP 2026-11-24 github-issue-triage: 10.28 (the recorder's count check, #337) after 10.20, independent of 10.27, before any new corner-fixture recording; it blocks neither 10.21 nor 10.11. 10.29 (Solution Playback shows the Lesson's Quick Check result, #326) is v1.1 scope, after 10.7. #321, #323, #325 and #313 are v1.0 issue fixes against Stories 10.15, 10.3 and 10.2.)* `

**P3, Story 3.5 (:871), #323.** OLD `suspect stickers carry Scan review's numbered markers, and their face tabs a warning dot` → NEW `suspect stickers carry Scan review's numbered markers, and their face tabs a warning dot (unchanged by SCP 2026-11-24, #323: its likely-misread proximity rule applies to Scan review only)`. No behaviour change in the Editor.

**P15, Story 3.5 unset-sticker AC (:865), #321,** inserted after the "faces not yet touched" AC:
```md
**Given** an Editor seeded from a Solve-tab scan with unset stickers *(SCP 2026-11-24 github-issue-triage, #321; Story 10.15)*
**When** it renders and validation runs
**Then** each unset sticker shows as the review net's blank well (neutral, no sticker color, dashed edge and "?", DESIGN.md) and TalkBack reads it as "{face} sticker {n}, not set"; tapping it and then a color sets it, as for any sticker; a face counts as entered once all nine of its stickers are set; while any sticker is unset no count or piece error shows, the line names the faces left to enter, and *Accept* stays disabled
```

**P4, new Story 3.9 (:961), #104,** inserted after Story 3.8's SCP 2026-11-12 annotation, before `## Epic 4`:
```md
### Story 3.9: Measure and shorten cold solver preparation

As a Learner who opens the app to solve a cube,
I want the first solve after the app starts to be ready sooner,
So that I am not left waiting on "Preparing the solver."

**Refs:** FR-15 · NFR-3, NFR-11 · AD-8, AD-16 · Stories 3.3, 3.8, 12.2 · #104

**Depends on** Story 3.8. **v1.1 scope** (#104 stays a later-release item; it never gates the v1.0 release). **Human gate:** a measurement session on the physical Lenovo Tab M10 Plus 3rd Gen (TB128XU), the minimum reference device. *(SCP 2026-11-24 github-issue-triage, #104: the cold-preparation follow-up to Story 3.8; bundled pruning tables did not remove the per-process move-table build, 12.6 s debug median and ≤ 10.5 s release-equivalent on the Lenovo.)*

**Acceptance Criteria:**

**Given** the physical Lenovo TB128XU and a release-equivalent build (R8, release code)
**When** at least five cold process starts each run the production Solve flow to a Solution
**Then** move-table preparation and search are timed and reported separately (median, minimum, maximum), with device and Android identity, revision and run conditions, in `docs/benchmarks.md`, beside the existing 12.6 s debug / ≤ 10.5 s release-equivalent baseline

**Given** that baseline
**When** the PO agrees a target from it
**Then** the target is recorded in the story spec before any mitigation is merged, and #104 closes only when the target is met

**Given** one bounded mitigation (for example shipping the move tables, building them faster, or starting preparation when the Solve tab opens)
**When** it is measured with the same method on the same device
**Then** it is compared with the baseline, and the comparison and the choice are appended to the architecture memlog with `uv run _bmad/scripts/memlog.py append`

**Given** the chosen mitigation
**When** it is verified
**Then** every Story 3.3 corpus Solution is still independently verified, preparation stays off the main thread (debug StrictMode adds no app main-thread disk violation), Lessons never wait for the Solver, no wait is added at app start, the install stays within NFR-11, and the copy "Preparing the solver." is unchanged
```

**P5, Story 10.2 amendment (:1939), #325, #313,** inserted after its last AC, before Story 10.3:
```md
*Amended by SCP 2026-11-24 github-issue-triage (#325, #313), built as issue fixes through `/bmad-orchestrate-issues`; v1.0 scope; acceptance on the Lenovo Tab M10 Plus 3rd Gen and the Galaxy S24 FE, main app build, from every caller of the Guided 6-face Scan viewfinder:*

**Given** the Guided 6-face Scan viewfinder with no face captured in this viewfinder
**When** the Learner taps *Close* or the system Back
**Then** the viewfinder leaves at once and returns `Cancelled`

**Given** one or more faces captured in this viewfinder
**When** the Learner taps *Close* or the system Back
**Then** "Discard the faces you captured?" shows with *Keep scanning* and *Discard*; *Keep scanning* closes the question and keeps every captured face, the mini net and the current prompt; *Discard* returns `Cancelled` and the Learner is back on the screen the scan was opened from; the copy comes from content strings and TalkBack reads the question, then the two actions

**Given** a retake viewfinder opened from review (Story 10.16) or a Quick Scan hand-over viewfinder (Stories 10.17, 10.24)
**When** the Learner backs out
**Then** their own back rules apply, unchanged

**Given** every caller of the Guided 6-face Scan viewfinder: Welcome → *Start from my cube*, Solve → *Scan my cube*, Learn → *Find my place* → *Scan my cube* (Re-placement), and the Quick Check full scan (*Scan all six faces*)
**When** the Learner closes or backs out of it
**Then** the same rule applies (0 faces: leave at once; 1 or more: the confirm), and *Discard* returns to that caller's previous screen

**Given** JVM or Robolectric tests on each of those callers
**When** they run
**Then** they cover leaving with 0 faces, *Keep scanning* and *Discard* with 1 or more faces, for both *Close* and system Back, and check the screen *Discard* returns to

**Given** the viewfinder in tablet landscape on the Lenovo's 600 dp width, and in portrait *(#313)*
**When** it renders
**Then** *Close* sits in the same control group as the shutter and the face prompt, TalkBack reaches it in order with those controls, and at 200% font nothing is cut off; the phone-portrait no-scroll rule still holds
```

**P6, Story 10.3 AC1 (:1977), #323.** OLD `invalid states name the likely bad stickers ("This corner shows two whites.") *(SCP 2026-11-15)*` → NEW: the same, followed by ` *(SCP 2026-11-24, #323: a sticker is named as a likely misread only under the proximity rule in the amendment below)*`

**P7, Story 10.3 amendment for #323 (:2005),** inserted after the paint-mode ACs:
```md
*Amended by SCP 2026-11-24 github-issue-triage (#323; settles action item `issue-174-deferred-suspect-distance-threshold`), built as an issue fix through `/bmad-orchestrate-issues`; v1.0 scope:*

**Given** a review whose color counts are off
**When** `SuspectRanking` ranks likely misreads
**Then** a scanned, unedited, non-center sticker of an over-counted color qualifies for an under-counted color when its measured Lab is at most `SUSPECT_MARGIN_DELTA_E` farther (CIE76) from that color's calibrated center than from the center of the color it was read as; `SUSPECT_MARGIN_DELTA_E` is 20 ΔE (PO 2026-11-24; twice `StickerClassifier.CLOSE_CALL_DELTA_E`) and is documented in code and in the spec; a qualifying sticker is suggested as the nearest under-counted color it qualifies for; up to 3 qualifying stickers are listed, ordered by margin (smallest first, then sticker index), and the list is never padded with stickers that do not qualify. The rule applies to Scan review only: the Editor has no measured colors and keeps its existing marker rule (Story 3.5, unchanged)

**Given** no candidate qualifies
**When** the review renders
**Then** no "This sticker may be {color} — check it." suggestion and no count-suspect marker shows; the color-count line and the piece problems (outlines, numbered piece markers, face-tab warning dots) still show, and *Accept* stays disabled

**Given** `SuspectRankingTest` and the review tests
**When** they run
**Then** they cover the release-session cases (Up 3 painted orange → blue, and Up 9 painted white → blue: no suggestion names the genuine blue Up 2, and the counts and the impossible corner still show), the #174 yellow-near-orange replay (still suggested "may be orange"), and a case with two under-counted colors (each qualifying sticker suggested as its nearest qualifying under-counted color, the list ordered by margin), and the spec records each case's margin
```

**P16, Story 10.3 amendment for #321 review *Close* (:2019),** inserted after P7, before Story 10.4:
```md
*Amended by SCP 2026-11-24 github-issue-triage (#321), built with the #321 issue fix; v1.0 scope:*

**Given** a scan review in which the Learner changed no sticker and completed no retake
**When** the Learner taps *Close*
**Then** the review returns `FellBackToEditor` carrying its stickers, and the caller decides what the Editor shows (the Solve tab seeds it, Story 10.15; every other caller opens it blank)

**Given** a scan review with a changed sticker or a completed retake
**When** the Learner taps *Close*
**Then** "Discard this scan?" shows; *Discard* returns `Cancelled`, and dismissing the question keeps the review exactly as it was

**Given** the review tests
**When** they run
**Then** they cover *Close* with no change (`FellBackToEditor` with the stickers) and *Close* with a change, both *Discard* (`Cancelled`) and dismissing
```

**P8, Story 10.5 AC2 (:2079), #321.** OLD ``FellBackToEditor` (*Enter colors instead*, or an invalid or incomplete scan) opens the Editor empty with a one-line note` → NEW ``FellBackToEditor` (*Enter colors instead*, or an invalid or incomplete scan) opens the Editor with a one-line note, seeded with the colours the scan read and the Learner's review fixes, unread stickers unset (SCP 2026-11-24, #321, Story 10.15; until then it opened empty); a denied permission or a scan with no captured face opens it blank`

**P9, Story 10.15 amendment (:2395), #321,** inserted after AC3 (`**Then** the Manual Cube Editor opens as the fallback, as Story 10.5 says`):
```md
*Amended by SCP 2026-11-24 github-issue-triage (#321), built as an issue fix through `/bmad-orchestrate-issues`; v1.0 scope (PO 2026-11-24: pulled into the current release, its v1.1 deferral removed); acceptance on the Lenovo Tab M10 Plus 3rd Gen and the Galaxy S24 FE, main app build, from the Solve tab:*

**Given** a Guided or Quick scan from the Solve tab that captured one or more faces (a reviewed scan that falls back, or a partially read scan, including *Enter colors instead* after faces were captured)
**When** it returns `FellBackToEditor`
**Then** the Manual Cube Editor opens seeded in the home frame with every sticker the scan read and every fix the Learner made in review, with the one-line note "Your scan is here. Fill in the stickers it couldn't read." (a new content string); stickers the scan did not read, and every sticker of a captured face whose rotation could not be worked out, are unset (not seeded), and a face holding one counts as not entered (Story 3.5's unset-sticker AC; EXPERIENCE.md *Invalid entered state*); the stickers travel in the typed `ScanOutcome` (Story 10.3), never filled from an expected state; *Accept* stays gated by the Editor's validation (Story 3.5); only an accepted scan counts as the Full verified baseline (Story 10.7 rule, unchanged)

**Given** denied camera permission, or a scan with no captured face
**When** the Editor opens
**Then** it opens blank with its one-line note "No scan this time. Enter your cube's colors here." (`msg.solve.scan-fell-back`), as today; that line is used only for the blank case

**Given** a scan fallback from any caller other than the Solve tab (Placement from Welcome → *Start from my cube* or Learn → *Find my place* → *Scan my cube*)
**When** it returns `FellBackToEditor`
**Then** its Editor opens blank, as today; seeding is the Solve tab's only

**Given** a scan accepted in review
**When** *Accept* is tapped
**Then** it still goes straight to solving and Solution Playback (#203, unchanged)

**Given** the Solve tab route tests
**When** they run
**Then** they cover review *Close* with no change (seeded Editor), review *Close* with a change and *Discard* (`Cancelled`, back on the Solve tab), a partially read scan, a captured face whose rotation is unresolved (left unset), a no-capture fallback and a denied permission (blank Editor with the old note), and a Placement fallback (blank)

**Given** a solve started from an accepted scan *(SCP 2026-11-19, #203)*
**When** the Learner cancels it, or the Solver times out or fails
**Then** the Manual Cube Editor opens with the scanned cube held in the home frame (`Orientation.HOME`, the same pieces), and its playback still counts as the Full verified baseline even if the Learner corrects colors there (the Story 10.7 rule); the scan stops counting only when the Editor is closed or the Learner leaves the Solve tab
```

**P10, Story 10.17 (:2503), #325.** OLD `**Then** Quick Scan resumes with the kept faces intact, or *Close* asks "Discard this scan?"` → NEW: the same, followed by ` *(SCP 2026-11-24, #325: unchanged; the standalone Guided Scan viewfinder's own Close follows Story 10.2's discard rule)*`. Story 10.16's rule (a completed retake counts as a change) is checked and unchanged.

**P11, Story 10.27 (:2777), #337.** Inserted after the Refs line: `*(SCP 2026-11-24 github-issue-triage, #337: this story audits the existing groups offline; Story 10.28 stops the recorder from saving a new group whose labels break the count rule. The piece check here still catches wrong labels that balance the counts. #338, corner placement, stays with AC2.)*`. AC1 is unchanged.

**P12, new Story 10.28 (:2807), #337:**
```md
### Story 10.28: The fixture recorder checks nine of each colour before it saves a corner group

As the developer recording corner fixtures,
I want the recorder to refuse a group whose labels cannot be a real cube, and to make me look at every side-face pre-fill,
So that the app's own confident misreads never become fixture labels.

**Refs:** FR-24 · recognition NFRs (Confidence calibration) · AD-9, AD-15 · Stories 10.12, 10.20, 10.21, 10.23, 10.27 · #337, #327, #338

**Depends on** Story 10.20's corner recorder. Device-free (JVM and UI rule tests). The recorder is a debug-only developer tool (AD-15), so this is not release scope. **Runs before** any new corner-fixture recording session; it blocks neither Story 10.21, Story 10.27 nor Story 10.11. *(SCP 2026-11-24 github-issue-triage, #337: the 2026-11-23 label review corrected 20 of 1,242 corner labels, 18 of them pre-filled, all 12 WHITE→BLUE on the side faces. #338, corner placement, is Story 10.27 AC2's and is not part of this story.)*

**Acceptance Criteria:**

**Given** a corner group whose two holds are both complete
**When** the operator finalizes it
**Then** `RecorderRules` counts the labels of both holds, centres included; if any colour is not exactly 9, the group is not saved, the recorder names each over- and under-counted colour with its count, and the pre-filled stickers of the over-counted colours are highlighted

**Given** a refused group
**When** the operator overrides
**Then** a non-empty reason is required and is written to the group's manifest (a new optional field; manifests without it still load), and an empty reason cannot override

**Given** a group saved with an override reason
**When** the group index and the fixture consumers read it
**Then** the index lists it separately with its reason, and Story 10.21 tuning and Story 10.23 scoring skip it unless the PO includes it by name; the skipped count is reported

**Given** `RecorderRulesTest`
**When** it runs
**Then** a group counting 10 WHITE / 8 BLUE is refused with both colours named, 9 of each is accepted, and an override saves its reason to the manifest

**Given** the pre-correction labels (`add0c79e`) of the 10 groups corrected on 2026-11-23 (`docs/evidence/10-27/label-corrections-2026-11-23.json`)
**When** they are replayed through the count check
**Then** all 10 are refused (a wrong label swapped with another wrong label of the same pair passes the count; Story 10.27's piece check covers it)

**Given** the corner-hold face review
**When** a face on either side face of the hold (face index 1 or 2) still has a pre-filled sticker the operator has not tapped (confirm or change)
**Then** that face cannot be confirmed, and a UI rule test pins it

**Given** an incomplete or retaken hold
**When** the group is finalized
**Then** the count check runs only once both holds are complete and always on the labels of the latest capture of each hold
```

**P12b, new Story 10.29 (:2847), #326 (v1.1):**
```md
### Story 10.29: Solution Playback shows the same Quick Check result as Lessons

As a Learner following a solution on my real cube,
I want a Quick Check in Solution Playback to show its result the way a Lesson does,
So that I understand what the check saw and how to recover, wherever I run it.

**Refs:** FR-26, FR-16, FR-9 · AD-3, AD-5, AD-20 · UX-DR12, UX-DR13, UX-DR20 · UX Flow 3 · Stories 10.6, 10.7, 14.3 · #326, #200

**Depends on** Story 10.7. **v1.1 scope** (PO 2026-11-24: stays a later-release story, as labelled on #326). **Human gate:** a device check on the Lenovo Tab M10 Plus 3rd Gen and the Galaxy S24 FE, main app build, Solve tab and a physical Lesson. The EXPERIENCE.md update for the shared result card is made when this story is created. *(SCP 2026-11-24 github-issue-triage, #326.)*

**Acceptance Criteria:**

**Given** a physical-mode Quick Check result in a Lesson or in Solution Playback
**When** it is shown
**Then** both hosts render one shared result component: the frame label, the Certainty chip from `tutor/Assess` (never a numeric confidence), "I can't tell what changed" for an unreadable result, and the recovery actions; the Certainty is announced to TalkBack once (#200)

**Given** the Lesson host
**When** a result action is tapped
**Then** it routes through the Lesson's step and recovery flow, unchanged

**Given** the Solution Playback host
**When** a result action is tapped
**Then** the equivalent recovery action routes through Solution Playback's existing `SessionEvent` → `reduce()` transitions; no ViewModel or composable mutates state

**Given** both hosts at a normal step and at completion
**When** a Quick Check returns a match, a probable mismatch and an unreadable result
**Then** tests cover each case on each host; the copy comes from content strings and fits at 200% font on the Lenovo's 600 dp width
```

**P13, Story 11.2 amendment (:3047), #324,** inserted after its last AC, before Story 11.3:
```md
*Amended by SCP 2026-11-24 github-issue-triage (#324), built as an issue fix through `/bmad-orchestrate-issues`; v1.0 scope; acceptance on the Lenovo Tab M10 Plus 3rd Gen and the Galaxy S24 FE, main app build, with `PLACEMENT` raised in debug:*

**Given** a solved cube accepted into Placement
**When** the outcome shows
**Then** it offers *Scan again*, *Start Stage 1* and *I can already solve it*, in that order; the reading and reason lines are unchanged (EXPERIENCE.md *Placement outcomes*)

**Given** *I can already solve it*
**When** it is tapped
**Then** the existing `EntryLevelScreen` (Story 14.1) opens; nothing is written until a level is chosen there (then `ChooseEntryLevel`, as Story 14.1 says), and Back returns to the solved outcome

**Given** the Placement tests
**When** they run
**Then** they cover all three actions and the Back navigation from the picker; the new label comes from content strings and fits at 200% font on the Lenovo's 600 dp width
```

**P14, Story 14.1 AC1 (:3811), #324.** OLD `**Given** Welcome → *I can already solve it*, or Learn → *Find my place* → *Choose my level*` → NEW `**Given** Welcome → *I can already solve it*, Learn → *Find my place* → *Choose my level*, or Placement's solved outcome → *I can already solve it* (SCP 2026-11-24, #324; Story 11.2)`

### 4.3 Not changed

- **PRD** (`prd.md`, FR-23 at `epics.md:59`): unchanged; see checklist 3.1.
- **Architecture spine:** unchanged; see checklist 3.2.
- **DESIGN.md:** unchanged; #313 reuses the outlined *Close* style.
- **`sprint-status.yaml`, code, `_bmad/`, `.claude/`:** not touched by this proposal (section 5 lists the follow-ups).

### 4.4 Conditional proposal for saved calibration profiles (carried from the draft)

[#173, automatic calibration profiles](https://github.com/hebelmx/RubikXplorer/issues/173) already has a product-owner deferral. [#172, reuse a solve's calibration in Quick Check](https://github.com/hebelmx/RubikXplorer/issues/172) is closed after [PR #233](https://github.com/hebelmx/RubikXplorer/pull/233). The outstanding prerequisite is a cube-A-to-cube-B experiment on the physical devices. Promote the proposal only if that experiment shows harm and maintains zero false VERIFIED results. If promoted, revise FR-24 (Color Calibration) from one device calibration to bounded local profiles matched automatically, update the calibration glossary and reset behaviour, and specify the database migration and single writer in the architecture update. Require an unmatched profile to offer calibration rather than report certainty. Queue implementation after the experiment and a separate approval. No edit is made for it now.

## 5. Implementation handoff

**Scope: Moderate.** PO/SM reorganize the backlog; Developer agents build.

| Issue | Then | Owner / route | What the change must now do |
| --- | --- | --- | --- |
| #323 | build | `/bmad-orchestrate-issues` against Story 10.3 (amended) | In Scan review only, `SuspectRanking` lists a sticker only when it is within `SUSPECT_MARGIN_DELTA_E` = 20 ΔE (relative, PO 2026-11-24) of an under-counted colour; it is suggested as the nearest qualifying under-counted colour, the list is ordered by margin; no qualifying sticker → no "may be X" suggestion or count-suspect marker, counts and piece diagnostics stay; Editor markers unchanged; tests for Up 3 orange→blue, Up 9 white→blue, the #174 yellow/orange replay and a two-under-counted case. SM marks `issue-174-deferred-suspect-distance-threshold` done when it merges |
| #324 | build | issue fix against Story 11.2 (amended) and 14.1 | Solved Placement outcome shows *Scan again* / *Start Stage 1* / *I can already solve it*; the last opens `EntryLevelScreen`, writes nothing by itself, Back returns to the outcome; tests for all three actions and Back |
| #325 | build | issue fix against Story 10.2 (amended); first of two PRs | Viewfinder *Close*/Back from every caller (Welcome, Solve, Find my place / Re-placement, Quick Check full scan): 0 faces → leave at once; ≥ 1 face → "Discard the faces you captured?" (*Keep scanning* keeps faces, *Discard* cancels to the caller's previous screen); retake and Quick Scan hand-over rules unchanged; tests on every caller |
| #313 | build | separate PR after #325, same Story 10.2 amendment (PO 2026-11-24) | *Close* in the same control group as the shutter and face prompt in tablet landscape, TalkBack order, 200% font |
| #321 | build | issue fix against Stories 10.5/10.15 (amended); PO/SM removes the `v1.1` label on GitHub | Solve tab only: `FellBackToEditor` after a scan with captured faces (including review *Close* with no change) opens the Editor seeded (scanned colours + review fixes, home frame) with "Your scan is here. Fill in the stickers it couldn't read."; unread stickers and every sticker of an unresolved-rotation face are unset (blank well, "not set", tap then colour; a face is entered at 9 set; no count/piece error while any is unset); review *Close* with a change → "Discard this scan?" → *Discard* = `Cancelled`; denied permission / no capture → blank Editor with the old note; Placement fallbacks stay blank; direct Accept → playback unchanged; only an accepted scan is the verified baseline |
| #337 | story | SM adds `10-28-fixture-recorder-checks-nine-of-each-colour-before-saving`; `/bmad-orchestrate story` | Recorder refuses a group not counting 9 of each colour (names over/under colours, highlights over-colour pre-fills) unless a non-empty override reason is saved to the manifest; overridden groups are listed separately in the group index and skipped by 10.21 tuning and 10.23 scoring unless the PO includes them; replay of the 10 pre-correction groups (`add0c79e`) refuses all 10; untouched side-face pre-fill blocks face confirm |
| #104 | story | SM adds `3-9-measure-and-shorten-cold-solver-preparation` (epic-3 back to in-progress); v1.1 | Measure ≥ 5 cold starts on the Lenovo (release-equivalent), preparation and search separate; PO target before any mitigation; one bounded mitigation compared on the same method; corpus verified, off-main, copy unchanged |
| #326 | story | SM adds `10-29-solution-playback-shows-the-same-quick-check-result-as-lesso` (v1.1); UX edit made at story creation | One shared Quick Check result component in Lessons and Solution Playback; Playback actions through its own `reduce()` transitions; tests for match, probable mismatch and unreadable at a normal step and at completion |

New story keys (SM to add to `sprint-status.yaml` as `backlog`; this proposal does not edit it):
- `3-9-measure-and-shorten-cold-solver-preparation` — Story 3.9: Measure and shorten cold solver preparation (v1.1)
- `10-28-fixture-recorder-checks-nine-of-each-colour-before-saving` — Story 10.28: The fixture recorder checks nine of each colour before it saves a corner group
- `10-29-solution-playback-shows-the-same-quick-check-result-as-lesso` — Story 10.29: Solution Playback shows the same Quick Check result as Lessons (v1.1)

**Success criteria:** each issue's "done when" from the trigger list holds on the main app build, with the tests named above; device checks on the Lenovo TB128XU and Galaxy S24 FE for #321, #324, #325, #313; each accepted story or fix links its GitHub issue, and issue closure is reconciled after verification.

## 6. PO decisions

| # | Question | Decision |
| --- | --- | --- |
| Q1 | #323 proximity rule and value | **Decided (PO 2026-11-24):** a relative margin of 20 ΔE (CIE76; 2 × `StickerClassifier.CLOSE_CALL_DELTA_E`): a sticker qualifies for an under-counted colour when it is at most 20 ΔE farther from that colour's calibrated centre than from its read colour's centre. |
| Q2 | #324: does the solved outcome's copy change? | **Decided (PO 2026-11-24):** no; both lines are kept and only *I can already solve it* is added. |
| Q3 | #313 + #325: one story or two issue PRs? | **Decided (PO 2026-11-24):** two issue PRs under the same Story 10.2 amendment, #325 first, then #313. |

Defaults chosen by the orchestrator under PO instruction 2026-11-24 (review findings that needed a product choice):

| Finding | Default |
| --- | --- |
| F1 (#321) | Review *Close* with no change returns `FellBackToEditor` with the stickers (seeded on the Solve tab); with a change, "Discard this scan?" and *Discard* returns `Cancelled` |
| F2 (#321) | An unset sticker renders as the review net's blank well ("?", no colour), TalkBack "not set"; tap it, then a colour; a face is entered once all 9 are set; no count or piece error while any sticker is unset |
| F3 (#321) | Seeded-case note "Your scan is here. Fill in the stickers it couldn't read." (new content string); "No scan this time. Enter your cube's colors here." stays for the blank case only |
| F4 (#321) | A captured face whose rotation is unresolved is left unset, not seeded |
| F5 (#321) | Seeding is Solve-tab only; Placement (Welcome → *Start from my cube*, *Find my place* → *Scan my cube*) fallbacks stay blank |
| F6 (#325) | The confirm applies to every Guided viewfinder caller (Welcome, Solve, Find my place / Re-placement, Quick Check full scan); *Discard* returns to the caller's previous screen |
| F8 (#323) | A sticker qualifies per under-counted colour; it is suggested as the nearest qualifying one; the list is ordered by margin |
| F9 (#323) | The proximity rule is Scan review only; the Editor's marker rule is unchanged |
| F15 (#337) | Override-saved groups are listed separately in the group index and excluded from 10.21 tuning and 10.23 scoring unless the PO includes them |

## 7. Adversarial review (revision 2, 2026-11-24)

Applied: F1–F6, F8, F9 and F15 (defaults above); F10 (Q1–Q3 marked decided, "20 ΔE (PO 2026-11-24)" in Story 10.3 and X5, status approved pending merge); F11 (Order line: 10.28 after 10.20, independent of 10.27, before any new corner-fixture recording); F19 (#313 is a separate PR after #325 under the same Story 10.2 amendment).

### Review findings not applied (minor)

These are recorded so they are not lost. They are candidates for the issue-fix specs or later story creation.

- F7 (#325): the text does not say whether auto-capture and the shutter pause while "Discard the faces you captured?" shows, or what system Back does on the question. Suggested: paused, and Back = *Keep scanning*.
- F12: the Epic 10 Order annotation leaves out Story 10.5 (#321) and Stories 11.2/14.1 (#324). Check whether the current Order line already covers them.
- F13 (#324): the Entry level picker opened from Placement's solved outcome still offers *Not sure? Scan my cube*, which allows a Placement → picker → Scan loop. Suggested: hide it from that entry.
- F14 (#324): after a level is chosen from the Placement entry (including Re-placement), the destination and onboarding completion are not stated. Suggested: the same as Story 14.1's Welcome/Learn entries.
- F16 (#337): Story 10.28 highlights only pre-fills of over-counted colours, so the 2 corrected errors the operator entered get no pointer.
- F17 (#104): Story 3.9 has no exit if its one bounded mitigation misses the target, and AC3 ("one mitigation") and AC4 ("the chosen mitigation") are inconsistent.
- F18 (#104): reopening epic-3 for a v1.1 story makes sprint-status show a v1.0 epic as in progress.
- F20 (#323): Story 10.3's test AC does not carry Q1's rule that the build stops for the PO if the #174 replay does not qualify at 20 ΔE.
