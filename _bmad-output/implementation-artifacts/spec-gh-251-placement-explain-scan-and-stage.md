---
title: 'GH #251: Placement tells the Learner what the scan saw and why this Stage'
type: 'feature'
created: '2026-11-20'
status: done
baseline_revision: '0228ad0ad2d16ea57d66a5a5a196315198975db3'
review_loop_iteration: 0
followup_review_recommended: false
context:
  - '{project-root}/AGENTS.md'
  - '{project-root}/_bmad-output/implementation-artifacts/epic-11-context.md'
warnings: ['oversized']
deferred: []
---

<intent-contract>

## Intent

**Problem:** Placement's result only says "Start at *Stage*?". It never says what it read on the Learner's cube or why it picked that Stage. Edge cases read badly: a cube one `U'` from solved is told "Start with a white cross", and a first-two-layers solve from yellow gets no credit (device session C, #251 comments).

**Approach:** `Placement.evaluate` returns richer, ordered outcomes: Solved, 1–2 turns from solved (bounded enumeration, no Solver), Nothing solved, Non-white solve, Partly solved (with the next Stage's own `ObjectiveProgress`), and At the last layer (with the Case when exactly one fits). `PlacementScreen` shows reading → reason → suggestion → options → set-up line from content strings, per EXPERIENCE.md *Placement outcomes* and DESIGN.md *Placement result*. Done when the Story 11.2 "#251" amendment ACs in `epics.md` hold.

## Boundaries & Constraints

**Always:**
- Outcome order, first match wins: Solved → 1–2 turns → (predicate evaluation) Nothing solved / Non-white → Partly solved → At the last layer.
- AD-8: the 1–2-turn check enumerates every single face turn (6 faces × 3 turns), then every pair on two different faces, on the accepted `CubeState`; never the Solver. A 1-turn result wins over a 2-turn result.
- Turns are named in words by the turned face's **center color** (Learner-facing `toFacelets()` center of the `FaceTurn.face`) and direction, since the Learner's hold is unknown.
- AD-6: all text is `Text(key, args)` in `en.json`; Stage names come from the Stage title keys; partly-solved counts come from the Stage Predicates' `progress`. Case naming uses `LastLayerMatcher` (AD-19) with the LL read as in `RecognizeCase.fromCube` (`LastLayerView.of(state, Color.YELLOW)`).
- DESIGN.md: reading `bodyLarge` in `inkPrimary`; reason `bodySmall` (meta) in `inkSecondary`; suggestion `titleLarge` in `inkPrimary`; options; then the set-up line `bodySmall` `inkSecondary` on **every** result. Text only. No exclamation marks.
- TalkBack: reading, reason and suggestion are one merged, polite live-region node (tag `placement:prompt`) whose text reads in that order; children keep tags `placement:reading`, `placement:reason`, `placement:suggestion` (unmerged tree).
- `PlaceFromScan.invoke` unchanged; acceptance, chooser, editor fallback, save-failure and Back behavior unchanged.

**Never:** a cube net, icons or color highlights on the result; the #252 re-placement entry (*Find my place*); new `ProgressEvent`s or Room/content-schema changes; Learner text in `strings.xml`; edits under `_bmad-output/planning-artifacts`.

## I/O & Edge-Case Matrix

| Scenario | Input / State | Reading / Reason / Suggestion | Options |
|---|---|---|---|
| Solved | `isSolved` | "Your cube is solved." / "Scramble it and scan again, or start Stage 1." / none | *Scan again*, *Start Stage 1* (as today) |
| 1 turn | `SOLVED` + `U'` | "Your cube is one turn from solved: turn the white face clockwise." / "Finish it, then scramble it and scan again, or pick a Stage." / none | *Scan again*, *Choose another Stage* |
| 2 turns | `SOLVED` + `R U` | "Your cube is two turns from solved: turn the white face counter-clockwise, then turn the red face counter-clockwise." / same reason / none | same |
| Nothing solved | white cross unmet, no other cross | "Your cube is fully mixed. Nothing is solved yet." / "Cube fundamentals starts from here." / "Start at Cube fundamentals?" | *Start* (Stage 1), *Choose another Stage* |
| Non-white cross | white cross unmet; e.g. `R U R'` (yellow cross intact) | "Your yellow cross is solved, nice work." / "Our lessons start with the white face and leave yellow for the end, so to follow them, start with the white cross." / "Start at First cross?" | *Start* (Stage 2), *Choose another Stage* |
| Non-white F2L | white cross unmet; F2L solved on another color, e.g. `R U2 R' U' R U' R'` | "Your first two layers are solved, nice work. You started from yellow." / same reason / "Start at First cross?" | *Start* (Stage 2), *Show me the last layer anyway* (accepts Last-layer orientation), *Choose another Stage* |
| Partly solved | first unmet Stage is 3 or 4 | Stage 3: "Your white cross is done, and {done} of 4 first-layer corners are in place." Stage 4: "Your first layer is done, and {done} of 4 middle-layer edges are in place." / "{stage} picks up from here." / "Start at {stage}?" | *Start*, *Choose another Stage* |
| At LL, not oriented | first unmet predicate `last-layer-oriented` | "Your first two layers are solved. Only the last layer is left: the top needs orienting." + " It looks like {case}." when one Case fits / "{stage} (or Faster last layer) covers this." / "Start at Last-layer orientation?" | *Start*, *Faster last layer*, *Choose another Stage* |
| At LL, oriented | first unmet predicate `cube-solved` | "…Only the last layer is left: the top is oriented; the pieces need moving into place." (+ Case) / same reason with Stage 6 / "Start at Last-layer completion?" | same (now also *Faster last layer*) |
| No matcher / several Cases | matcher null or ≠ 1 candidate | no Case sentence | — |

</intent-contract>

## Code Map

- `core/domain/src/main/kotlin/dev/cubentia/domain/tutor/Placement.kt` -- `PlacementOutcome` + `Placement.evaluate(state, stages)`; Stage Predicate = last Lesson `completion` (skip `StateReached`/no Lessons). Stage completions: 02 `white-cross-solved`; 03 `allOf(cross, corners)`; 04 `allOf(cross, corners, middle)`; 05 `last-layer-oriented`; 06 `cube-solved`.
- `core/domain/.../predicate/WhiteLayer.kt` -- `correctPieces(state, pieces, centerColor)` (internal, same module) for any-color cross; `FaceletTables.EDGE_FACELETS/CORNER_FACELETS/center`, `Face.opposite`.
- `core/domain/.../predicate/ObjectiveProgress.kt`, `AllOf.progress` (sums children) -- next Stage's own part = its predicate's `progress` minus the previous predicate Stage's `total` (both done and total).
- `core/domain/.../cube/CubeState.kt` -- `isSolved` ignores orientation; `apply(FaceTurn)` acts through orientation. `Move.kt` `FaceTurn`, `Turn`. Home: white U, green F, red R, yellow D.
- `core/domain/.../lastlayer/LastLayerMatcher.kt` -- `of(cases, algorithms)`, `candidates(view)`, `isSolvedUpToAuf`; `core/application/.../usecase/RecognizeCase.kt` `fromCube` is the pattern. `Case.name` is a TextKey (`case.case-oll-01.name` = "OLL 1").
- `core/application/.../usecase/PlaceFromScan.kt` -- `suggest(state, stages)` delegates to `Placement.evaluate`.
- `app/.../ui/placement/PlacementFlowViewModel.kt` -- `suggest` lambda, `evaluateScan(stages)`, `editorAccepted(state, stages)`.
- `app/.../ui/placement/PlacementScreen.kt` -- Result branch (prompt, buttons, tags `placement:start`, `:fasterLastLayer`, `:chooseAnother`, `:scanAgain`, `:startStage1`).
- `app/.../ui/shell/AppShell.kt` L313 (`recognizeCase` built from `ready.bundle`), L319-322 (`evaluateScan`), L433-461 (`PlacementScreen` call).
- `app/src/debug/kotlin/dev/cubentia/app/ScanDiagnostics.kt` L227-250 -- exhaustive `when` on outcomes; release twin is a no-op.
- `app/src/main/kotlin/dev/cubentia/app/ui/theme/Type.kt` L32 (meta = Body Small), `RubikTheme.tokens.inkSecondary`.
- `core/domain/src/main/resources/content/strings/en.json` -- `msg.placement.*`; ICU `select` color pattern as in `msg.diagnosis.unexpected-orientation`.
- Tests: `core/domain/src/test/.../tutor/PlacementTest.kt` (`TutorFixtures.state`, `entryOf(shipped(id))`), `app/src/test/.../ui/placement/PlacementFlowViewModelTest.kt`, `app/src/test/.../ui/shell/AppShellPlacementTest.kt`, `AppShellRecognizeTest.kt`, `app/src/testDebug/.../PlacementFromScanRouteTest.kt`.

## Tasks & Acceptance

**Execution:**
- `core/domain/.../tutor/Placement.kt` -- replace outcomes with `Solved`; `NearlySolved(turns: List<FaceTurnWords>)` (`FaceTurnWords(color: Color, turn: Turn)`, 1–2 items, in the order to make them); `NothingSolved(stage)`; `NonWhite(color, firstTwoLayers: Boolean, stage /*first predicate Stage*/, lastLayerStage: Stage? /*Stage whose predicate is LastLayerOriented, only when firstTwoLayers*/)`; `PartlySolved(stage, progress: ObjectiveProgress)`; `AtLastLayer(stage, oriented: Boolean, case: CaseId?)`. `evaluate(state, stages, matcher: LastLayerMatcher? = null)` per the matrix. Non-white color: first non-white `Color.entries` color whose F2L is solved (every corner/edge not touching that center's opposite face sits correctly), else the first with its 4 cross edges correct. At LL when the first unmet predicate is `LastLayerOriented` (oriented = false) or `CubeSolved` (oriented = true); empty predicate list → `NothingSolved(stages.first())`.
- `core/domain/src/test/.../tutor/PlacementTest.kt` -- one test per matrix row (states above; partly via `lesson-03-01`, `lesson-04-01` with exact progress; LL via `lesson-05-01`, `lesson-06-02`), plus: a Case is named for a shipped OLL Case state (`LastLayerMatcher.caseState(primary, Color.YELLOW)` with a matcher from the shipped bundle) and none without a matcher; 1-turn wins over 2; whole-cube rotation invariance of the 1-turn and non-white cases.
- `core/application/.../usecase/PlaceFromScan.kt` -- `suggest(state, stages, matcher = null)`; update its test.
- `app/.../ui/placement/PlacementFlowViewModel.kt` -- thread `matcher: LastLayerMatcher?` through `suggest`, `evaluateScan`, `editorAccepted`; update `PlacementFlowViewModelTest`.
- `app/.../ui/placement/PlacementScreen.kt` -- new params `matcher`, `cases: List<Case>`; Result renders the matrix (summary node, options, `placement:setup` line); new button `placement:lastLayerAnyway` → `onAccept(lastLayerStage, false)`; *Faster last layer* for every `AtLastLayer`; NearlySolved shows `placement:scanAgain` + `placement:chooseAnother`.
- `app/.../ui/shell/AppShell.kt` -- remember a `LastLayerMatcher` from `ready.bundle` (`Built` or null); pass it to `evaluateScan` and `PlacementScreen` with `ready.bundle.cases`.
- `app/src/debug/.../ScanDiagnostics.kt` -- map the new variants (stage id, or `SOLVED` / `NEARLY_SOLVED`).
- `core/domain/.../strings/en.json` -- remove `msg.placement.solved`, `msg.placement.non-white-cross`; add `msg.placement.reading.{solved,one-turn,two-turns,nothing-solved,non-white-cross,non-white-layers,last-layer-orient,last-layer-permute,case}`, `msg.placement.reading.partly.stage-03-first-layer-corners`, `msg.placement.reading.partly.stage-04-middle-layer`, `msg.placement.turn` ("turn the {color} face {turn}" with `select`s: clockwise / counter-clockwise / a half turn), `msg.placement.reason.{solved,nearly-solved,starts-here,non-white,picks-up,last-layer}`, `msg.placement.setup`, `btn.placement.last-layer-anyway` "Show me the last layer anyway"; copy exactly as the matrix.
- `app/src/test/.../ui/shell/AppShellPlacementTest.kt` (+ `AppShellRecognizeTest`, `PlacementFromScanRouteTest` if their asserted text changes) -- update to the new copy; add the ACs below.

**Acceptance Criteria:**
- Given an accepted cube with Stage 2 met and Stage 3 unmet, when the result shows, then the merged `placement:prompt` node's texts are, in order, the reading with its corner count, "First-layer corners picks up from here." and "Start at First-layer corners?", and `placement:setup` reads "The lesson sets up its own cube, so you can put yours aside."
- Given `SOLVED` + `U'` in the shell, when the result shows, then the one-turn reading shows with *Scan again* and *Choose another Stage* and no `placement:start`.
- Given the session C non-white F2L cube, when *Start* is tapped, then Stage 1 derives `SKIPPED_BY_PLACEMENT` and Stage 2 opens; when *Show me the last layer anyway* is tapped instead, then Stages 1–4 derive `SKIPPED_BY_PLACEMENT` and Last-layer orientation opens.
- Given an oriented-LL cube (`lesson-06-02` entry), when shown, then the oriented reading and *Faster last layer* show.
- Given every Placement outcome, when rendered, then no `placement:` text is hard-coded or from `strings.xml`.

## Spec Change Log

## Review Triage Log

### 2026-11-20 — Review pass
- verdicts: 32 findings — high 0, medium 0, low 19, false 13, maybe-false 0
- findings:
  - `[low]` `[patch]` (blind) Partly-solved copy hard-codes "of 4" and ignores `{total}` — en.json now uses "{done} of {total}" in both keys.
  - `[low]` `[reject]` (blind) Partly-solved key built from the Stage id, no integrity test — only Stages 3 and 4 reach `PartlySolved` with shipped content (Stage 2 unmet goes to the non-white branch, 5/6 to the LL branch); a content-integrity test adds machinery for content that does not exist.
  - `[false]` `[reject]` (blind) *Faster last layer* on an oriented LL sends the Learner to an orientation lesson — the button passes `faster = true`, so AppShell lands on Learn's *Faster last layer* rows (`selectedStage = null`, `scrollToTwoLook`), not inside 2-look OLL; 2-look PLL stays open there.
  - `[low]` `[reject]` (blind) "clockwise" has no reference point — the app's convention (`Turn` KDoc, every `msg.move-note.face.*`) is clockwise as seen looking at the face; the matrix copy is fixed by the spec.
  - `[low]` `[patch]` (blind) Turn words are never checked against the ICU keywords beyond white clockwise — grouped with the verification-gap turn-words row; rendered assertions added for a two-turn and a half-turn cube.
  - `[low]` `[reject]` (blind) LL reading joins two content strings with a space in code — no Learner-visible harm (the separator is not Learner text and both sentences come from en.json); the fix adds keys and branches.
  - `[false]` `[reject]` (blind) `unmet < 0` reported as Solved for an unsolved cube — Stage 6's predicate is `cube-solved`, so every predicate met implies `isSolved`, which returns earlier; the `stage!!` and `?: stages.first()` sites are non-null by construction of `suggestedStage`.
  - `[low]` `[patch]` (blind) Missing domain tests (PLL Case on an oriented LL, several Cases, two-turn rotation, 3+ turns) — PLL Case naming grouped with the verification-gap row and added; 3+ turns is already covered (`R U R'` asserts `NonWhite`); several Cases and two-turn rotation rejected as unlikely (a fully seen LL matches one pattern; rotation invariance is already tested on the one-turn path through the same `words` helper).
  - `[low]` `[reject]` (blind) Diagnostics log only kind + Stage id — the spec asks only to map the new variants; adding turn/color/progress fields is new surface for debug logs.
  - `[low]` `[reject]` (blind) Two `LastLayerMatcher`s per catalog (Placement and `RecognizeCase`) — built once per catalog load each, negligible cost; sharing needs new public surface on `RecognizeCase`.
  - `[low]` `[reject]` (blind) `epic-11-context.md` rewrite drops unrelated requirements — the workflow regenerated the stale cache with compile-epic-context (scoped to current planning artifacts); it is derived and regenerable, not code.
  - `[false]` `[reject]` (blind) Solved-branch *Scan again*/*Start Stage 1* lack `enabled = !accepting` — unchanged pre-existing code; *Start Stage 1* writes no progress and a pending save can only start from another outcome's *Start*, so no save can be pending while the Solved branch shows.
  - `[low]` `[reject]` (blind) Color-generic helpers live in `WhiteLayer` — cosmetic; `correctPieces` already took a center color before this change.
  - `[low]` `[patch]` (verification-gap) Editor-phase cube never gets its Case named through `PlacementScreen` in a test — shell test requested through the Editor phase.
  - `[low]` `[patch]` (verification-gap) Oriented-LL Case naming unchecked with a matcher — PLL Case test added to `PlacementTest`.
  - `[low]` `[patch]` (verification-gap) Turn words only checked for "white … clockwise" — rendered two-turn and half-turn assertions added to `AppShellPlacementTest`.
  - `[low]` `[patch]` (verification-gap) Diagnostics test does not check the outcome names — exact `outcome=NON_WHITE suggested=stage-02-first-cross` assertion added (filed as defer; the fix is one assertion).
  - `[low]` `[patch]` (verification-gap, other) `ScanDiagnosticsRunLineTest` fixture comment misstates why `R U R'` suggests a Stage — comment corrected with the same patch.
  - `[false]` `[reject]` (edge) Empty `twoLookStages` makes the LL reason name a missing option — shipped content always loads both 2-look Stages (`ContentBundle.twoLookStages`).
  - `[low]` `[patch]` (edge) `{total}` unused — same root as the blind `{total}` row; same fix.
  - `[low]` `[reject]` (edge) Raw key for `PartlySolved` on another Stage — same root and refutation as the blind dynamic-key row.
  - `[false]` `[reject]` (edge) Non-superset predicate gives a negative count — shipped Stage 3/4 completions are cumulative `allOf`s and the previous predicate is met, so `own.done >= before`.
  - `[low]` `[reject]` (edge) Several non-white crosses credit enum order — `Color.entries` puts yellow first after white, which is the likely case; a priority rule adds branches for a rare cube.
  - `[false]` `[reject]` (edge) Empty `stages` crashes — unreachable with shipped content, same as Story 11.2's carried row.
  - `[false]` `[reject]` (edge) Oriented LL *Faster last layer* sends the Learner to OLL — same refutation as the blind row.
  - `[false]` `[reject]` (intent) ACs live on the two devices with real TalkBack; tests are JVM — not a code defect: an unattended build cannot run the device session; acceptance on the Lenovo and S24 FE stays with the orchestrator's device session (recorded under residual risks, not as a deferred defect).
  - `[low]` `[patch]` (intent) The Editor input path is not exercised through the screen — same root as the verification-gap Editor row.
  - `[false]` `[reject]` (intent) Non-white reason says "leave yellow for the end" whatever the credited color — the sentence describes the Course (white first, yellow last), which is true for any credited color; copy fixed by EXPERIENCE.md.
  - `[false]` `[reject]` (intent) *Show me the last layer anyway* accepts directly without citing a source — settled in Design Notes from SCP decision 3, matching how *Faster last layer* accepts; the fix would edit the spec.
  - `[false]` `[reject]` (intent) DESIGN tokens `body`/`meta`/`title` unverified — `Type.kt` L32 defines that mapping (body = Body Large, meta = Body Small, title = Title Large), which the code uses.
  - `[false]` `[reject]` (intent) Changes beyond #251 (context file, diagnostics, Recognize fixture) — the context file is the workflow's cache, diagnostics had to follow the outcome type, and the Recognize fixture changed because a one-turn cube now correctly gets the nearly-solved outcome.
  - `[false]` `[reject]` (intent) New `unmet < 0` Solved path untested — unreachable, same refutation as the blind row.

## Design Notes

- Solved and 1–2-turn outcomes have no "Start at" line: their EXPERIENCE.md copy puts the suggestion in the reason and options, and a nearly solved cube has no Stage to suggest.
- *Show me the last layer anyway* is an option like *Faster last layer*: tapping it accepts Last-layer orientation directly (PO decision 3: "suggests *Last-layer orientation*").
- The LL reason uses `{stage}` so Stage 6 reads "Last-layer completion (or Faster last layer) covers this."; for Stage 5 it matches EXPERIENCE.md word for word.
- "Nothing solved" means no white cross and no other cross; a few pieces in place by chance do not count.
- Two-turn example: `SOLVED.apply(R U)` is undone by `U'` then `R'`: white is U's center, red R's.

## Auto Run Result

**Summary:** Placement's result now explains itself. `Placement.evaluate` checks, in order: Solved, 1–2 face turns from solved (bounded enumeration of the 18 single turns, then pairs on different faces; never the Solver), Nothing solved, Non-white solve (credit, *First cross*, plus *Show me the last layer anyway* when the first two layers are solved from another color), Partly solved (the next Stage's own `ObjectiveProgress`), and At the last layer (orientation or permutation reading, with the Case named through `LastLayerMatcher` when exactly one fits). `PlacementScreen` shows reading, reason and suggestion as one merged polite live region (TalkBack order), then the options and the set-up line "The lesson sets up its own cube, so you can put yours aside.", all from `en.json`. Text only.

**Files changed:**
- `core/domain/.../tutor/Placement.kt`: new outcomes and their evaluation order; near-solved enumeration; Case naming.
- `core/domain/.../predicate/WhiteLayer.kt`: color-generic `crossSolved` and `firstTwoLayersSolved`.
- `core/application/.../usecase/PlaceFromScan.kt`: `suggest` takes an optional matcher.
- `app/.../ui/placement/PlacementFlowViewModel.kt`: threads the matcher through scan and Editor evaluation.
- `app/.../ui/placement/PlacementScreen.kt`: the new result layout, options and set-up line.
- `app/.../ui/shell/AppShell.kt`: builds the matcher per catalog and passes it and the Cases.
- `app/src/debug/.../ScanDiagnostics.kt`: logs the new outcome kinds.
- `core/domain/.../strings/en.json`: new Placement copy; obsolete `msg.placement.solved` and `msg.placement.non-white-cross` removed.
- Tests: `PlacementTest` (17), `AppShellPlacementTest` (33), `PlacementFlowViewModelTest`, `StageProgressUseCasesTest`, `AppShellRecognizeTest`, `PlacementFromScanRouteTest`, `ScanDiagnosticsRunLineTest`.
- `_bmad-output/implementation-artifacts/epic-11-context.md`: regenerated (it was older than the planning artifacts).

**Review findings:** 32 findings (high 0, medium 0, low 19, false 13). 10 rows patched, in 5 entries: the `{total}` copy, rendered two-turn and half-turn wording, PLL Case naming on an oriented LL, Case naming for an Editor-entered cube through the screen, and the exact diagnostics outcome assertion with its comment fixed. Nothing deferred. Rejected, each with its reason in the triage log: the dynamic partly-solved key (2 rows), "clockwise" wording, the joined Case sentence, the extra domain tests (several Cases, two-turn rotation), diagnostics detail, the duplicated matcher, the context-file rewrite, helper naming, non-white color priority, and the false rows (Faster last layer on oriented LL ×2, `unmet < 0` ×2, Solved-branch guards, empty 2-look list, non-superset arithmetic, empty Stages, device-only acceptance, the "yellow for the end" reason, the *last layer anyway* semantics, design-token mapping, changes beyond #251).

**Follow-up review recommendation:** false. Patched entries by verdict: high 0, medium 0, low 5.

**Verification:**
- `./gradlew spotlessApply spotlessCheck build --max-workers=4`: BUILD SUCCESSFUL before and after the patches.
- JUnit XML: `PlacementTest` and `AppShellPlacementTest` ran with 0 failures and 0 skipped, and every I/O-matrix row has a covering test.

**Residual risks:**
- Not checked on a device yet (Lenovo Tab M10 Plus 3rd Gen, Galaxy S24 FE) or with real TalkBack. That needs a device session.
- *Start* on a non-white result now targets *First cross* and records Stage 1 as *Skipped by placement*. Before, it started Stage 1 and wrote nothing.
- Debug diagnostics no longer emit `SUGGESTED` or `NON_WHITE_CROSS`, so session tooling that looks for those names needs updating.

## Verification

**Commands:**
- `export JAVA_HOME=/usr/lib/jvm/java-25-openjdk-amd64 ANDROID_HOME=$HOME/Android/Sdk; ./gradlew spotlessApply spotlessCheck build` -- expected: BUILD SUCCESSFUL
- `./gradlew :core:domain:test --tests '*PlacementTest' :app:testDebugUnitTest --tests '*Placement*'` -- expected: all pass
