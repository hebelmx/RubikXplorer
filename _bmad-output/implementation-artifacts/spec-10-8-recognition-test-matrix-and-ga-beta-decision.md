---
title: 'Story 10.8: Recognition test matrix and GA/BETA decision (measurement tooling)'
type: 'feature'
created: '2026-11-18'
status: 'done'
baseline_revision: 'f78b365206571c178a31dfed3bfaa4e4183dc79a'
review_loop_iteration: 1
followup_review_recommended: true
context:
  - '{project-root}/AGENTS.md'
warnings: ['oversized']
deferred: []
---

<intent-contract>

## Intent

**Problem:** Story 10.8 must report Guided 6-face Scan, Quick Scan, Quick Check, Case recognition and Placement against the §4.8 targets over the OQ-8 matrix (2 cubes × 4 lights × 3 devices), but today only Quick Scan writes a run line. No tool scores runs against ground truth, and no report exists. This build is the CODE PART ONLY. Device session C, run by the PO, records the logs, fills in the numbers and records the per-feature OFF/BETA/GA recommendation in the PRD memlog.

**Approach:** Add one debug-only `<feature> run` line per finished run to `ScanDiagnostics` (tag `CubentiaScanDiag`; release stays a no-op). Add an offline `:core:domain:evaluateMatrix` main that reads one logcat file per cell and a truth CSV, scores each run per cell, per device and per feature, and rewrites a generated block in `docs/spikes/oq-8-recognition-matrix.md`. The rest of that report is written by hand: the 10.6 residual risk, the fixture accuracy summary, a placeholder table for the PO's recommendation and the manual-fallback checklists.

## Boundaries & Constraints

**Binding ADs (verbatim from the spine; Refs AD-14, AD-18):**

> ### AD-14 — Quality gates in CI
> - **Binds:** NFR-4, NFR-11, NFR-12, PRD §7 gates 1–3
> - **Prevents:** portfolio claims that the repo can't back.
> - **Rule:**
>   - **Every PR to `main`** must pass:
>     - the build;
>     - Spotless/ktlint;
>     - Android lint, including accessibility checks;
>     - JVM tests (unit, Kotest property tests, content schema/keys/predicates, Algorithm verification, solve corpus, recognition fixtures, transition table, checkpoint round-trip);
>     - Kover ≥ 80% line coverage on `:core:*`;
>     - the AD-13 checks;
>     - an AAB size check against NFR-11.
>   - **detekt 2.x** runs non-blocking until it has a stable release for Kotlin 2.4.
>   - **Release tags** additionally run instrumentation, UI and renderer-sync tests.

> ### AD-18 — Feature flags for beta and cut order
> - **Binds:** FR-5, FR-22, FR-27, FR-28, NFR-5, NFR-11, PRD §6.2 cut order and spike fallback
> - **Prevents:** each epic implementing its own flag mechanism; an OFF feature still shipping its code or permission; scan shortcuts enabled without the scan pipeline; per-feature beta labels.
> - **Rule:**
>   - A `FeatureFlags` port maps a closed enum `{QUICK_SCAN, PLACEMENT, CASE_RECOGNITION, TEST_ME, CAMERA_SCAN, ADAPTIVE_ASSISTANCE}` to `{OFF, BETA, GA}`.
>   - The levels are set per build type in `build-logic`.
>   - The UI shows the beta label from the flag, through one shared BETA label component (introduced for *Test me*, #137/#141). No feature checks `BuildConfig` directly.
>   - **Flags compose the build (SCP 2026-11-14):** when a build type has `CAMERA_SCAN`, `QUICK_SCAN`, `PLACEMENT` or `CASE_RECOGNITION` above OFF, `build-logic` adds the camera source set and its dependencies to that build type; otherwise neither is added.
>   - `PLACEMENT`, `QUICK_SCAN` and `CASE_RECOGNITION` may be above OFF only when `CAMERA_SCAN` is above OFF in the same build type. `build-logic` fails the build otherwise, naming the flag.
>   - **Debug levels follow wiring (SCP 2026-11-16 main-app entry points):** `debug` raises a flag above OFF (normally to GA) only once that flag's entry point is wired in the main app, in the same PR. A raised flag never shows a dead or unconnected entry point, and a feature is accepted on the main app build, not through a debug-only launcher.

No known deviation: no flag level changes, and the diagnostics are a developer tool in the debug-only source set (AD-15).

**Always:**
- Run lines are computed and logged only by `app/src/debug/.../ScanDiagnostics.kt`. The release copy only gains matching no-op signatures. Any timing state lives in the debug object, so release computes nothing. No Learner-visible change, no new permission, no new dependency, no flag change.
- Line grammar: `<feature> run k=v k=v …`, separated by single spaces, with values that contain no spaces. Every run line ends with `device=<Build.MODEL>`, where each character outside `[A-Za-z0-9-]` becomes `-`. Letters are `ScanTrace.letter`. A 54-letter state is URFDLB home-frame `Facelets`, with `-` for a blank sticker. A field with no value is `-`.
- The `quickScan run` line keeps its five existing fields, in the same order, and appends `state= initial= device=` at the end. The `QuickScanMeasurement` regex accepts trailing fields, and its test stays green unchanged.
- Ground truth goes only to the evaluator; the app never sees it. Sticker truth = `CubeState.SOLVED.apply(Notation.parse(scramble)).reoriented(Orientation.HOME).toFacelets()`. That is the home frame `ScanAssembler` places faces in by center color, the same frame as the `quickCheck` line's `expectedHome`.
- The evaluator code goes in the existing `evaluation` source set (excluded from the shipped jar and Kover). Its tests go in `core/domain/src/test`.

**Never:** fabricate device results, a recommendation or a memlog entry (all remain marked placeholders for the PO); add an in-app tagging/session UI; change scan, Quick Check, recognition or placement behavior; edit planning artifacts.

## I/O & Edge-Case Matrix

| Scenario | Input / State | Expected Output / Behavior | Error Handling |
|----------|--------------|---------------------------|----------------|
| Guided accepted | 6-face scan, 1 correction, Accept | `guidedScan run elapsedMs=N repromptFaces=R corrections=1 valid=true outcome=ACCEPTED state=<54> initial=<54 with - for uncertain> device=M` | — |
| Guided cancelled/editor | Close or *Enter colors* | `valid=` from the review's current stickers, or `false` when there is no review. `state=` is the review's stickers, or `-`. `outcome=CANCELLED` or `FELL_BACK_TO_EDITOR` | — |
| Quick Check | Partial or Full result applied | `quickCheck run kind=Partial captures=C elapsedMs=N certainty=VERIFIED matches=true observed=U:xxxxxxxxx,R:… expected=<54> expectedHome=<54> device=M` (a Full result's `observed=` is 54 letters). The existing comma line stays | Without a capture mark: `captures=- elapsedMs=-` |
| Recognize | second capture → result | `recognize run elapsedMs=N outcome=UNIQUE case=case-oll-27 candidates=- captures=2 device=M`. `SEVERAL`: `case=-` and `candidates=a,b`. `CANCELLED` and `EDITOR` are logged on the capture outcome with `elapsedMs=-` | — |
| Placement | scan accepted → suggestion → Learner accepts, leaves or rescans | `placement run elapsedMs=N outcome=SUGGESTED suggested=stage-03-… accepted=stage-03-…\|none device=M` | Only scan-originated suggestions are logged |
| Evaluator happy | `Fixtures/matrix/*.log` + `truth.csv` | Per-cell, per-device and per-feature tables with MET/MISSED/NO DATA per target. The generated block between the markers is replaced | — |
| Bad cell name / unknown feature | `foo.log` | Cell skipped and listed under "Skipped files" | No crash |
| Missing truth for a run | no truth row | Run counted, truth-dependent metrics show "no truth" | — |
| Missing logs dir | dir absent | Every feature NO DATA | — |
| Malformed truth | bad scramble or unknown token | Fails fast, naming file, line and reason | `IllegalArgumentException` |

</intent-contract>

## Code Map

- `app/src/debug/kotlin/dev/cubentia/app/ScanDiagnostics.kt` -- debug logger; `onQuickScanRun` (fields L48-68), `onQuickCheck` (L117-145), `letters`. Add `onGuidedScanRun`, extend `onQuickScanRun(state, initial)`, `onQuickCheckCaptured`/`onQuickCheckRun`, `onCaseCaptured`/`onRecognizeRun`, `onPlacementScanAccepted`/`onPlacementRun`.
- `app/src/release/kotlin/dev/cubentia/app/ScanDiagnostics.kt` -- mirror signatures as `= Unit`.
- `app/src/camera/kotlin/dev/cubentia/app/camera/GuidedScanActivity.kt` -- `finishWith` (L347, quick run L348-364): add guided run for a 6-face guided scan (`quickScan == null && caseCorners == null && quickFaces == null && !calibrationMode`); `analyze` guided branch (L444-446): set `guidedStartedAt`/`quickCheckStartedAt` on first frame; `handle` (L510-536): mark the Partial capture; `captureCase` done (L460): `onCaseCaptured()`; `GuidedScanViewModel` (L555): new `guidedStartedAt`, `guidedLogged`, `quickCheckStartedAt`. Review fields: `GuidedScanPhase.Review.corrections/initialStickers` (`GuidedScanScreen.kt` L92-99); `controller.repromptCount` (`GuidedScan.kt` L379); `vm.captures`.
- `app/src/main/kotlin/dev/cubentia/app/MainActivity.kt` L216-221 -- next to `onQuickCheck`, call `onQuickCheckRun(expected, observation, certainty, matchesExpected)`.
- `app/src/main/kotlin/dev/cubentia/app/ui/recognize/RecognizeFlowViewModel.kt` -- `onCapture`/`evaluate`: log the run for camera inputs only; `Recognition` (`core/application/.../usecase/RecognizeCase.kt` L16-29).
- `app/src/main/kotlin/dev/cubentia/app/ui/placement/PlacementFlowViewModel.kt` + `app/src/main/kotlin/dev/cubentia/app/ui/shell/AppShell.kt` L401-424 -- pending run from `evaluateScan`; new `accepted(stageId)` called from `onAccept`; `reset()` and a new scan finish it with `accepted=none`. `PlacementOutcome` (`core/domain/.../tutor/Placement.kt` L11-25).
- `core/domain/src/evaluation/.../evaluation/quickscan/QuickScanMeasurement.kt` -- relax the trailing `\s*$` to allow appended fields.
- `core/domain/src/evaluation/.../evaluation/LiveBaselineMarkdown.kt` L6-80 -- marker replace pattern to copy; `LiveBaselineMain.kt` -- main shape.
- `core/domain/build.gradle.kts` -- register `evaluateMatrix` like `evaluateLiveBaseline` (JavaExec, `evaluation.runtimeClasspath`, `workingDir = rootDir`).
- `core/domain/src/test/kotlin/dev/cubentia/domain/tutor/QuickCheckScenarioTest.kt` L150-190 -- the 10.6 residual-risk measurement (prints "Observed-face-preserving sequences: P of S; residual aliases of a match: A").
- `docs/scan-accuracy.md`, `docs/spikes/oq-4-guided-scan.md` (live-baseline block) -- fixture accuracy to summarize.
- Route tests to extend: `app/src/testDebug/kotlin/dev/cubentia/app/{QuickScanRouteTest (L160-163 regex), ScanRouteTest, QuickCheckRouteTest, CaseRecognitionRouteTest, PlacementFromScanRouteTest}.kt` (ShadowLog on `ScanDiagnostics.TAG`).

## Tasks & Acceptance

**Execution:**
- `app/src/debug/.../ScanDiagnostics.kt` + release mirror -- add the run-line functions per the I/O matrix. The debug object holds `@Volatile` marks (case capture time, quick-check capture info, placement start) and uses one monotonic clock (`SystemClock.elapsedRealtime`). Each mark is consumed once.
- `GuidedScanActivity.kt` -- wire guided start/finish, quick-check capture mark (Partial in `handle`; Full in `finishWith` when `EXTRA_FULL_QUICK_CHECK`), `onCaseCaptured` at case Done.
- `MainActivity.kt`, `RecognizeFlowViewModel.kt`, `PlacementFlowViewModel.kt`, `AppShell.kt` -- call the new functions; no behavior change.
- `QuickScanMeasurement.kt` -- accept trailing fields, and `elapsedMs=-` (a run that is never a success). `QuickScanMeasurementTest` keeps its existing case unchanged and gains one case with the extended record (`state= initial= device=`) and one with `elapsedMs=-`.
- `core/domain/src/evaluation/.../evaluation/matrix/` (new) -- `MatrixRuns.kt` (parse cell file names `<device>_<cube>_<light>_<feature>.log`; light ∈ `daylight|warm|cool|low`, feature ∈ `guided|quickscan|quickcheck|recognize|placement`; parse only that feature's run lines), `MatrixTruth.kt` (CSV `cell,run,truth`; `#` comments; run = 1-based index or `*`; truth = scramble Notation for guided/quickscan, `match`/`mismatch` for quickcheck, a Case id or `LL_SOLVED`/`NOT_AT_LL` for recognize, a stage id or `SOLVED` for placement), `MatrixScoring.kt` (metrics + verdicts, Design Notes), `MatrixMarkdown.kt` (render + marker replace `<!-- recognition-matrix:start -->`/`end`), `MatrixMain.kt` (args `<logs dir> <truth file> [report]`, default report `docs/spikes/oq-8-recognition-matrix.md`).
- `core/domain/build.gradle.kts` -- `evaluateMatrix` task, default args `Fixtures/matrix`, `Fixtures/matrix/truth.csv` and the report; `--args` overrides; paths resolve from the repo root.
- `core/domain/src/test/kotlin/dev/cubentia/domain/recognition/MatrixEvaluationTest.kt` (new) -- each line parser (all five plus foreign lines ignored), cell-name parsing and skipping, truth parsing and its errors, scoring against a synthetic truth (accepted-sticker accuracy, initial-read accuracy, confidently wrong, centers excluded, valid ≤ 3 corrections, false VERIFIED incl. a VERIFIED run without `matches`, Quick Check CANCELLED in the match-confirmed denominator, wrong single Case, SEVERAL missing truth, placement agreement, timing NO DATA when an accepted run has no time, NO DATA), a malformed line keeping later runs on their own truth rows, a populated two-device report (each device's rows and verdicts plus the coverage line inside the markers, via `MatrixMain`-equivalent render on temp files), and marker replace.
- `docs/spikes/oq-8-recognition-matrix.md` (new) -- hand-written: purpose, capture procedure (`adb logcat -c`, then `adb logcat -s CubentiaScanDiag > Fixtures/matrix/<cell>.log`, one file per cell, a scramble per run noted in `truth.csv`), file naming, truth format with an example, targets table, generated block (produced by running the task on an empty matrix: all NO DATA). Also: the 10.6 residual risk quoted with its source and regenerated by running `QuickCheckScenarioTest`, plus a recommendation placeholder; a fixture accuracy summary linking `docs/scan-accuracy.md` and oq-4; a PO recommendation placeholder table (Guided / Quick Scan / Quick Check / Placement / Recognize × OFF/BETA/GA, "PO decision — device session C"); and a manual-fallback checklist per flow (`- [ ]` items).
- Route tests -- assert each new run line's shape on its real path; update the QuickScanRouteTest regex to the 5 fields + `state= initial= device=`. Also: system Back in a running Guided 6-face Scan yields exactly one `guidedScan run … outcome=CANCELLED` line and a cancelled result; an accepted Full Quick Check through `MainActivity` yields one `quickCheck run kind=Full captures=<n> elapsedMs=<digits>` line; a cancelled Partial Quick Check yields one `quickCheck run … certainty=CANCELLED` line; the Guided route test scans a cube built from a known scramble and asserts the logged `state=` equals the letters of `CubeState.SOLVED.apply(scramble).reoriented(Orientation.HOME).toFacelets()` (the evaluator's truth formula — proves the frames agree end to end).
- `app/src/testDebug/kotlin/dev/cubentia/app/ScanDiagnosticsRunLineTest.kt` (new, Robolectric) -- run-line shapes the routes do not reach: guided CANCELLED with no review (`valid=false state=- initial=-`), guided FELL_BACK_TO_EDITOR with a review, recognize SEVERAL (sorted candidates)/UNIQUE/CANCELLED/EDITOR, placement kind + `accepted=none` on reset, and no placement line for a manual-entry suggestion.

**Acceptance Criteria:**
- Given a debug build, when each flow finishes on its main-app entry point, then exactly one `<feature> run` line with the I/O-matrix fields appears under `CubentiaScanDiag` (route tests). Given release, then the functions are no-ops and `spotlessCheck build` passes the manifest, denylist and Learner-text gates.
- Given a logs dir and truth CSV, when `./gradlew :core:domain:evaluateMatrix --args="<dir> <truth>"` runs, then the report's generated block holds per-cell, per-device and per-feature results against every §4.8 target, and the hand-written sections are untouched.
- Given the committed report, then it states the 10.6 residual rate with its source, summarizes fixture accuracy, and leaves the recommendation and checklist results as marked placeholders. No device numbers are invented.
- Given `./gradlew spotlessCheck build`, then it passes, including `QuickScanMeasurementTest` unchanged and Kover ≥ 80%.

## Spec Change Log

### 2026-11-18 — Review round 1 (bad_spec, loop 1)

- **Trigger:** the intent-alignment layer found that the intent defines "Stickers correct = accepted facelets vs. the state from the truth scramble", while the Design Notes gated ≥ 99.5% on the pre-correction `initial` read. Other verified findings were folded in so the re-derivation converges:
  - malformed lines shifted run indices;
  - `matches=-` on a VERIFIED run was not counted against;
  - Quick Check cancels went unlogged;
  - Quick Check confidently-wrong had no target;
  - untimed accepted runs could pass timing;
  - fixed centers inflated sticker rates;
  - `QuickScanMeasurement` dropped `elapsedMs=-`;
  - the Confirm-card path leaked a capture mark;
  - placement collapsed its outcome kinds;
  - there was no coverage line;
  - the Back, Full Quick Check, populated-report and end-to-end frame behaviors had no tests.
- **Amended:**
  - Design Notes: scoring now has accepted-state sticker accuracy (gated), initial-read accuracy (reported), centers excluded, run indices and malformed lines, a coverage line, and the app-side rules.
  - Tasks: the new tests and the QuickScanMeasurement cases.
- **Known-bad state avoided:**
  - a report whose ≥ 99.5% verdict measures something other than what the PO defined;
  - truth rows silently misaligned after one truncated logcat line;
  - Quick Check cancels missing from the confirmation rate.
- **KEEP** (attempt 1 worked well; the saved patch is `_bmad-output/implementation-artifacts/spec-10-8-attempt-1.patch`; start from it):
  - the whole run-line grammar and field order;
  - `device` from `Build.MODEL` with `startRun()` returning null when `Build.MODEL` is null (plain JVM VM tests log nothing);
  - the `ScanDiagnostics` mark/consume design;
  - `GuidedScanViewModel.isGuidedRun` / `guidedStartedAt` / `quickCheckStartedAt`;
  - the PlacementFlowViewModel hooks (`accepted`, `scanningAgain`, `onCleared`, reset/back) and AppShell calls;
  - `MatrixTruth` CSV parsing and errors;
  - `MatrixCell` naming;
  - `MatrixMarkdown.replace` with CRLF handling;
  - the report document text (residual-risk paragraph naming `R U2 R'` with {B, D}, regenerated 44 of 123500 / 1; fixture summary; placeholder table; checklist);
  - all route-test assertions;
  - `ScanDiagnosticsRunLineTest`.

## Review Triage Log

### 2026-11-18 — Review pass (loop 1 re-derivation)
- verdicts: 30 findings — high 0, medium 3, low 17, false 10, maybe-false 0
- findings:
  - `[false]` `[reject]` (blind) The ≥ 99.5% gate on corrected `state` can pass after manual edits — the intent defines "Stickers correct = accepted facelets vs truth"; the gate implements that definition, and initial-read accuracy plus confidently wrong (gated) are reported beside it for the PO.
  - `[false]` `[reject]` (blind) Confidently wrong lacks Case recognition and Full Quick Check coverage — the intent scopes it "where available"; those paths have no independent sticker truth.
  - `[low]` `[reject]` (blind) Coverage counts empty logs or unexpected tokens — the coverage line lists every token seen and the per-cell table shows run counts, so the PO sees it; enumerating a required-cell list adds configuration for a supervised session.
  - `[low]` `[reject]` (blind) carried: logged `device=` not validated against the filename token — same claim as pass 1; operator naming error, unlikely.
  - `[low]` `[reject]` (blind) A Quick Check line with only `device=` counts as a non-false-verified — the app always writes `certainty` (CANCELLED included); only a hand-edited log could do this.
  - `[low]` `[patch]` (blind) A measured every-run breach is hidden as NO DATA when another run lacks its value — fixed: `every()` checks recorded breaches first; test `a measured breach is missed despite an untimed run…`.
  - `[low]` `[patch]` (blind) The timing interval and repeat-user setup are undefined — fixed: the report now defines each `elapsedMs` interval and one untimed warm-up scan per device.
  - `[low]` `[patch]` (blind) Placement attempts without a suggestion log no line, which can shift numbered truth rows — fixed: the report says truth rows number logged runs, not attempts (cancelled scans show only as `guidedScan run`).
  - `[low]` `[reject]` (blind) carried: correction distribution not reported — same claim as pass 1.
  - `[false]` `[reject]` (blind) carried: residual-risk recommendation left as placeholder — the intent requires a placeholder.
  - `[low]` `[patch]` (edge) every() reports NO DATA before a measured breach — grouped with the blind every() row.
  - `[false]` `[reject]` (edge) A foreign message containing the marker consumes a run index — capture is `adb logcat -s CubentiaScanDiag`, so only this tag's lines are in the file, and no other CubentiaScanDiag line contains `<feature> run `.
  - `[false]` `[reject]` (edge) Missing certainty on a Quick Check line counts as no false VERIFIED — `onQuickCheckRun` always writes the check's certainty or CANCELLED; it is never `-` on an emitted line.
  - `[low]` `[reject]` (edge) Duplicate faces or bad letters in a Partial `observed` distort the rate — the line comes from `PhysicalObservation.Partial`, a map keyed by Face with Color letters; unreachable from the app.
  - `[low]` `[reject]` (edge) An ACCEPTED line without `state` counts as a wrong accept — an accepted scan always has a review, so `state` is 54 letters; unreachable from the app.
  - `[low]` `[reject]` (edge) A logs path that is a regular file yields an empty report — operator would pass a file only by mistake and sees NO DATA; adding the guard is not a direct correction.
  - `[false]` `[reject]` (edge) A late analyzer callback after Back leaves a stale capture mark for a later run — the next Quick Check overwrites the mark at its own terminal (`handle` or `finishWith`) before `MainActivity` consumes it.
  - `[low]` `[patch]` (edge) Placement cancels emit no line and shift indices — grouped with the blind Placement row (doc fix).
  - `[low]` `[patch]` (edge) A stale/superseded Quick Check result discards its mark without a line — grouped with the blind Placement row (doc: truth rows number logged runs).
  - `[false]` `[reject]` (edge) Guided ≥ 99.5% uses corrected stickers — grouped with the first blind row; intent-defined.
  - `[medium]` `[patch]` (verification-gap) Accepted Quick Scan `state`/`initial` payload never asserted — fixed: QuickScanRouteTest asserts the accepted line's `state=` equals the solved letters and `initial=` is 54 letters.
  - `[medium]` `[patch]` (verification-gap) Full Quick Check route leg leaves certainty and matches unchecked — fixed: regex now requires `certainty=VERIFIED matches=true observed=<54>`.
  - `[medium]` `[patch]` (verification-gap) Saved non-Stage-1 placement choice never asserted — fixed: AppShellPlacementTest asserts `suggested=<stage 3> accepted=<stage 3>` (debug only, `BuildConfig.DEBUG`).
  - `[low]` `[patch]` (verification-gap other) every() ordering hides a measured breach — grouped with the blind every() row.
  - `[false]` `[reject]` (intent) No test against a real `adb logcat` capture — device runs are the PO's session C per the intent; the parser accepts the logcat prefix (tested).
  - `[low]` `[reject]` (intent) carried: Guided Back handling now routes through `finishWith` — resolved in loop 1 (Design Notes rule, Back route tests); same RESULT_CANCELED.
  - `[false]` `[reject]` (intent) Extra fields beyond the literal list — additions the evaluator needs (`initial`, `matches`, `expectedHome`, `device`); nothing requested is missing; Guided has no fallback faces.
  - `[low]` `[patch]` (intent) Per-cell rows of a non-gating device always show NO DATA — fixed: such rows show the verdict *reported only*; test `a non-gating device's rows are reported only`.
  - `[low]` `[patch]` (intent) Recognize truth `LL_SOLVED`/`NOT_AT_LL` never scored — fixed: new reported metric "Outcome matches truth"; tested.
  - `[false]` `[reject]` (intent) The "regenerated" 10.6 claim is unproven — the scenario test was run in this build and printed "Observed-face-preserving sequences: 44 of 123500; residual aliases of a match: 1".

### 2026-11-18 — Review pass
- verdicts: 35 findings — high 0, medium 13, low 14, false 8, maybe-false 0
- findings:
  - `[medium]` `[bad_spec]` (blind) Malformed run lines dropped before indexing shift later runs onto the wrong truth row — MatrixRuns.parse filtered before mapIndexed; amended: every marker line is a run, malformed listed.
  - `[low]` `[bad_spec]` (blind) Duplicate keys / VERIFIED without `matches` scored as non-event — associate overwrote; falseVerified required parseable matches; amended: duplicate key = malformed, missing matches counts against.
  - `[low]` `[reject]` (blind) Logged `device=` not cross-checked against filename device token — operator naming error; fix needs alias mapping complexity; unlikely in a supervised session.
  - `[medium]` `[bad_spec]` (blind) Per-feature verdict over a partly recorded matrix looks complete — only present cells rendered; amended: coverage line per feature.
  - `[medium]` `[bad_spec]` (blind) Fixed centers inflate sticker metrics by 6/54 — ScanReview forces centers; amended: per-sticker metrics count 48 non-center stickers.
  - `[low]` `[bad_spec]` (blind) Accepted runs without elapsedMs silently dropped from timing — times() used mapNotNull; amended: timed/accepted shown, NO DATA if any missing.
  - `[medium]` `[bad_spec]` (blind) Quick Check cancellations never logged, so match-confirmed and captures omit them — MainActivity only logged applied Observed; amended: CANCELLED run line.
  - `[false]` `[reject]` (blind) Mismatch/Full Quick Check cannot contribute to confidently wrong — intent scopes it "where available"; mismatch truth has no sticker state by design.
  - `[low]` `[reject]` (blind) Placement cancels/editor fallbacks have no placement line — Placement has no §4.8 target and the same cell log carries the guidedScan line for that scan; spec states scan-originated suggestions only.
  - `[low]` `[reject]` (blind) Correction distribution not reported — cosmetic; the target is a threshold rate, which is reported.
  - `[low]` `[bad_spec]` (blind) QuickScanMeasurement drops `elapsedMs=-` runs — regex requires digits while the app now logs `-` before a first corner frame; amended: accept `-` as a non-success run.
  - `[false]` `[reject]` (blind) Residual-risk recommendation left as PO placeholder — the intent requires a recommendation placeholder.
  - `[medium]` `[bad_spec]` (edge) Malformed line shifts truth — same root cause as the first blind row.
  - `[low]` `[bad_spec]` (edge) Duplicate field key overwrites — grouped with the blind duplicate-key row.
  - `[low]` `[bad_spec]` (edge) Timing passes on an incomplete subset — grouped with the blind timing row.
  - `[low]` `[bad_spec]` (edge) VERIFIED with invalid `matches` not counted — grouped with the blind duplicate/matches row.
  - `[low]` `[bad_spec]` (edge) Confirm-card (`effectId == -1`) path with a stale revision leaves the capture mark for a later check — discard was only in the accepted branch; amended: always discard on that path.
  - `[low]` `[reject]` (edge) A second capture completing while the first is suspended in dispatch consumes the wrong mark — quickInFlight allows one camera round trip at a time; a new capture takes seconds vs a sub-ms dispatch; unlikely, fix adds plumbing.
  - `[medium]` `[bad_spec]` (edge) Cancelled Quick Checks disappear from run counts — grouped with the blind cancellation row.
  - `[false]` `[reject]` (edge) Placement attempts ending before a suggestion have no record — the spec's I/O row says only scan-originated suggestions are logged; matches the claim.
  - `[medium]` `[bad_spec]` (edge) Quick Check confidently-wrong reported without the 0.1% target — intent applies ≤ 0.1% to all modes where available; amended: gated target.
  - `[medium]` `[bad_spec]` (verification-gap) No test that QuickScanMeasurement counts the extended record — amended: added case.
  - `[medium]` `[bad_spec]` (verification-gap) No test for system Back in Guided Scan logging CANCELLED — amended: route test.
  - `[medium]` `[bad_spec]` (verification-gap) No test for an accepted Full Quick Check run line — amended: route test.
  - `[medium]` `[bad_spec]` (verification-gap) No populated-report test; per-device table could vanish undetected — amended: two-device render test.
  - `[low]` `[bad_spec]` (verification-gap other) QuickScanMeasurement drops `elapsedMs=-` cancellations — grouped with the blind QuickScanMeasurement row.
  - `[medium]` `[bad_spec]` (intent) Sticker accuracy gated on initial read, while the intent defines it as accepted facelets vs truth — root cause in Design Notes; amended: accepted-state accuracy gated, initial-read reported.
  - `[medium]` `[bad_spec]` (intent) No end-to-end test that the logged scan frame equals the evaluator's truth frame — amended: Guided route test compares the logged state to the truth formula for a known scramble.
  - `[low]` `[bad_spec]` (intent) Guided BackHandler behavior change undocumented — result code is the same RESULT_CANCELED (ScanFlowContract.encode); amended: rule stated in Design Notes, covered by the Back route test.
  - `[low]` `[bad_spec]` (intent) Quick Scan `elapsedMs=-` vs the 11.1 parser — grouped with the blind QuickScanMeasurement row.
  - `[false]` `[reject]` (intent) Quick Scan validity not measured — ScanReviewScreen enables Accept only when `ScanReview.accepted` (CubeValidator) is non-null, so ACCEPTED implies valid.
  - `[false]` `[reject]` (intent) "0 false VERIFIED" also scored on device runs — an additional measurement, not a defect; the scenario-suite rate is quoted as required.
  - `[false]` `[reject]` (intent) Gating tokens hard-coded / Lenovo token untested — tokens are the sanitized lowercased Build.MODEL already used by Fixtures/live; documented in the report.
  - `[false]` `[reject]` (intent) Guided logs only repromptFaces, not fallback faces — the Guided 6-face Scan has no fallback; intent says "if applicable".
  - `[false]` `[reject]` (intent) Report placeholders / memlog untouched — exactly what the intent requires.

## Design Notes

**Scoring (per cell, per device, per feature; verdicts only on the gating devices `lenovo-tb128xu` and `sm-s721u1`, the device tokens of the existing fixtures; other devices are reported only):**
- Guided / Quick Scan: valid ≤ 3 = `outcome=ACCEPTED && corrections ≤ 3 && (valid=true, or no valid field on a Quick Scan line — Quick Scan's Accept is enabled only for a valid state)` over all runs (≥ 98% / ≥ 95%). Time = median `elapsedMs` of accepted runs (< 45 s / < 15 s); the value shows `timed/accepted`, and the verdict is NO DATA when any eligible accepted run has no time. Per-sticker metrics count only the 48 non-center stickers of each scan (the prompt fixes centers, so they cannot be misread).
  - **Sticker accuracy (the PO's definition, gated):** accepted `state` vs the truth facelets, over ACCEPTED runs with a truth. Guided ≥ 99.5% verdict counts only the `warm` and `cool` cells (normal indoor light); Quick Scan reports it without a target. Every light is still reported.
  - **Initial-read accuracy (reported, no target):** `initial[i] == truth[i]` over runs with a 54-letter `initial` and a truth; a `-` (uncertain) counts as not read.
  - **Confidently wrong (≤ 0.1%, gated):** an `initial` letter that is not `-` and differs from truth.
  - *Accepted but wrong* = `state != truth` on accepted runs, per run (no target, always reported).
- Quick Check: false VERIFIED = `certainty=VERIFIED` and `matches` missing, invalid, or `!= truth` (target 0; missing evidence counts against). Match confirmed = VERIFIED with `matches=true`, over truth=`match` runs including CANCELLED ones (≥ 98%). Captures: met only when every run (cancelled included) has ≤ 2; NO DATA if any gating run lacks a count. Confidently wrong (≤ 0.1%, gated) where available: on truth=`match` Partial runs, observed non-center letters vs `expectedHome` on the observed faces.
- Recognize: wrong single Case = UNIQUE with a case other than the truth, over UNIQUE runs (≤ 1%). SEVERAL without the truth among its candidates is reported. Time is met only when every result run is < 1000 ms (UNIQUE, SEVERAL, NO_FIT, LL_SOLVED, NOT_AT_LL).
- Placement: suggestion = truth share, and accepted ≠ suggested count. No §4.8 target, reported only.
- A verdict is NO DATA with 0 runs. Each rate also gives its sample size and, when 0 events are seen, the rule-of-three bound.
- **Run indices:** every line carrying the feature's `<tag> run ` marker is one run, numbered in file order, so truth rows stay aligned. A line whose fields do not parse (bad token or a duplicate key) is still a run, with no fields, and is listed under "Malformed lines" as `file:line`.
- **Coverage:** the per-feature table is preceded by one coverage line per feature: cells present, and the device, cube and light tokens seen. That way a MET over a partly recorded matrix is visibly partial.

**App-side rules added in review (round 1):**
- System Back in any non-calibration Guided Scan phase goes through `finishWith(ScanOutcome.Cancelled)`, which gives the same `RESULT_CANCELED` as the default finish, so a Back-abandoned scan is logged. Calibration keeps its own handler.
- **Quick Check cancel:** `GuidedScanActivity.finishWith` records the capture mark for any non-accepted end of a Partial or Full Quick Check. `MainActivity`'s Cancelled branch (not the Confirm card's `effectId == -1` path) then logs `quickCheck run kind=- captures=C elapsedMs=N certainty=CANCELLED matches=- observed=- expected=<54> expectedHome=<54> device=M`.
- The Confirm card's `effectId == -1` path always discards the capture mark, so a later check never inherits it.
- Placement logs the real `PlacementOutcome` kind: `SUGGESTED`, `AT_LAST_LAYER`, `NON_WHITE_CROSS` or `SOLVED`.

## Verification

**Commands:**
- `export JAVA_HOME=/usr/lib/jvm/java-25-openjdk-amd64 ANDROID_HOME=$HOME/Android/Sdk && ./gradlew spotlessApply` -- expected: formatting applied
- `./gradlew :core:domain:test --tests '*MatrixEvaluationTest' --tests '*QuickScanMeasurementTest' --tests '*QuickCheckScenarioTest'` -- expected: pass; scenario test prints the residual rate
- `./gradlew :core:domain:evaluateMatrix` -- expected: report block regenerated (NO DATA)
- `./gradlew :app:testDebugUnitTest --tests '*RouteTest'` -- expected: pass
- `./gradlew spotlessCheck build` -- expected: BUILD SUCCESSFUL

## Auto Run Result

Status: done

**Summary:** This is the code part of Story 10.8, the measurement tooling for device session C. Debug builds log one `CubentiaScanDiag` run line per finished Guided 6-face Scan, Quick Scan, Quick Check, Case recognition and Placement run; release stays a no-op. The offline evaluator `:core:domain:evaluateMatrix` scores per-cell logs (`Fixtures/matrix/<device>_<cube>_<light>_<feature>.log`) against a `truth.csv` and the §4.8 targets, and rewrites the generated block of `docs/spikes/oq-8-recognition-matrix.md`. The committed block reads NO DATA. The OFF/BETA/GA recommendations, the residual-risk recommendation and the fallback checklist are left as PO placeholders. No device results are fabricated, and the PRD memlog is untouched.

**Files changed:**
- `app/src/debug/kotlin/dev/cubentia/app/ScanDiagnostics.kt`: run-line functions, capture and start marks, and the device model.
- `app/src/release/kotlin/dev/cubentia/app/ScanDiagnostics.kt`: matching no-op stubs.
- `app/src/camera/.../GuidedScanActivity.kt`: guided, quick-check and case start and finish hooks; system Back goes through `finishWith(Cancelled)`.
- `app/src/main/.../MainActivity.kt`: Quick Check run line (applied or cancelled); mark discard; Placement rescan hook.
- `app/src/main/.../ui/recognize/RecognizeFlowViewModel.kt` and `ui/placement/PlacementFlowViewModel.kt`, `ui/shell/AppShell.kt`: recognize and placement run lines.
- `core/domain/src/evaluation/.../matrix/{MatrixRuns,MatrixTruth,MatrixScoring,MatrixMarkdown,MatrixMain}.kt`: the evaluator. `core/domain/build.gradle.kts`: the `evaluateMatrix` task.
- `core/domain/src/evaluation/.../quickscan/QuickScanMeasurement.kt`: accepts trailing fields and `elapsedMs=-`.
- Tests:
  - JVM: `MatrixEvaluationTest` (new), `QuickScanMeasurementTest` (+2 cases).
  - Robolectric: `ScanDiagnosticsRunLineTest` (new); line assertions in `ScanRouteTest`, `QuickScanRouteTest`, `QuickCheckRouteTest`, `CaseRecognitionRouteTest`, `PlacementFromScanRouteTest`, `AppShellPlacementTest`.
- `docs/spikes/oq-8-recognition-matrix.md`: procedure, truth format, targets, generated block, 10.6 residual risk (regenerated 44 of 123,500 sequences, 1 alias `R U2 R'` with {B, D}), fixture-accuracy summary, PO placeholder table, manual-fallback checklist.

**Review:**
- **Pass 1:** 35 findings. Routed bad_spec (loop 1): the ≥ 99.5% sticker gate had been put on the initial read, against the PO's accepted-facelets definition. Other verified fixes were folded into the amended spec and re-derived from `spec-10-8-attempt-1.patch`. 13 findings were rejected; their reasons are in the triage log.
- **Pass 2:** 30 findings. 11 rows were patched (3 medium, 8 low; 6 root causes): every() ordering, reported-only verdicts for non-gating devices, a recognize outcome-vs-truth metric, timing and truth-row docs, and three test assertions. 19 were rejected (10 false, 9 low); reasons are in the triage log. Nothing was deferred.

**Follow-up review recommended: true.** Three medium entries were patched in pass 2. All three tighten test assertions; no review layer saw those patches. Patched counts in pass 2: high 0, medium 3, low 8.

**Verification:**
- `./gradlew spotlessApply`: OK.
- `:core:domain:test` (MatrixEvaluationTest, QuickScanMeasurementTest, QuickCheckScenarioTest; the last printed 44 of 123500, 1 alias): pass.
- `:core:domain:evaluateMatrix`: report regenerated (NO DATA).
- `:app:testDebugUnitTest` route tests + ScanDiagnosticsRunLineTest + AppShellPlacementTest: pass.
- `./gradlew spotlessCheck build`: BUILD SUCCESSFUL.

**Residual risks:**
- Gating device tokens are hard-coded to `lenovo-tb128xu` and `sm-s721u1` (sanitized, lowercased `Build.MODEL`). Confirm them on the first device-C capture.
- The ≥ 99.5% sticker gate uses the accepted, corrected state, as the PO defined it. The camera's own accuracy is shown in the "Initial-read accuracy" and "Confidently wrong" rows; the PO should weigh both.
- Quick Scan validity is inferred from Accept, which is enabled only for a valid state; there is no `valid` field.
- System Back in Guided Scan now goes through `finishWith(Cancelled)`, which gives the same RESULT_CANCELED.

