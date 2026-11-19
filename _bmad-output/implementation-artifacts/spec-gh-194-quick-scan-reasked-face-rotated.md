---
title: 'GH-194: Quick Scan re-asked face merged in the corner reads frame'
type: 'bugfix'
created: '2026-11-19'
status: 'done'
baseline_revision: 'c89a4fabb78b2324d81ba395085cf8a258516b8d'
review_loop_iteration: 0
followup_review_recommended: false
context:
  - '{project-root}/AGENTS.md'
warnings: [oversized]
deferred:
  - summary: >-
      On-device confirmation that a Quick Scan grid re-ask held off its named top now merges in the corner frame.
    evidence: |-
      Domain tests model a rotation-90 grid capture as one counterclockwise turn of the held face; this matches the Lenovo Guided log (U read OYBWWRORO, o=90, turn 1 -> OWORWYORB) but no device run has exercised the fix. A Lenovo/S24 Quick Scan device session with a deliberately mis-held re-ask settles it.
    location: >-
      core/domain/src/main/kotlin/dev/cubentia/domain/recognition/QuickScan.kt
    severity: medium (unverified)
  - summary: >-
      Recognize (Story 11.3) last-layer sticker order is checked only by reasoning plus the shared corner-frame projection test.
    evidence: |-
      ScanAssembler.lastLayerView maps corner top cells 7,6 / 5,8 / 1,2 / 3,0 to LastLayerView blocks F/R/B/L, consistent with the net-frame top the projection test proves; no projection-based Recognize test or device run confirms it. A projection test feeding CornerReader output into lastLayerView, or a device session, settles it.
    location: >-
      core/domain/src/main/kotlin/dev/cubentia/domain/recognition/ScanAssembler.kt
    severity: medium (unverified)
---

<intent-contract>

## Intent

**Problem:** In Quick Scan (Story 11.1), a face re-asked in the Guided grid keeps the turn its prompt named ("with blue on top") as a fixed pose. When the Learner holds it another way, the face is merged rotated against the five faces read from the corners, the cube is invalid, and the Learner's corrections follow the rotated face (issue #194: U merged as BROYWROWO, its 180° reversal, instead of OWORWYORB).

**Approach:** When the merged scan with the named tops is not one valid state, Quick Scan lets each face it took from the Guided grid try its four quarter turns against the faces read from the corners (the frame they fix), and keeps that result only when exactly one valid state fits. The re-prompt decision inside the grid round uses the same oriented merge. Recognize (Story 11.3) reads only corners, so it gets a regression test of the corner frame and no code change.

## Boundaries & Constraints

**Always:** Corner-read faces keep their fixed turns. A merge whose named tops already give one valid state is unchanged. The oriented merge is used only when it is exactly one valid state (`ScanBranch.RESOLVED`); otherwise the named-top merge stands and today's re-prompt and review behavior is unchanged. Domain changes stay in pure-JVM `:core:domain`.

**Never:** No feature-flag change (`FeatureFlagLevels.kt` untouched; camera stays OFF in release). No change to `ScanAssembler`'s search rules, `GuidedScanPlan`, prompts, Learner text, the camera adapter, or Recognize/`CaseCapture` code. No filling from an expected state. No planning-artifact edits.

## I/O & Edge-Case Matrix

| Scenario | Input / State | Expected Output / Behavior | Error Handling |
|----------|--------------|---------------------------|----------------|
| Prompted top | Evidence cube (`U R2 F' D L' B2`); one face missing from the corners, re-asked in the grid, held with the named top, camera rotation 90 | `Done` samples assemble to the true 54-sticker state | No error expected |
| Wrong top, every face | Same, the re-asked face held 1, 2 or 3 quarter turns off its named top; for each of U R F D L B | `Done` samples assemble to the true state; for U the merged U is `OWORWYORB`, never `BROYWROWO` | No error expected |
| Involved-face re-ask (round 2) | All six corner faces read confidently, one U corner sticker misread so the merge is invalid and `QuickScanEffect.Fallback(faces, 2)` lists U (at most 2 faces); U re-asked held 180° off, any other listed face re-asked with its named top | Assembles to the true state, U = `OWORWYORB` | No error expected |
| Still invalid | Grid faces cannot make any valid state (existing test: a wrong F sticker) | Named-top merge kept; goes to review invalid as today | Existing behavior |
| Corner frame (Recognize path) | Physically projected cube at holds 1 and 2, camera rotation 0/90/270 | `CornerReader` faces equal `QuickScanPlan` frames of the true state | No error expected |

</intent-contract>

## Code Map

- `core/domain/src/main/kotlin/dev/cubentia/domain/recognition/QuickScan.kt` -- `QuickScanController`: corner samples stored in `onShutter` (orientation 0, plan tops); `onGuidedEffects` replaces a face with a grid capture; `startFallback` round-1 `reprompt` lambda builds `merged` (samples + replacements) and calls `involvedFaces`; `finish()` returns `Done(captured)`. Add a set of grid-captured faces and an `oriented(merged, gridFaces)` helper; use it in the round-1 lambda's `involvedFaces` and in `finish()`.
- `core/domain/src/main/kotlin/dev/cubentia/domain/recognition/ScanAssembler.kt` -- read-only. `place()` (posed = `upNeighborColor` set → single fixed turn; null → `search` tries 4 turns); `trace(samples, calibration).branch` gives `ScanBranch.RESOLVED` for exactly one valid state. Reuse; do not modify.
- `core/domain/src/main/kotlin/dev/cubentia/domain/recognition/SelfCalibration.kt` -- `SelfCalibration.from(samples, saved)`; reuse for the calibration, as `involvedFaces` does.
- `core/domain/src/main/kotlin/dev/cubentia/domain/recognition/GuidedScan.kt` -- read-only. `GuidedScanController.store` stores grid samples with the frame's `orientationDegrees` and the step's `upNeighborColor`.
- `core/domain/src/test/kotlin/dev/cubentia/domain/recognition/QuickScanControllerTest.kt` -- add the matrix tests. Its `hold(index)` helper already gives corner reads in plan frames (D: `[2,5,8,1,4,7,0,3,6]`, L/B reversed); a grid capture at rotation 90 is the upright face turned one quarter counterclockwise in pixel order, passed to `grid.onFrame(stats, 90, now)`. Scramble via `Notation.parse("U R2 F' D L' B2")` (`NotationResult.Parsed.moves`).
- `app/src/testDebug/kotlin/dev/cubentia/app/camera/CornerLocatorTest.kt` -- existing test renders into `guide.quads` (circular). Add one test that paints each facelet of a 3D cube by orthographic projection (hold 1: eye (1,1,1), up +y; hold 2: eye (−1,−1,−1), up −y), turned into frame pixels by −rotation, and asserts `CornerReader.read` faces equal the plan frames (same mapping as the existing test's `expected`). Reuse `grid`, `classify`, `toCornerFrame`, the palette.
- Evidence (read-only): `Fixtures/matrix/lenovo-tb128xu_stka_warm_quickscan.log` (run line), `lenovo-tb128xu_stka_warm_guided.log` (U read `OYBWWRORO`, o=90, turn 1 → `OWORWYORB`).

## Tasks & Acceptance

**Execution:**
- `core/domain/src/main/kotlin/dev/cubentia/domain/recognition/QuickScan.kt` -- track grid-captured faces; add `oriented()` (named-top merge if RESOLVED; else the merge with `upNeighborColor = null` on grid faces if RESOLVED; else named-top merge); apply in `finish()` and the round-1 lambda; KDoc the rule -- re-asked faces merge in the corner frame.
- `core/domain/src/test/kotlin/dev/cubentia/domain/recognition/QuickScanControllerTest.kt` -- add matrix rows 1–3 (every face × held turns 0–3, rotation 90; round-2 U case asserting U = `OWORWYORB`) -- the issue's done criteria.
- `app/src/testDebug/kotlin/dev/cubentia/app/camera/CornerLocatorTest.kt` -- add the physical-projection corner-frame test (matrix row 5) -- proves the Recognize/corner path has no frame mismatch.

**Acceptance Criteria:**
- Given Quick Scan with a face re-asked in the Guided grid, when the Learner holds it with any top, then the scan's `Done` samples assemble (via `ScanReview.fromSamples`/`ScanAssembler`) to the true 54-sticker state whenever exactly one state fits the corner reads.
- Given the existing Guided Scan, Quick Scan and Recognize tests, when the suite runs, then they pass unchanged.

## Spec Change Log

## Review Triage Log

### 2026-11-19 — Review pass
- verdicts: 15 findings — high 0, medium 1, low 9, false 3, maybe-false 2
- findings:
  - `[low]` `[reject]` Blind: the named-top merge is kept whenever it is valid, though another turn might also be valid — a single face's wrong turn that still yields a valid cube is very rare, and guarding it adds a second search branch; the scan never guesses between valid states.
  - `[low]` `[reject]` Blind: when freeing grid turns gives AMBIGUOUS, the invalid named-top merge is kept — one freed face against five fixed corner faces almost never has two valid turns; today's re-prompt/review fallback then applies; handling it adds a branch.
  - `[low]` `[reject]` Blind: `trace()` re-classifies stickers just to read the branch — at most two extra assemblies once per grid capture on the analyzer thread; negligible.
  - `[low]` `[reject]` Blind: no test chains an earlier grid capture and a new replacement both needing rotation across the in-grid re-prompt — `gridFaces + replacements` is a plain set union read in the lambda; low and unlikely to break.
  - `[low]` `[patch]` Blind: projection test omits camera rotation 180 — added 180 (portrait frame, `CornerPoint(-right, -down)`).
  - `[low]` `[reject]` Edge: AMBIGUOUS freed search skips re-asking ambiguous faces — same root cause and reasoning as the Blind AMBIGUOUS row.
  - `[low]` `[patch]` Edge: 180° camera frames untested in the projection test — grouped with the Blind 180 row; same fix.
  - `[medium]` `[patch]` Verification gap: no route test proves the review/accept uses the oriented `QuickScanEffect.Done` samples (existing re-capture is a uniform face at rotation 0) — added a `QuickScanRouteTest` case: scrambled cube, U re-asked at rotation 90 held 180° off, accepted state equals the scramble.
  - `[low]` `[reject]` Intent: the frame guarantee holds only when the freed search is uniquely valid — by design (never guess); residual cases go to review as before.
  - `[false]` `[reject]` Intent: the correction path is untouched — once the face merges in the corner frame the editor shows the true face, so corrections no longer follow a rotated one; the editor itself had no defect.
  - `[maybe-false]` `[defer]` Intent: domain tests model the grid capture (rotation 90 = one counterclockwise turn) rather than the camera adapter — the model matches the Lenovo Guided log (`OYBWWRORO`, o=90 → `OWORWYORB`); a device session settles on-device behavior.
  - `[false]` `[reject]` Intent: evidence not replayed from the recorded log — the logs carry no per-sticker Lab values, so a synthetic replay of the true state is the only possible one.
  - `[low]` `[reject]` Intent: round-2 involved re-ask is covered only for U — round 1 covers every face × every held turn; building an invalid ≤2-face corner state per face adds test scaffolding for little value.
  - `[maybe-false]` `[defer]` Intent: Recognize's own last-layer sticker order is not tested end to end on a physical projection — no grid re-ask there, so it does not share this defect; its 11.3 deferred risk stays open until a device session or projection-based `lastLayerView` test.
  - `[false]` `[reject]` Intent: scope — production change is one domain file, no flags; nothing to fix.

## Design Notes

Root-cause evidence: a synthetic 3D projection shows corner reads are in the Kociemba frame at rotations 0/90/270, and a grid re-ask held with its named top already merges correctly; the device screenshots show the grid U capture taken while the cube was held as a corner, so the grid face's real top did not match the named one. Corner faces fix the frame, so one re-asked face has at most four candidate turns and validity picks it — the same rule Guided Scan already uses for unposed faces.

## Verification

**Commands:**
- `export JAVA_HOME=/usr/lib/jvm/java-25-openjdk-amd64 ANDROID_HOME=$HOME/Android/Sdk; ./gradlew :core:domain:test --tests '*QuickScanControllerTest' --tests '*ScanAssemblerTest' --tests '*GuidedScanTest' --tests '*CaseCaptureControllerTest'` -- expected: pass
- `./gradlew :app:testDebugUnitTest --tests '*CornerLocatorTest' --tests '*QuickScan*' --tests '*GuidedScan*' --tests '*CaseRecognition*'` -- expected: pass
- `./gradlew spotlessCheck build` -- expected: BUILD SUCCESSFUL

## Auto Run Result

Status: done

**Summary:** Quick Scan now merges a face re-asked in the Guided grid in the frame fixed by the corner reads. When the merge with the prompts' named tops is not exactly one valid state, the grid-captured faces drop their named top so `ScanAssembler` tries their four turns against the fixed corner faces; that merge is used only when it is exactly one valid state. The same oriented merge decides the in-grid re-prompt. Investigation: a physical 3D projection proves the corner reads (shared with Recognize) are in the Kociemba frame at camera rotations 0/90/180/270, and a re-ask held with its named top already merged correctly, so the #194 failure is a grid face whose real top differed from the named one; Recognize has no grid re-ask and needed no change.

**Files changed:**
- `core/domain/src/main/kotlin/dev/cubentia/domain/recognition/QuickScan.kt` -- tracks grid-captured faces; `oriented()` frees their turns when that uniquely resolves an invalid merge; used in `finish()` and the round-1 re-prompt.
- `core/domain/src/test/kotlin/dev/cubentia/domain/recognition/QuickScanControllerTest.kt` -- evidence cube: every face × held turns 0–3 at rotation 90 (U = `OWORWYORB`), and a round-two involved-U re-ask held 180° off.
- `app/src/testDebug/kotlin/dev/cubentia/app/camera/CornerLocatorTest.kt` -- orthographic 3D projection test of both corner holds at rotations 0/90/180/270.
- `app/src/testDebug/kotlin/dev/cubentia/app/QuickScanRouteTest.kt` -- route test: U re-asked at rotation 90 held 180° off is accepted as the true scrambled cube (fails without the fix).

**Review:** 15 findings. Patched: 1 medium (route-level test), 1 low group (180° rotation in projection test). Deferred: 2 (on-device confirmation; Recognize last-layer order end to end). Rejected: 12 — see the triage log for each reason.

**Follow-up review recommended:** false (patched: high 0, medium 1, low 1).

**Verification:** `./gradlew :core:domain:test` (QuickScanController 14/14, ScanAssembler, GuidedScan, CaseCapture) pass; `./gradlew :app:testDebugUnitTest` CornerLocatorTest 4/4, QuickScanRouteTest 4/4 pass; the new route test fails with the fix removed; `./gradlew spotlessCheck build` BUILD SUCCESSFUL.

**Residual risks:** A re-asked face whose freed turns give several valid states, or a corner face that is itself misread, still goes to review as before. The device screenshots suggest the Lenovo grid U capture was taken while the cube was held as a corner; a non-flat capture is a read problem this change cannot fix. Needs a device session (Lenovo + S24).
