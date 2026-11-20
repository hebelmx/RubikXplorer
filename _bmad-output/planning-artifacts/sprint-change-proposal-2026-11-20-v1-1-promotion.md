# Sprint Change Proposal: promote eight v1.1 issues into v1.0 (2026-11-20)

**Status: Approved by the product owner, hebelmx, on 2026-11-20. Every recommendation marked "PO approved 2026-11-20" was approved as written; the camera permission in v1.0 is approved; Story 10.18 detects the cube's real edges and corners. The planning edits in section 4 are applied on branch `docs/scp-2026-11-20-v1-1-promotion`.**

- **Trigger:** GitHub issues #249, #251, #252, #253, #208, #209, #210, #198 (labelled v1.1 / deferred), promoted by the PO on 2026-11-20.
- **Mode:** Batch
- **Scope:** **Major, a scope move between releases (v1.1 → v1.0).** Two new epics (14, 15), two new stories in Epic 10 (10.17, 10.18), amendments to FR-4, FR-14, FR-16, FR-18, FR-22, FR-26, FR-27, a new FR-30, a new AD-21 and amendments to AD-3, AD-9, AD-10, AD-20, and the camera features FR-21–FR-27 move from v1.1 into the v1.0 release.
- **Product owner decisions (hebelmx, 2026-11-20):**
  1. Promote all eight issues out of the v1.1 deferral ("promote all, since we are almost done with everything else").
  2. One Sprint Change Proposal covers all eight.
  3. #249, #251, #252 and #253 continue as code builds once this proposal is approved; #208, #209, #210 and #198 become new stories or epics in `epics.md` for `/bmad-orchestrate`.
  4. Placement only picking a Stage is fine as designed; #251's missing piece is explaining the scanned cube, the reason, and a suggested Lesson or Algorithm.
  5. #252 needs a re-placement entry outside Welcome that does not reset progress.
  6. The Lenovo Tab M10 Plus 3rd Gen (TB128XU) camera is the minimum camera spec.
  7. #198 still depends on the 3D corner-grid Quick Scan rework; sequence its story after that work.
  8. **(Update, same day.)** We are ahead of schedule: **all of these features ship in v1.0, not v1.1.** Their dependencies, including the corner-grid Quick Scan rework, move into v1.0 too.
  9. **(Approval, same day.)** The proposal is approved. Every recommendation is approved as written (now marked "PO approved 2026-11-20"); the camera permission in v1.0 is approved, with the follow-ups in section 5; Story 10.18 detects the cube's real edges and corners in the camera frame, and no perspective-correct guide sketch is used.

## 1. Issue summary

Device session C (2026-11-18 to 2026-11-20, Lenovo Tab M10 Plus 3rd Gen and Galaxy S24 FE) and PO testing surfaced eight improvements that were parked as v1.1:

| Issue | Problem | Evidence |
|---|---|---|
| #253 | The Manual Cube Editor makes each face be entered in a fixed hold it never shows. One face entered at 180° made the cube impossible to accept, with a generic "Some corners show colors that no real corner has". Errors also show before the faces are entered, auto-advance is invisible, and check marks disappear on green. | `docs/evidence/session-c/notes.md` (Path C); screenshots `sm-s721u1_editor_up.png`, `sm-s721u1_editor_D.png` |
| #252 | Placement can only be reached from Welcome, which shows only with no progress. Placing yourself again means Reset progress and a restart. | Session C notes |
| #251 | Placement shows only "Start at *Stage*?". It doesn't say what it read or why. Edge cases read poorly: a cube one `U'` from solved got "Start with a white cross"; a yellow-first solve got no credit. | Session C Placement table; #251 comments |
| #249 | Fixing a whole face on Scan review takes about 16 taps. | Session C notes |
| #208 | Learning happens only on the virtual cube; Algorithms can't be practised on a real cube, and Quick Check is only offered at Objective boundaries. | PO testing, party 2026-11-18 |
| #209 | *I can already solve it* walks through every Stage check, which doesn't suit a Learner who wants to start at 2-look or the full sets. | PO testing |
| #210 | The Solver's optimal solution teaches no method. | PO testing |
| #198 | Leaving Quick Scan for the Guided Scan starts from zero. | Session C (Story 10.8) |

The planning had each of these out of scope: `epics.md:2272` (retake in the Quick Scan review a v1.1 follow-up with #198), `prd.md:385` and `prd.md:624` (a "teaching solve" is P2), `prd.md:738` ("no progress sentence" on Placement), `EXPERIENCE.md:28` (Placement only from Welcome), `epics.md:830` (generic editor errors).

## 2. Impact analysis

### Checklist

| # | Item | Status | Note |
|---|---|---|---|
| 1.1 | Trigger | [x] | The eight issues; session C evidence |
| 1.2 | Problem type | [x] | New requirements from the stakeholder after device testing (all eight), plus a misunderstanding of the editor's hold (#253) |
| 1.3 | Evidence | [x] | Session C notes and screenshots; issue comments |
| 2.1 | Current epics | [x] | Epics 3, 7, 9 and 11 are done; their stories keep their ACs. Stories 3.5, 10.3 and 11.2 get amendment ACs that the issue builds must meet; Stories 7.7 and 9.5 get pointers. |
| 2.2 | Epic changes | [!] | New Epic 14 (#208, #209), new Epic 15 (#210), new Stories 10.17 (#198) and 10.18 (#195 real edge and corner detection, the #198 dependency). |
| 2.3 | Later epics | [!] | Epic 12: release Stories 12.3, 12.4 and 12.5 now run after all of this. Epic 13: the privacy facts change when the release declares `CAMERA` (see 3.4). |
| 2.4 | Obsolete or new epics | [!] | New Epics 14 and 15. Nothing becomes obsolete. |
| 2.5 | Order | [!] | v1.0 now includes Story 10.11 (raise `CAMERA_SCAN`, `QUICK_SCAN`, `PLACEMENT`), which until now gated only v1.1. Order in section 5. |
| 3.1 | PRD | [!] | FR-4, FR-14, FR-16, FR-18, FR-22, FR-26, FR-27 amended; FR-30 added (§4.10); §4.8 release banner and §6.2 moved to v1.0; §6.2 P2 "teaching solve" points at FR-30; §10 decision added. MVP grows: camera recognition (except FR-28) returns to v1.0. |
| 3.2 | Architecture | [!] | AD-8 **unchanged**. New AD-21 (method-aware solutions come from the Course, never from `CubeSolver`). AD-3 (Algorithm practice is a `SOLVE_PLAYBACK` session), AD-9 (Editor and Quick Scan hand-over go through `ScanAssembler`; v1.0 status), AD-10 (`StageSkippedByChoice`; only-add rule for re-placement), AD-20 (labeled segments), Ownership Matrix (`ChooseEntryLevel`), Capability Map. |
| 3.3 | UX | [!] | EXPERIENCE.md: IA (Welcome, Learn *Find my place*, Editor, Algorithm detail, Algorithm practice, Solution Playback), Physical controls, Sticker review net (paint mode, Editor), Quick Scan capture, State Patterns (Entry level, Placement outcomes, Re-placement, Invalid entered state), TalkBack, Flow 8 rewritten, new Flows 10 and 11, release note. DESIGN.md: update-cue ring, paint mode, Editor tabs and hold bands, Placement result, Stage row. This replaces a separate `bmad-ux` pass. |
| 3.4 | Other artifacts | [!] | `sprint-status.yaml` (not touched here): add Stories 10.17, 10.18, epic-14 with 14.1–14.3, epic-15 with 15.1–15.3, and move 10.11 into the v1.0 path. `AGENTS.md` says camera work is v1.1 and flags stay OFF for release: refresh it with `bmad-project-context` after approval. Raising camera flags in the release adds `CAMERA` to the manifest, so `docs/privacy-facts.json` and the website privacy page (Epic 13) change through their CI check, and the Play data-safety answers must be re-confirmed ("no data collected" still holds: frames stay in memory). Copy goes in `en.json`. |
| 4 | Path forward | [x] | Direct adjustment plus a release-scope move (section 3) |
| 6.4 | sprint-status | [N/A here] | Left to the orchestrator or `bmad-sprint-planning` (out of this worker's remit) |

### Interactions between the issues

- #251 and #252 share the Placement result: the re-placement entry (#252) shows the same reading and reason (#251).
- #209 and #252 share one Learn-tab entry, *Find my place*: *Scan my cube* (re-placement) and *Choose my level* (the entry-level picker). With `PLACEMENT` OFF only *Choose my level* shows.
- #249 and #253 share the sticker review net component: paint mode is built once and works in both (PO approved 2026-11-20, Q5).
- #208 and #210 share the AD-20 segment labels: *Set-up* / *Algorithm*, and Stage / Case segments.
- #198 needs Story 10.18 (detect the cube's real edges and corners in the frame, #195, with #196 and #197); both are now v1.0.

## 3. Recommended approach

**Direct adjustment with a release-scope move.** Amend the done stories with acceptance criteria for the four issue builds, add Stories 10.17 and 10.18 and Epics 14 and 15, and move the camera features these depend on into v1.0. AD-8 stays as it is: method-aware solutions are built from the Course's predicates and the Library's Algorithms (AD-21), so the Solver still never teaches.

- **Alternatives considered:** keeping #210 as a v1.x epic (rejected by PO decision 8); explaining Solver output in beginner terms (rejected: breaks AD-8 and Principle 3); a new `SessionKind` for Algorithm practice (not needed: `SOLVE_PLAYBACK` already has *Done*, Expected State, mismatch ladder and Quick Check).
- **Effort:** High overall. Issue builds: #249 low, #251 medium, #252 low–medium (the use case already only adds), #253 medium. Stories: 14.1 medium (new event type), 14.2 medium, 14.3 low, 10.17 medium, 10.18 high (edge and corner detection in the frame), 15.1 high (segment search), 15.2 medium, 15.3 medium.
- **Risk:** Medium–high. The largest are 10.18 (detecting the cube's real edges and corners on the minimum device) and 15.1 (search time on the minimum device). Moving camera features into v1.0 also makes the release depend on Story 10.11's evidence and on a privacy-facts update.
- **Timeline:** v1.0 release Stories 12.3, 12.4 and 12.5 move after this work. If 10.18 slips, the PRD cut order still applies: Quick Scan can ship as BETA, or OFF with #198 held, without blocking the rest (PO approved 2026-11-20, Q8).

## 4. Detailed change proposals (applied)

Line numbers are after the edits.

### PRD: `prd.md` (frontmatter `updated: 2026-11-20`)

- **FR-4 (line 227):** NEW consequence: a Learner who can already solve can choose an entry level with no check; earlier Stages are *skipped* (self-chosen, recorded as such), never *mastered*; nothing is re-locked or downgraded; the check remains. *(#209)*
- **FR-14 (366–367):** NEW consequences: any face rotation, asked only when unresolvable or ambiguous; specific messages; no piece errors while a new entry's faces are not entered. *(#253)*
- **FR-16 Out of Scope (388):** OLD "A "teaching solve" … (§6.2, P2)." → NEW: still no strategic explanation of Solver output; method-aware solutions are FR-30, built by the Course's methods. *(#210)*
- **FR-18 (421):** NEW bullet: practice on a real cube (set-up then Algorithm, *Done*, Quick Check at any step). *(#208)*
- **FR-22 (493):** NEW: switch from Quick Scan to the Guided Scan at any time, keeping captured faces. *(#198)*
- **FR-26 (529):** NEW: Quick Check available at any step, including mid-Algorithm. *(#208)*
- **FR-27 (538–539):** NEW: cube reading and reason before the suggestion; a ≤ 2-Moves-from-solved outcome first; credit for a non-white solve; TalkBack. Re-placement outside Welcome only adds progress. *(#251, #252)*
- **§4.8 banner and §6.2:** OLD "Release: v1.1, flag-gated … The AD-18 flags stay OFF in v1.0." → NEW: FR-21–FR-27 are v1.0 per this SCP; Story 10.11 raises `CAMERA_SCAN`, `QUICK_SCAN`, `PLACEMENT` for the v1.0 release; FR-28 keeps its own release decision.
- **NEW §4.10, FR-30 (575–):** method-aware solutions (beginner, 2-look, full OLL + PLL), Stage/Case-tagged segments, preferring *Learning*/*Learned* Algorithms, never by re-labelling Solver output, verified on the FR-15 corpus; Solve tab default stays optimal; CFOP F2L, Roux and ZZ stay P2. *(#210)*
- **§6.2 P2 (645):** "teaching solve" annotated as moved into v1.0 as FR-30.
- **§10 (759–760):** the 2026-11-18 "no progress sentence" decision marked superseded; NEW 2026-11-20 decision line (promotion, v1.0 scope move, Lenovo camera as minimum).

### Architecture: `ARCHITECTURE-SPINE.md` (frontmatter `updated: 2026-11-20`, binds FR-30, scope line)

- **AD-3 (95):** NEW: Algorithm detail stays outside sessions; *I'm doing it on my cube* starts a `SOLVE_PLAYBACK` session (set-up Moves then the Algorithm); no new `SessionKind`.
- **AD-8:** unchanged.
- **AD-9 (status line, 257):** the Editor's per-face rotation and the Quick Scan hand-over use `ScanAssembler`; v1.0 status; the release carries the camera source set and `CAMERA`.
- **AD-10 (289–290):** NEW event type `StageSkippedByChoice`; NEW "only add, never downgrade" rule for `PlaceFromScan` and `ChooseEntryLevel` (matches today's `PlaceFromScan`, which skips only `LOCKED`/`IN_PROGRESS` earlier Stages).
- **AD-20 (411):** NEW: labeled segments; labels are content keys naming what is practiced; per-Move notes still only `MoveNote`.
- **NEW AD-21 (413–):** `MethodSolve` in `:core:domain`; never calls `CubeSolver`; first-two-layer segments by the `RecoveryPlanner` bounded `preservedPieces` search against Stage predicates; LL by `LastLayerMatcher` and Library Algorithms, preferring *Learning*/*Learned*; Effect with timeout; re-verified.
- **Ownership Matrix (431):** Stage progress writers add `ChooseEntryLevel`. **Capability Map (545–):** FR-17–18 row adds AD-3; NEW FR-30 row.

### UX: `EXPERIENCE.md` (frontmatter `updated: 2026-11-20`)

- **Foundation:** NEW release note: these features and their camera dependencies are v1.0, still behind their flags.
- **IA (28–40):** Welcome → entry-level picker; Learn → *Find my place* (*Scan my cube* / *Choose my level*); Editor any rotation; Algorithm detail → *I'm doing it on my cube*; NEW Algorithm practice row; Solution Playback → *Solution style* (FR-30).
- **Voice table (63):** three skip records.
- **Physical controls (94):** Quick Check icon at every step; Physical-mode switch in every Lesson including 2-look.
- **Sticker review net (98):** NEW paint mode (Scan review and Editor) and NEW Editor rules (any rotation through `ScanAssembler`, neighbour-center bands, face-tab ✓ and warning dot, face-done line, pulsing cursor, orientation question copy); Quick Scan retake now via Story 10.17.
- **Quick Scan capture (101):** NEW *Scan face by face* switch keeping captured faces.
- **State Patterns (129–136):** NEW Entry level row; Placement outcomes rewritten (reading, reason, suggestion, set-up line; outcome order: solved, 1–2 turns, nothing solved, non-white, partly solved, at LL); NEW Re-placement row; Invalid entered state gains specific messages and "faces left to enter".
- **Accessibility (185):** NEW TalkBack lines for paint mode, Editor and Placement.
- **Flow 8 (297–):** rewritten for the level picker; NEW Flow 10 (re-placement) and Flow 11 (Algorithm practice); Surface Coverage updated.

### UX: `DESIGN.md` (frontmatter `updated: 2026-11-20`)

- **Sticker review net (269–272):** update-cue dot gets a 1.5dp `cubie-body` ring, contrast ≥ 3:1 on all six stickers (fixes the green case); NEW paint-mode visuals; NEW Editor face tabs (✓, `sticker-highlight` dot, never red), 6dp neighbour bands, face-done line; numbered markers reused.
- **Color palette (276):** long-press locks a swatch. **NEW Placement result (277):** reading `body`, reason `meta`, suggestion `title`, set-up note `meta`; text only. **Stage row (290):** chosen-level skip reads "Skipped".

### Epics: `epics.md` (frontmatter `updated: 2026-11-20`)

- **Requirements Inventory:** FR-4, FR-14, FR-18, FR-22, FR-26, FR-27 amended; NEW FR-30; UX-DR12, UX-DR16, UX-DR25 amended. **FR Coverage Map:** FR-4, FR-18, FR-22, FR-26 extended; NEW FR-30 → Epic 15.
- **Epic List:** Epic 10 order line (11.1 → 10.18 → 10.17), Epic 10 and 11 v1.0 notes, the "never blocks 12.3–12.5" and "never gate them" notes marked superseded; NEW Epic 14 and Epic 15 entries; NEW SCP summary paragraph with the v1.0 scope move.
- **Story 3.5 (850–):** NEW amendment ACs for #253 (rotation resolve, orientation question, "faces left to enter", specific messages, auto-advance cues).
- **Story 7.7 (1508):** pointer to Story 14.1. **Story 9.5 (1780):** pointer to Story 14.2.
- **Story 10.3 (1932–):** NEW amendment ACs for #249 (paint mode, TalkBack, exit rules, 1 + 9 touches).
- **Story 10.16 (2272 note):** OLD "retake in the Quick Scan review is a v1.1 follow-up noted with #198" → NEW appended "now v1.0, through Story 10.17".
- **NEW Story 10.17 (2366–):** switch from Quick Scan to the Guided Scan keeping captured faces. *(#198)*
- **NEW Story 10.18:** "Quick Scan finds the cube's real edges and corners": the camera adapter detects the cube's real edges and corners in the frame and unwarps each face from them (no perspective-correct guide sketch, PO approved 2026-11-20), with capture cues and dark-background coaching. **PRD FR-22** gains the same rule. *(#195, #196, #197; the #198 dependency, now v1.0)*
- **Story 11.2 (2460–):** NEW amendment ACs for #251 and #252.
- **NEW Epic 14 (3220–):** 14.1 Choose my entry level; 14.2 Practice an Algorithm on my real cube; 14.3 Practice every Lesson step on my real cube, with a Quick Check at any step.
- **NEW Epic 15 (3316–):** 15.1 Beginner-method solution from the Course's Stages; 15.2 Last-layer segments for 2-look and full OLL + PLL; 15.3 Choose a solution style in Solution Playback.

## 5. Implementation handoff

- **Scope:** Major (scope move v1.1 → v1.0). Approved by the PO on 2026-11-20. Route: the orchestrator (`/bmad-orchestrate` for stories, `/bmad-orchestrate-issues` for issue builds). Codex is unavailable until 2026-11-23, so builds wait or run with Codex off.
- **Still to do outside this worker:** update `sprint-status.yaml` (10.17, 10.18, epic-14, epic-15, 10.11 in the v1.0 path).
- **Follow-ups for the camera permission in v1.0 (PO approved 2026-11-20):**
  1. Refresh the privacy facts (`docs/privacy-facts.json`, derived from the release manifest) and the website privacy page in `hebelmx/cubentia-site` (Epic 13) so that they state the `CAMERA` permission and that frames are processed in memory only, never saved or sent.
  2. Review the Play Console **Data safety** answers again for the camera release ("no data collected" still holds, since frames stay in memory on the device), before Story 12.5.
  3. Refresh `AGENTS.md` through `bmad-project-context`: it still says camera work is v1.1 and camera flags stay OFF for release; it should say that `CAMERA_SCAN`, `QUICK_SCAN` and `PLACEMENT` are raised for v1.0 by Story 10.11.
- **Order (PO approved 2026-11-20):** #253 → #249 → #251 → #252 → 14.1 → 14.2 → 14.3 → 11.1 → 10.18 → 10.17 → 15.1 → 15.2 → 15.3 → 10.11 (raise `CAMERA_SCAN`, `QUICK_SCAN`, `PLACEMENT` for v1.0) → 12.3 → 12.4 → 12.5.
- **Issue handoff:**

| Issue | Then | What the code change must do |
|---|---|---|
| #253 | build | Editor: resolve per-face 90/180/270° turns through `ScanAssembler` and accept silently when unique (redraw and a `meta` note); otherwise name the face and hold (TalkBack); no piece/count errors until all faces are entered on a fresh entry; specific count and piece messages, markers and tab dots; pulsing next-sticker cursor, face-done ✓ and line with TalkBack; update-cue ring legible on green. ACs: Story 3.5 amendment. |
| #249 | build | Shared review net: long-press (or accessibility action) locks a palette color; taps paint; locked swatch outlined with ✓; exit by tap/long-press on it, a plain tap on another color, or retake; TalkBack on/off; plain tap behavior unchanged. ACs: Story 10.3 amendment. |
| #251 | build | Placement result: reading, reason, suggestion, set-up line (content strings, TalkBack order); a ≤ 2-Move outcome before predicates (bounded enumeration, no Solver); non-white credit with *First cross* and *Show me the last layer anyway*; partly solved counts from `ObjectiveProgress`; Case name at the LL when unique. ACs: Story 11.2 amendment. |
| #252 | build | Learn top bar *Find my place* → *Scan my cube* (`PLACEMENT`-gated) with the "keeps your progress" note; re-placement through `PlaceFromScan`, only adding progress; "You've already reached *Stage*." with no write. ACs: Story 11.2 amendment. |
| #209 | story | Story 14.1: entry-level picker, `StageSkippedByChoice` via `ChooseEntryLevel`, nothing downgraded, check kept. |
| #208 | story | Stories 14.2 and 14.3: *I'm doing it on my cube* on every Algorithm (set-up + Algorithm, `SOLVE_PLAYBACK`, *Done*); Physical mode in every Lesson incl. 2-look; Quick Check at any step. |
| #210 | story | Epic 15: `MethodSolve` (AD-21) for beginner, 2-look, full OLL + PLL; segment labels; *Solution style* in playback, *Shortest* default. |
| #198 | story | Story 10.17 after Story 10.18 (#195, real edge and corner detection): *Scan face by face* keeps captured faces, Guided Scan only for the rest, assembled together. |

- **Success criteria:** each item's ACs pass on the main app build in a device session on the Lenovo Tab M10 Plus 3rd Gen (minimum camera spec) and the Galaxy S24 FE; Story 10.11's evidence supports BETA or GA for each raised flag; Stories 12.3–12.5 pass with the camera flags raised.

## 6. Decisions on the open questions (PO approved 2026-11-20)

Each was approved as recommended and is written into the artifacts.

1. **#252 entry point:** *(PO approved 2026-11-20.)* *Find my place* in the Learn tab top app bar, shared with *Choose my level* (#209). Alternatives: Solve Home, Settings, Recognize.
2. **#252 re-placement rules:** *(PO approved 2026-11-20.)* never re-lock or downgrade; earlier `IN_PROGRESS` Stages become *Skipped by placement* (today's `PlaceFromScan` behavior; their Lesson completions and Checkpoints are kept); stays behind `PLACEMENT`.
3. **#251 non-white solve:** *(PO approved 2026-11-20.)* suggest *First cross* (Stage 2, not Stage 1 as today), and add *Show me the last layer anyway* (suggests *Last-layer orientation*) when the first two layers are solved from another color.
4. **#251 net highlight:** *(PO approved 2026-11-20.)* no cube net on the Placement result in this pass, text only; add the line "The lesson sets up its own cube, so you can put yours aside."
5. **#249 in the Editor too:** *(PO approved 2026-11-20.)* yes, paint mode lives in the shared sticker review net and works in the Manual Cube Editor as well.
6. **#253 silent resolve:** *(PO approved 2026-11-20.)* "silent" means no question, but the net redraws the turned face with a one-line `meta` note ("We turned the Up face to match the others.") so the screen matches the accepted cube.
7. **#253 release:** *(PO approved 2026-11-20.)* pulled in now (decisions 1 and 8); built first, since it is a v1.0 Solve-tab surface.
8. **#209 no check:** *(PO approved 2026-11-20.)* choosing a level skips Stages without a check and is recorded as `StageSkippedByChoice`, so the record stays honest; the scramble check stays as a fourth choice.
9. **#198 / 10.18 slip:** *(PO approved 2026-11-20.)* if the edge and corner detection of Story 10.18 misses, Quick Scan ships BETA (or OFF with 10.17 held) under the PRD cut order without blocking the rest of v1.0.
10. **v1.0 camera release:** *(PO approved 2026-11-20; follow-ups in section 5.)* raising `CAMERA_SCAN`, `QUICK_SCAN` and `PLACEMENT` for v1.0 adds the `CAMERA` permission; Story 10.11's checklist, the privacy facts and the store data-safety answers must be redone before 12.3–12.5. `CASE_RECOGNITION` keeps its own decision at 10.11.
