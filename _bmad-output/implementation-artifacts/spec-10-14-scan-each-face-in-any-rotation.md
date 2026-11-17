---
title: 'Scan each face in any rotation'
type: 'feature'
created: '2026-11-16'
status: 'done'
baseline_revision: '9cf7ad253f3914c0fa6dbc55bba658a3be9935ad'
review_loop_iteration: 0
followup_review_recommended: false
context:
  - '{project-root}/_bmad-output/implementation-artifacts/epic-10-context.md'
  - '{project-root}/AGENTS.md'
warnings: ['oversized']
deferred:
  - summary: >-
      The re-prompt round through GuidedScanActivity (sixth capture yielding Capture + Remeter, the re-prompt viewfinder, then Done to review) has no Activity-level test.
    evidence: |-
      Intent-alignment layer: GuidedScanActivity is unchanged and untested; the round is verified in GuidedScanController JVM tests and GuidedScanFlowTest with an injected reprompt. Same Activity-glue root deferred in Stories 10.2 and 10.3 (needs Hilt + CameraX under Robolectric); check on device in session B2.
    location: >-
      app/src/camera/kotlin/dev/cubentia/app/camera/GuidedScanActivity.kt (handle, phaseOf after a re-prompt)
    severity: medium
---

<intent-contract>

## Intent

**Problem:** The Guided 6-face Scan (10.2) asks for each face "with **white** on top" and `ScanAssembler` turns each face from that prompted up color, so a beginner must hold the right side on top — the hardest part of the scan (SCP 2026-11-16, #158).

**Approach:** Prompt only the center color. `ScanAssembler` works out every face's quarter turn by trying all 4⁶ assignments (centers fixed) and keeping the one `CubeValidator` accepts. When zero or several distinct states fit, the scan never guesses: it re-prompts only the faces involved with the 10.2 prompt naming a top, uses that turn for them, and sends whatever results to 10.3 review with no further loop.

## Boundaries & Constraints

**Always:**
- Fixed face order (green, red, blue, orange, white, yellow) and the 10.2 live repeated-face rule (AC4) stay. First-round prompts name only the center: "Show the **green** center." with one glyph swatch; the captured `FaceSample.upNeighborColor` is null.
- A re-prompt step for a face is the 10.2 step for that face (sides: white on top; white: blue on top; yellow: green on top): "Show the **green** center, with **white** on top." with both swatches; its capture carries that `upNeighborColor` and replaces the face's earlier sample.
- `ScanAssembler` is the only place samples are rotated (AD-9). A sample with `upNeighborColor` keeps today's prompted-turn placement (bad direction → `NO_POSE`). Samples without one are searched only when all six faces are otherwise placeable; with any other gap they become `NO_POSE` (fewer than six faces cannot be oriented from validity).
- Search: over the classified colors (the read color of every sticker, UNCERTAIN included; centers are always home), each unposed face takes 4 turns, posed faces their prompted turn. Assignments giving the same `Facelets` count as one. A **legal piece** = a corner/edge whose colors are a real cubie in a real orientation (twist allowed) and that no other piece of the assignment duplicates. Prune with piece legality first; only all-legal assignments go to `CubeValidator.validate`.
- Resolution: exactly one distinct valid state → `ScanResult.Full` of it (confidence as read, rotated with colors). Otherwise the **faces involved**, ordered: (1) faces whose center read disagrees with their prompt (classified center color ≠ prompted center; a center with `centerPlain == false` — a logo — never counts), then in plan order (2) for ≥ 2 valid states, faces whose 9 colors differ across those states; for 0 valid, faces holding any sticker of an illegal piece in the assignment(s) with the most legal pieces; if that is empty (all pieces legal, but twist/flip/parity fails), faces whose colors differ across those best assignments. Only unposed faces and faces from (1) can be involved.
- When the result is unresolved, `assemble` still returns `Full` built from the first candidate in enumeration order (candidates = the distinct valid states if any, else the assignments with the most legal pieces), and every non-center sticker of a face whose colors differ across the candidates is UNCERTAIN, so review blanks it instead of showing a guessed turn.
- The controller asks for re-prompts at most once per scan: after the sixth first-round capture it computes the faces involved (self-calibration + search); non-empty → a re-prompt round (no `Done` yet); after the last re-prompt capture it emits `Done` with six samples in plan order, and review takes whatever `ScanReview.fromSamples` assembles. In the re-prompt round a capture is rejected as repeated only against stored centers other than the prompted face's own.
- All Learner text in `core/domain/src/main/resources/content/strings/en.json` (NFR-10); the compact-portrait one-screen fit holds for both prompt forms.
- One six-face assembly (search included) under 50 ms on the JVM.

**Never:**
- No change to `ScanResult`, `CubeValidator`, `StickerClassifier`, `SelfCalibration`, `ScanReview` rules, the review screen, `ScanOutcome`/contract, or release camera flags/gates.
- No second re-prompt round, no per-face retake from review, no face identification from center color, no completion from an expected state.
- No change to the debug fixture recorder (`app/src/camera/debug/.../recorder/`): it never uses `GuidedScanPlan`, and its fixtures' pose convention stays as is.
- No timing numbers in `docs/scan-accuracy.md` (the generated span must stay byte-stable).

## I/O & Edge-Case Matrix

| Scenario | Input / State | Expected Output / Behavior | Error Handling |
|----------|--------------|---------------------------|----------------|
| Any rotation | six unposed samples of a scrambled cube, each face turned randomly | `Full` = that cube's facelets; no re-prompt | — |
| Solved / symmetric faces | solved cube, faces turned | one distinct state → `Full` solved | — |
| Several valid states | colors where two turns of a face both validate | faces whose colors differ are re-prompted | — |
| Misread sticker | one sticker wrong → 0 valid | faces holding the illegal piece(s) of the best assignment re-prompted | — |
| Wrong face (saved-calibration center disagrees) | center reads RED on the orange step | that face first in the re-prompt list | — |
| Logo center | `centerPlain = false`, center read differs | not counted as disagreeing | — |
| After re-prompt still invalid | re-prompted faces posed, state invalid | `Done`; review shows best candidate, ambiguous faces blank | no further loop |
| Gap present | one face POOR_QUALITY | `Partial`; unposed faces `NO_POSE` | — |
| Prompted sample | `upNeighborColor` set | placed by prompted turn as today | bad direction → `NO_POSE` |

</intent-contract>

## Code Map

- `core/domain/src/main/kotlin/dev/cubentia/domain/recognition/ScanAssembler.kt` -- `assemble` (L72), `place` (L135–166: direction from `upNeighborColor`, `NO_POSE`), `NET_NEIGHBORS`, `rotateClockwise`. Search lives here.
- `core/domain/src/main/kotlin/dev/cubentia/domain/cube/Facelets.kt` -- internal `FaceletTables.CORNER_FACES/CORNER_FACELETS/EDGE_FACES/EDGE_FACELETS` (home-frame indices) for piece legality; `CubeValidator.validate/problems` (`decodeCorner`/`decodeEdge` show the cubie decoding).
- `core/domain/src/main/kotlin/dev/cubentia/domain/recognition/GuidedScan.kt` -- `ScanStep` (L11), `GuidedScanPlan.steps` (L23), `GuidedScanController` (`store` L305 sets `upNeighborColor`, `capture` L286 repeated-face rule, `step`/`stepIndex`/`done`, `Done` effect L325).
- `core/domain/src/main/kotlin/dev/cubentia/domain/recognition/{SelfCalibration,ScanReview,FaceSample}.kt` -- `SelfCalibration.from(samples)`; `ScanReview.fromSamples` (L72) = self-calibrate → assemble; `FaceSample.centerPlain`.
- `app/src/camera/kotlin/dev/cubentia/app/camera/GuidedScanScreen.kt` -- `GuidedScanPhase.Viewfinder(stepIndex, captured, …)` (L105), progress (L167), `Prompt` (L418–443, reads `GuidedScanPlan.steps[stepIndex]`), `MiniNet` current face (L463).
- `app/src/camera/kotlin/dev/cubentia/app/camera/GuidedScanFlow.kt` -- `phaseOf(controller)`; `resultOf` (Done → `ScanReview.fromSamples`). `GuidedScanActivity.handle` (L345) runs controller effects; keep Activity changes to none or trivial (its glue is untested, deferred since 10.2).
- `core/domain/src/evaluation/kotlin/dev/cubentia/domain/recognition/evaluation/scan/{ScanAccuracy,ScanAccuracyMarkdown,TruthPose}.kt` -- fixture runs with truth-recovered pose (`ScanRun.valid` = prompted-pose valid), markdown span `scan-accuracy:start/end`, `TruthPose.rotateClockwise`; `ScanAccuracy.DEVICES`.
- `core/domain/src/test/kotlin/dev/cubentia/domain/recognition/evaluation/scan/ScanAccuracyTest.kt` -- lazy `REPORT` from `Fixtures/live`; pinned totals.
- `core/domain/src/test/kotlin/dev/cubentia/domain/recognition/{ScanAssemblerTest,GuidedScanTest,RecognitionFixtures}.kt`, `app/src/testDebug/kotlin/dev/cubentia/app/camera/{GuidedScanScreenTest,GuidedScanFlowTest}.kt` -- existing tests that set `upNeighborColor`/plan tops; compact-portrait fit test at L59.
- `core/domain/src/main/resources/content/strings/en.json` -- `msg.scan.prompt` (L616, has `{up}`), `msg.scan.progress` (L625).

## Tasks & Acceptance

**Execution:**
- `core/domain/.../recognition/ScanAssembler.kt` -- add the rotation search and resolution rules above to `assemble`; add `repromptFaces(samples, calibration): List<Face>` (ordered faces involved; empty when resolved or nothing to ask); update KDoc.
- `core/domain/.../recognition/GuidedScan.kt` -- `ScanStep.upNeighborColor: Color?`; plan steps without a top; `GuidedScanPlan.reprompt(face)`; controller re-prompt round (injectable `reprompt: (List<FaceSample>) -> List<Face>` defaulting to self-calibration + `repromptFaces`, `NeedsCalibration` → empty), `repeating` flag/progress for the UI, repeated-face rule excluding the prompted face's own sample.
- `core/domain/src/main/resources/content/strings/en.json` -- `msg.scan.prompt` → "Show the {center} center."; add `msg.scan.prompt-with-top` "Show the {center} center, with {up} on top." and `msg.scan.progress-again` "Again: face {n} of {total}".
- `app/src/camera/.../GuidedScanScreen.kt`, `GuidedScanFlow.kt` -- Viewfinder phase carries the current `ScanStep?` and re-prompt progress; `Prompt` picks the key and swatches by `upNeighborColor`; `MiniNet` outlines the current step's face.
- `core/domain/src/test/.../ScanAssemblerTest.kt` -- every I/O-matrix row; property test: random scrambles with random per-face turns, unposed → same facelets; dedup of symmetric faces; ordering of involved faces; mixed posed/unposed.
- `core/domain/src/test/.../GuidedScanTest.kt` -- prompt steps have no top and samples no `upNeighborColor`; re-prompt round flow (prompted faces only, in order, top carried, replaces sample, `Done` with six in plan order, no second round); repeated-face rule in both rounds; update existing tests for the new plan.
- `core/domain/src/evaluation/.../scan/ScanAccuracy.kt`, `ScanAccuracyMarkdown.kt` -- any-rotation runs: each run's samples with every face's stickers turned by a seeded random quarter turn (fixed seed) and `upNeighborColor` null; per device and input: scans, prompted-pose valid, same `Facelets`, needs re-prompt, re-prompt share; render a new subsection inside the span; regenerate `docs/scan-accuracy.md` with `./gradlew :core:domain:evaluateScanAccuracy` and add a short Method line.
- `core/domain/src/test/.../evaluation/scan/ScanAccuracyTest.kt` -- every prompted-pose-valid run yields the same `Facelets` when turned; pinned re-prompt counts; a warmed six-face assembly (median of several) < 50 ms.
- `app/src/testDebug/.../GuidedScanScreenTest.kt`, `GuidedScanFlowTest.kt` -- center-only prompt text and one swatch; re-prompt text "…with white on top" from content strings; compact-portrait one-screen fit for both forms; phase mirrors re-prompt step.

**Acceptance Criteria:**
- Given the Guided Scan, when a face is prompted, then the prompt reads "Show the green center." from content strings, the order and repeated-face rule are unchanged, and the stored sample has no `upNeighborColor`.
- Given six unposed captures, when `ScanAssembler` assembles them, then it tries the 4⁶ turns and returns the single distinct valid state, and no other code rotates samples.
- Given the session A/A2 fixtures turned by a seeded random quarter turn, when the JVM suite runs, then every prompted-pose-valid scan yields the same `Facelets`, `docs/scan-accuracy.md` reports the re-prompt share per device, and one assembly is under 50 ms.
- Given zero or several valid states, when the sixth face is captured, then only the faces involved are re-prompted in the stated order with a named top, their prompted turn is used, and the result goes to review with no further loop.
- Given `./gradlew spotlessCheck build`, when run, then it passes (Kover ≥ 80 % on `:core:domain`, release gates unchanged).

## Spec Change Log

## Review Triage Log

### 2026-11-16 — Review pass
- verdicts: 20 findings — high 0, medium 3, low 8, false 9, maybe-false 0
- findings:
  - `[false]` `[reject]` (blind) Default re-prompt can't catch a wrong center because self-calibration takes the centers from the same samples — a wrong face still makes every assignment invalid, so the faces holding its illegal pieces are re-prompted; the center rule is for logo-derived and (10.4) saved calibration centers, as the spec's I/O row states.
  - `[low]` `[reject]` (blind) Entering the re-prompt round keeps the just-captured hold, so re-asking for the sixth face needs the frame to change first — deliberate: the hold stops a capture before the Learner turns the face to the named top; any movement or a misaligned frame releases it.
  - `[low]` `[reject]` (blind) Dedup by colors keeps the first assignment's confidence positions for symmetric faces — harm needs a wrong uncertain guess that still leaves a face's colors symmetric under a turn; rare, and a fix adds a confidence merge.
  - `[false]` `[reject]` (blind) Mini net shows faces as held, not oriented — it shows what the Learner captured, as seen; the oriented result is shown in review.
  - `[false]` `[reject]` (blind) Any-rotation table lacks truth accuracy; "same Facelets" can match an invalid read — the column is documented as matching the prompted read; truth accuracy is reported in the existing tables.
  - `[low]` `[reject]` (blind) Calibration failure would count as no re-prompt in the any-rotation table — no committed fixture fails calibration (all runs are Full); adds a column for a case not present.
  - `[low]` `[patch]` (blind) 50 ms test times only a resolving scan — the timing test now also times a scan with no valid state (`one six-face assembly in any rotation takes under 50 ms`).
  - `[low]` `[patch]` (blind) No test for the all-pieces-legal fallback (twist/flip/parity) — added `a twisted corner with every piece legal resolves to its one best state with nothing to re-prompt`.
  - `[medium]` `[patch]` (blind) "ambiguous faces blank" test never asserts blanks — its fixture had a unique best candidate; renamed it to what it checks and added `no valid state with tied best candidates blanks the faces that differ in review` asserting UNCERTAIN and `ScanReview.from` blanks on U, F, D, B.
  - `[low]` `[reject]` (blind) Re-prompt controller tests use the shutter, not auto-capture — both paths go through the same `capture`/`store`, and auto-capture is covered by the existing controller tests.
  - `[false]` `[reject]` (edge) Empty plan throws at `requireNotNull(step)` — the plan is the fixed six steps; no caller builds an empty plan.
  - `[medium]` `[patch]` (verification-gap) Zero-valid fallback blanking unverified — same root as the blind blank-assertion row; same new test.
  - `[false]` `[reject]` (intent) Recorder left unchanged (reading a of AC6) — recorded spec decision (Design Notes); the recorder never prompted a top, and "keeps" requires no change.
  - `[low]` `[patch]` (intent) 50 ms checked on a resolving case only — same as the blind timing row; same fix.
  - `[false]` `[reject]` (intent) Unresolved scans reach review as first candidate with ambiguous faces blanked, not "as today" — the result type and review input are unchanged (`ScanResult.Full`); blanking is how "never guesses" reaches review.
  - `[medium]` `[defer]` (intent) Activity re-prompt round untested — Activity unchanged; same Activity-glue deferral as 10.2/10.3; added to `deferred`.
  - `[false]` `[reject]` (intent) Center-disagree rule rarely fires in the shipped flow — same as the blind wrong-center row.
  - `[false]` `[reject]` (intent) Fixture turn is a sticker permutation, not a physical re-capture — that is what the AC asks for (fixtures turned by a seeded quarter turn); physical turns are device session B2.
  - `[low]` `[reject]` (intent) A gap makes every unposed face NO_POSE — the Guided Scan stores only ACCEPTABLE samples with distinct prompted centers, so a gap is not reachable from the guided flow.
  - `[false]` `[reject]` (intent) Report row "valid 4, same 5" — documented column meaning; the AC constrains only prompted-valid scans, all of which match.

## Design Notes

Why search over classified colors, not only CONFIDENT ones: review (10.3) blanks UNCERTAIN stickers anyway, and excluding them would make most real scans unorientable. Why prune by legal pieces: 4,096 full `CubeValidator` runs risk the 50 ms budget; per-piece table lookups are cheap, and the same counts give the "most legal pieces" rule for free.

Why the decision sits in `GuidedScanController`: the controller is pure and JVM-tested, while the Activity glue has no test harness (10.2/10.3 deferral). The sixth capture returns `Capture` + `Remeter` instead of `Done` when re-prompts are needed, which the Activity already handles.

Fixture recorder AC: the recorder has never prompted a top (it names faces by center; pose is recovered from truth by `TruthPose`). "Keeps the prompted top" is read as: the recorder is untouched by this story, so its fixtures keep the same known-pose convention.

## Verification

**Commands:**
- `export JAVA_HOME=/usr/lib/jvm/java-25-openjdk-amd64 ANDROID_HOME=$HOME/Android/Sdk && ./gradlew spotlessCheck build` -- expected: BUILD SUCCESSFUL.
- `./gradlew :core:domain:evaluateScanAccuracy && git diff --stat docs/scan-accuracy.md` -- expected: span regenerated with the any-rotation table; a second run gives no diff.
- `git diff --stat -- app/src/camera/debug/kotlin/dev/cubentia/app/camera/recorder` -- expected: empty.

**Manual checks (if no CLI):**
- Device session B2 (Lenovo, S24 FE; stickered and stickerless): faces shown in arbitrary turns assemble with no confidently wrong turn; re-prompts only when a face really can't be placed.

## Auto Run Result

**Summary:** The Guided Scan prompts only the center ("Show the green center.") and stores samples with no top. `ScanAssembler` searches the 4⁶ turns of unposed faces (pruned by legal pieces, then `CubeValidator`), dedups by colors, and returns the single valid state; otherwise it returns the first candidate with differing faces UNCERTAIN, and `repromptFaces` names the faces to ask again (center-disagree first, then plan order). `GuidedScanController` runs one re-prompt round with the 10.2 named-top prompt, replacing those samples, then emits `Done` to review. The live fixtures turned by a seeded quarter turn are reported in `docs/scan-accuracy.md`.

**Files changed:**
- `core/domain/.../recognition/ScanAssembler.kt` -- rotation search, resolution, `repromptFaces`.
- `core/domain/.../recognition/GuidedScan.kt` -- center-only plan, `GuidedScanPlan.reprompt`, controller re-prompt round, repeated-face rule excluding the prompted face.
- `core/domain/src/main/resources/content/strings/en.json` -- `msg.scan.prompt` center-only, `msg.scan.prompt-with-top`, `msg.scan.progress-again`.
- `app/src/camera/.../GuidedScanScreen.kt`, `GuidedScanFlow.kt` -- prompt by step (one or two swatches), re-prompt progress, mini net current face.
- `core/domain/src/evaluation/.../scan/ScanAccuracy.kt`, `ScanAccuracyMarkdown.kt`, `docs/scan-accuracy.md` -- any-rotation runs and per-device re-prompt table.
- Tests: `ScanAssemblerTest`, `GuidedScanTest`, `ScanAccuracyTest`, `GuidedScanScreenTest`, `GuidedScanFlowTest`.

**Review findings:** 20 findings. Patched: blank assertion for the zero-valid tie (medium), twisted-corner fallback test (low), 50 ms timing on the no-valid-state path (low). Deferred: Activity-level re-prompt round untested (medium, Activity-glue root). Rejected: see triage rows with reasons.

**Follow-up review recommendation:** false -- first pass patched one medium entry and two low (patched counts: high 0, medium 1, low 2); no named unverified risk beyond the deferred Activity glue.

**Verification:** `./gradlew spotlessCheck build` -- BUILD SUCCESSFUL (after patches). `./gradlew :core:domain:evaluateScanAccuracy` -- span regenerated; re-run byte-identical. Recorder directory -- no changes. Implementer measured ~1.3 ms per assembly.

**Residual risks:** Device session B2 not run. The property test shows roughly 1 in 70 deep scrambles has a face valid in two turns, so some real scans will re-prompt (the test requires ≥ 95% to resolve, and every unresolved one never shows a wrong sticker as confident). Re-prompt share on fixtures: S24 FE 1/4 Kotlin reader, 2/6 center pixel (the scans already invalid when prompted). The recorder AC was read as "unchanged" (it never prompted a top). The 50 ms test is wall-clock (median of 11, large headroom).
