---
title: 'GH #321: Scan fallback to the Editor keeps the scanned colours (Solve tab)'
type: 'feature'
created: '2026-11-24'
status: 'done'
baseline_revision: 'aeabb56f75fc53e476b12c210ca8abd2b85e5fb8'
review_loop_iteration: 0
followup_review_recommended: false
context:
  - '{project-root}/AGENTS.md'
warnings: ['oversized']
deferred: []
---

<intent-contract>

## Intent

**Problem:** On the Solve tab, a scan that falls back to the Manual Cube Editor (review *Close*, *Enter colors instead*) opens it empty ("No scan this time…", 6 faces left) although the scan read most stickers (`guidedScan valid=true outcome=FELL_BACK_TO_EDITOR`). Review *Close* with no change today returns `Cancelled`.

**Approach:** Give the typed `ScanOutcome.FellBackToEditor` (Story 10.3 contract) an optional payload: the review's 54 home-frame stickers (`List<Color?>`, null = unset) including the Learner's fixes, or, before review, the stickers `ScanReview.fromSamples` reads from the captured faces. The Solve tab seeds its Editor from it (unset stickers are blank wells); every other caller ignores it and opens blank. Review *Close* with no change returns `FellBackToEditor`; with a change it asks "Discard this scan?" and *Discard* returns `Cancelled`. Sources: SCP 2026-11-24 github-issue-triage (#321 row, F1–F5, X2, X6–X8, P8, P9, P15, P16), amended Stories 3.5, 10.3, 10.5, 10.15 in `_bmad-output/planning-artifacts/epics.md`.

## Boundaries & Constraints

**Always:**
- Payload is present iff the scan has at least one captured face (review phase, a retake/calibration opened from review, or a viewfinder / corner view / hand-over with captured or kept faces); null for Rationale, Denied, Unavailable, a viewfinder with no capture, and Case capture (unchanged `CaseCaptureContract.editor()`).
- Stickers cross the Activity boundary only as an Intent extra of 54 colour names ("" = unset); `decode` is lenient (missing, wrong size or bad name → payload null). Never fill an unset sticker from an expected state (AD-5); centres are always home colours.
- Solve tab, payload non-null: `SolvePhase.Editing` seeded in the home frame, note `msg.solve.scan-kept` = "Your scan is here. Fill in the stickers it couldn't read." (new key in `en.json`), read stickers counted as set, every other non-centre sticker unset. Payload null: today's blank editor with `msg.solve.scan-fell-back`. Either way `scanned`/`scannedColors` are cleared: only `Accepted` is the Full verified baseline.
- Seeded editor: an unset sticker renders as the review net's blank well (no colour, dashed edge, "?") with TalkBack content description from new key `msg.editor.sticker-not-set` = "{face} sticker {position}, not set"; selecting it then a palette colour sets it (normal paint and paint mode); a face is entered only when all 9 stickers are set; while any face is unentered no counts/piece/global line, no turn fit, *Accept* disabled, and the instruction line is `msg.editor.faces-left` naming those faces. The seeded flag survives recreation (Saver) and a Solve tab switch (draft).
- The Activity knows a Solve-tab scan only from a new boolean extra `ScanFlowContract.EXTRA_SEEDS_EDITOR`, set by `ScanFlowContract(..., seedsEditor = true)` for the Solve Guided and Quick scan actions in `MainActivity`; Placement's contract leaves it unset.
- Review *Enter colors instead*: with `EXTRA_SEEDS_EDITOR` it returns `FellBackToEditor` with the stickers at once, no discard question (nothing is discarded; that is how the review fixes reach the Editor); without it, unchanged (asks when changed, then `FellBackToEditor`).

**Never:**
- No change to Placement (`PlacementFlowViewModel`/`PlacementScreen` stay blank, old note), Quick Check (`QuickCheckContract` still decodes the fallback as `Cancelled`), Recognize/Case capture, calibration mode, direct review *Accept* → playback, system Back on review, or the viewfinder's own *Close* (#325).
- No new `SessionEvent`, port, module, persisted entity; nothing persisted beyond Compose saved state. No planning-artifact, `_bmad/`, `.claude/skills/` edits. No device checks.

## I/O & Edge-Case Matrix

| Scenario | Input / State | Expected Output / Behavior | Error Handling |
|----------|--------------|---------------------------|----------------|
| Close, no change | Solve scan, 6 faces, review untouched, *Close* | `FellBackToEditor(stickers)`; seeded Editor, new note; Accept enabled if the stickers validate | — |
| Close, changed | review sticker edited or retake completed, *Close* | "Discard this scan?"; *Discard* → `Cancelled` (Solve home); dismiss keeps review as is | — |
| Enter colors after a fix | Solve review edited, *Enter colors instead* | Seeded Editor holding the fix, no question | — |
| Unread / unresolved face | review blanks (incl. a face in `gaps`) | those stickers unset; their faces listed as left; no problem line | — |
| Partial scan | Enter colors in viewfinder after ≥1 capture | `fromSamples(captured+kept, saved)` stickers; unposed faces all unset; new note | — |
| No capture / denial | Enter colors with 0 faces, Denied card | payload null → blank Editor, `msg.solve.scan-fell-back` | — |
| Placement fallback | Placement review Close (no change) or Enter colors | blank Placement Editor, old note | — |
| Bad extra | payload extra wrong size / bad name | `FellBackToEditor(null)` | runCatching → null |

</intent-contract>

## Code Map

- `app/src/main/kotlin/dev/cubentia/app/scan/ScanOutcome.kt` -- `data object FellBackToEditor` → `data class FellBackToEditor(val stickers: List<Color?>? = null)`.
- `app/src/main/kotlin/dev/cubentia/app/scan/ScanFlowContract.kt` -- encode (L52) / decode (L77) the sticker extra; add `seedsEditor` ctor param + `EXTRA_SEEDS_EDITOR` on `createIntent`.
- `app/src/main/kotlin/dev/cubentia/app/MainActivity.kt` -- `guidedScanAction` (~L132-155): `seedsEditor` param; true for `onScan`/`onQuickScan`, false for `onPlacementScan`.
- `app/src/camera/kotlin/dev/cubentia/app/camera/GuidedScanActivity.kt` -- `onEnterColors` (L190), review `onClose` (L340) / `onEnterColors` (L322), `finishWith` (L494-540: `when` L503 and `capture == FellBackToEditor` L534 become `is`). Read `EXTRA_SEEDS_EDITOR` near `solveScan` (L135-138). `scan.saved` (L881).
- `app/src/camera/kotlin/dev/cubentia/app/camera/GuidedScanFlow.kt` -- add pure `fallbackStickers(phase, saved): List<Color?>?` (Review → `review.stickers`; Calibration/Viewfinder `fromReview` → its stickers; Viewfinder `kept + captured`, CornerViewfinder `captured` non-empty → `ScanReview.fromSamples(samples, saved).stickers`; else null). Phases in `GuidedScanScreen.kt` L100-170.
- `app/src/main/kotlin/dev/cubentia/app/ui/scan/ScanReviewScreen.kt` -- Close (L433), discard dialog (L472-494, `DiscardAction` L87), BackHandler L280: add `onDiscard: () -> Unit = onClose` (used by the CLOSE discard confirm and the Back discard) and `enterColorsAsks: Boolean = true` (false skips the question for *Enter colors instead*).
- `app/src/main/kotlin/dev/cubentia/app/ui/solve/SolveFlowViewModel.kt` -- `SolvePhase.Editing` gains `seeded: Boolean = false`; `onScanOutcome` (L111) seeds from the payload (unset positions hold the face's home colour as placeholder, `set` = read non-centre indices).
- `app/src/main/kotlin/dev/cubentia/app/ui/solve/SolveScreen.kt` (L104-126) -- pass `seeded` to `ManualCubeEditor`.
- `app/src/main/kotlin/dev/cubentia/app/ui/editor/ManualCubeEditorState.kt` -- `seeded` ctor param; `isUnset(i)`, `entered(face)` = `complete(face)` when seeded; initial selection = first unset; Saver persists `seeded`.
- `app/src/main/kotlin/dev/cubentia/app/ui/editor/ManualCubeEditor.kt` -- `seeded` param into `rememberSaveable`/`rememberPaintMode` keys; net gets `unresolved` (null for unset) when seeded; `instruction` (L340-349) uses faces-left when `fresh || seeded`; `SelectedStickerLine` (L525-545) handles an unset sticker.
- `app/src/main/kotlin/dev/cubentia/app/ui/editor/StickerReviewNet.kt` -- blank sticker semantics (L348-358, L422-436): optional blank-description key param (editor passes `msg.editor.sticker-not-set`, no "Color needed" state); visuals unchanged.
- `app/src/main/kotlin/dev/cubentia/app/ui/placement/PlacementFlowViewModel.kt` (L124) -- `is ScanOutcome.FellBackToEditor`, behaviour unchanged.
- `core/domain/src/main/resources/content/strings/en.json` -- add `msg.solve.scan-kept` (beside L63) and `msg.editor.sticker-not-set`.
- Tests to update for the type change: `SolveFromScanRouteTest` (L151,169,184; `returnToSolve` compares by equality), `GuidedScanContractTest` L45, `QuickCheckContractTest` L50, `SolveFlowViewModelTest` (L134,148,188,274), `AppShellSolveTest` L240, `AppShellPlacementTest` (L248…879), `ScanRouteTest` review Close tests (~L426-520).

## Tasks & Acceptance

**Execution:**
- `ScanOutcome.kt`, `ScanFlowContract.kt`, `MainActivity.kt` -- payload type, extras, Solve-only marker -- typed contract (Story 10.3).
- `GuidedScanFlow.kt`, `GuidedScanActivity.kt`, `ScanReviewScreen.kt` -- payload from the phase; review Close no change → `FellBackToEditor(stickers)`, Discard → `Cancelled`; Solve Enter colors carries without asking -- F1, P16.
- `SolveFlowViewModel.kt`, `SolveScreen.kt`, `ManualCubeEditorState.kt`, `ManualCubeEditor.kt`, `StickerReviewNet.kt`, `en.json` -- seeded editor with unset stickers, new note and TalkBack copy -- F2, F3, F4, P15.
- `PlacementFlowViewModel.kt` -- compile fix only -- F5.
- Tests: `ScanFlowContract` round trip with/without payload and lenient decode, `EXTRA_SEEDS_EDITOR` set only for Solve contracts; `GuidedScanFlow.fallbackStickers` per phase; `ScanReviewScreen` Close no change / Close changed → Discard / dismiss / Enter colors with `enterColorsAsks=false`; `ManualCubeEditorState` seeded rules (entered at 9 set, problems hidden, selection, paint sets, Saver round trip); `ManualCubeEditor` Compose: blank well "?" + "not set" description, tap then colour sets, faces-left line, Accept disabled until all set; `SolveFlowViewModel` seeded vs blank, Accepted unchanged; Robolectric route (`SolveFromScanRouteTest`): review Close no change → seeded Editor + new note, review Close changed → Discard → Solve home, Enter colors after a fix → fix in Editor, Enter colors after a partial capture → seeded, 0 faces / denial → blank old note; Placement fallback with stickers → blank (`AppShellPlacementTest` or `PlacementFromScanRouteTest`); existing tests updated only for the new type/behaviour.

**Acceptance Criteria:**
- Given a Solve-tab scan with captured faces, when it falls back (review Close with no change, Enter colors instead), then the Editor opens seeded with the scanned stickers plus review fixes in the home frame with "Your scan is here. Fill in the stickers it couldn't read.", and unread/unresolved stickers are blank wells read "not set".
- Given a seeded Editor with any unset sticker, when it renders, then no count or piece error shows, the instruction names the faces left, and Accept is disabled; once every sticker is set, normal validation applies.
- Given a review with a change, when Close then Discard, then the outcome is `Cancelled` and the Solve tab shows home; dismissing keeps the review.
- Given a denial, a no-capture fallback or any Placement fallback, when the Editor opens, then it is blank with "No scan this time. Enter your cube's colors here."; review Accept still goes straight to playback.

## Spec Change Log

## Review Triage Log

### 2026-11-24 — Review pass
- verdicts: 23 findings — high 0, medium 1, low 8, false 11, maybe-false 0 (3 rejected by rule: spec-edit fixes)
- findings:
  - `[low]` `[patch]` (intent) Placement review *Close* with no change now opens the blank Placement Editor instead of cancelling, untested at the review surface — intended by P16/X8; patched: route test `reviewCloseWithNoChangeFromAPlacementScanOpensTheBlankPlacementEditor` in PlacementFromScanRouteTest.
  - `[false]` `[reject]` (intent) Solve *Enter colors instead* no longer asks (reading R3b) goes beyond the intent — P9 requires every review fix in the seeded Editor and any fix makes *Close* ask/*Discard* cancel, so asking "Discard this scan?" and then keeping the fixes (or dropping them) contradicts the intent; R3b is the only consistent reading.
  - `[low]` `[reject]` (intent) No route test drives a six-face scan with one unresolved-rotation face to the Editor — `ScanReview.from` already blanks `gaps` faces (pre-existing, tested domain), editor unset rendering is tested; building such a fixture is more than a direct fix.
  - `[low]` `[patch]` (intent) Solve Quick Scan seeding path only contract-tested — grouped with the verification-gap finding; patched (see gap row).
  - `[false]` `[reject]` (intent) Home frame not checked for a non-home hold — review stickers are home-frame by construction (centres fixed in `ScanReview`), and the Editor forces home centres.
  - `[false]` `[reject]` (blind) Spec "Never: no change to Placement" contradicts the design note — fix is a spec edit (rejected by rule); behaviour follows P16.
  - `[low]` `[patch]` (blind) No test clicks Placement review *Close* — same root as intent row 1; patched by the same route test.
  - `[low]` `[patch]` (blind) Code Map lists ScanRouteTest updates not made; non-Solve Close-no-change untested — same root; covered by the Placement route test.
  - `[false]` `[reject]` (blind) Diagnostics now log FELL_BACK_TO_EDITOR for review Close with no change — that is the true outcome now; `QuickScanMeasurement` counts only ACCEPTED (verified by the gap layer), Quick Check logs no outcome.
  - `[low]` `[reject]` (blind) "Your scan is here" shows when a partial capture left every sticker unset — the intent's done-when (1) prescribes the seeded Editor and note for any scan with captured faces; recorded in Design Notes.
  - `[low]` `[reject]` (blind) Face naming differs between the "not set" line (tab labels) and coloured-sticker lines — cosmetic; matches P15's "{face} sticker {n}, not set" format.
  - `[low]` `[patch]` (blind) Quick Scan seeding path has no route test — same root as gap row; patched.
  - `[false]` `[reject]` (blind) Quick Check / Case capture not re-checked end to end — Case capture never shows review and keeps `CaseCaptureContract.editor()`; Quick Check decode → Cancelled is unit-tested and full Quick Check routes stayed green.
  - `[false]` `[reject]` (blind) `seededEditing` hard-codes 9/4 and a dead size guard — no named harm; the same literals are used across `ScanReview`/`SolveFlowViewModel`; the guard is defensive for a public data class.
  - `[false]` `[reject]` (blind) *Enter colors* during a retake drops newly retaken faces — Story 10.16 retakes are all-or-nothing (back-out restores the review exactly), so the review's stickers are the consistent payload.
  - `[false]` `[reject]` (blind) Spec bookkeeping incomplete / no device follow-up recorded — spec edits and process; device acceptance is explicitly out of scope.
  - `[false]` `[reject]` (blind) Seeded Editor's own Close/Back and `fresh=false` effects unspecified — `fresh` is consumed only by the Editor (SolveScreen L123); Close uses the same `closeEditor` as every Editor.
  - `[false]` `[reject]` (blind) Contract misses lowercase names / encode size check — encoder only receives `ScanReview.stickers` (always 54, enum names); lenient decode already returns null on any bad name.
  - `[medium]` `[patch]` (edge) Calibration opened from Quick Scan's corner view: *Enter colors instead* dropped captured corner faces (blank Editor, old note) — patched: `fallbackStickers(..., quickScanCapture)` filled from `scan.capturePhase()` when `fromQuickScan`; GuidedScanFlowTest case added.
  - `[false]` `[reject]` (edge) `rememberSaveable` keys omit `initialSet` — the Saver persists `set`, and no path replaces a composed Editing with same facelets but a different set.
  - `[false]` `[reject]` (edge) Quick Check diag logs FELL_BACK_TO_EDITOR — the Quick Check branch logs `onQuickCheckCaptured` without an outcome.
  - `[medium]` `[patch]` (edge) Payload-iff-captured claim broken for Calibration.fromQuickScan — same root as the edge medium row; same patch.
  - `[low]` `[patch]` (gap) Solve Quick Scan launch never asserted to carry EXTRA_SEEDS_EDITOR — patched: assert added in QuickScanRouteTest next to EXTRA_QUICK_SCAN.

## Design Notes

- Review *Close* now returns `FellBackToEditor` for every caller (P16); Placement therefore opens its blank Editor where it used to cancel — the caller decides (P16, X8). Quick Check maps it to `Cancelled` as today.
- Why Solve's *Enter colors instead* stops asking: the review fixes reach the Editor only that way (any fix makes *Close* ask and *Discard* cancels), and P9 requires "every fix the Learner made in review" in the seeded Editor; asking "Discard this scan?" before keeping it would be untrue. Placement keeps its question because it still discards.
- A partial Guided scan cannot pose faces (`ScanAssembler` marks `NO_POSE` gaps under six faces), so its fallback seeds an Editor with every non-centre sticker unset and the new note — the literal F4/P9 rule.

## Verification

**Commands:**
- `cd /mnt/e/Dynamic/IndFun/RubikXplorer-issue-321 && export JAVA_HOME=/usr/lib/jvm/java-25-openjdk-amd64 ANDROID_HOME=$HOME/Android/Sdk && ./gradlew spotlessApply && ./gradlew spotlessCheck build` -- expected: BUILD SUCCESSFUL (all JVM/Robolectric tests incl. testDebug, lint, Kover, boundary and content checks).

## Auto Run Result

Status: done

**Summary:** `ScanOutcome.FellBackToEditor` now carries the scan's 54 home-frame stickers (null = unset). The Solve tab seeds its Manual Cube Editor from them with "Your scan is here. Fill in the stickers it couldn't read."; unset stickers are the review net's blank well read "{face} sticker {n}, not set", a face counts as entered at 9 set, no problem line and Accept disabled while any is unset. Review *Close* with no change returns the fallback; with a change it asks "Discard this scan?" and *Discard* cancels. Solve's review *Enter colors instead* keeps the fixes without asking (`EXTRA_SEEDS_EDITOR`). Denial / no capture / Placement stay blank with the old note; Accept → playback unchanged; only Accepted is the verified baseline.

**Files changed:**
- `app/src/main/.../scan/ScanOutcome.kt` — payload on `FellBackToEditor`.
- `app/src/main/.../scan/ScanFlowContract.kt` — sticker extra (lenient decode), `EXTRA_SEEDS_EDITOR`.
- `app/src/main/.../MainActivity.kt` — Solve Guided/Quick contracts mark `seedsEditor`.
- `app/src/camera/.../GuidedScanFlow.kt` — `fallbackStickers` per phase (incl. Quick Scan calibration).
- `app/src/camera/.../GuidedScanActivity.kt` — fallbacks carry stickers; review Close/Discard split.
- `app/src/main/.../ui/scan/ScanReviewScreen.kt` — `onDiscard`, `enterColorsAsks`.
- `app/src/main/.../ui/solve/SolveFlowViewModel.kt`, `SolveScreen.kt` — seeded `Editing`.
- `app/src/main/.../ui/editor/ManualCubeEditorState.kt`, `ManualCubeEditor.kt`, `StickerReviewNet.kt` — seeded mode, unset blank wells, "not set" TalkBack.
- `app/src/main/.../ui/placement/PlacementFlowViewModel.kt` — type change only.
- `core/domain/.../content/strings/en.json` — `msg.solve.scan-kept`, `msg.editor.sticker-not-set`.
- Tests: contract, GuidedScanFlow, ScanReviewScreen, editor state/Compose, SolveFlowViewModel, AppShell Solve/Placement, Solve/Placement/Quick Scan route tests.

**Review findings:** 23 — patches applied: 3 root causes (1 medium: Quick Scan calibration fallback lost captured corners; 2 low: Placement review-Close route test, Solve Quick Scan seeds-editor assert). Deferred: none. Rejected: 13 false and 3 low as recorded in the Review Triage Log (with reasons).

**Follow-up review recommendation:** false (patched: 0 high, 1 medium, 2 low).

**Verification:** `./gradlew spotlessCheck build` (JDK 25, ANDROID_HOME set) — BUILD SUCCESSFUL after the patches (2m 20s), including testDebugUnitTest; targeted classes reported green (GuidedScanFlowTest 50, QuickScanRouteTest 16, PlacementFromScanRouteTest 5, SolveFromScanRouteTest 12, ScanRouteTest 11).

**Residual risks:** device acceptance (Lenovo, S24 FE; TalkBack wording and blank-well look) is a later human session; Placement's review *Close* with no change now opens its blank Editor instead of cancelling (P16); run logs now record FELL_BACK_TO_EDITOR for that Close.
