---
title: 'Story 10.29: Solution Playback shows the same Quick Check result as Lessons'
type: 'feature'
created: '2026-11-24'
status: 'done'
baseline_revision: 'b4890271fbf41740557d16e2a5efd7b7b7359fda'
review_loop_iteration: 0
followup_review_recommended: false
context:
  - '{project-root}/AGENTS.md'
  - '{project-root}/_bmad-output/implementation-artifacts/epic-10-context.md'
  - '{project-root}/_bmad-output/implementation-artifacts/spec-10-7-quick-check-in-physical-cube-mode-and-playback.md'
warnings: ['oversized']
deferred:
  - summary: >-
      EXPERIENCE.md does not describe the shared Quick Check result card (PO to update the UX spec).
    evidence: |-
      Story 10.29 says "The EXPERIENCE.md update for the shared result card is made when this story is created." EXPERIENCE.md has no shared-card entry: only the Certainty chip row (line 96), Physical controls (line 95), the mismatch ladder (line 117) and Flow 3 step 3 ("A Quick Check is available at any step"); line 255 still says the playback Quick Check "arrive[s] in v1.1". Per the PO clarification this is recorded here instead of editing planning artifacts.
    location: >-
      _bmad-output/planning-artifacts/ux-designs/ux-RubikXplorer-2026-10-29/EXPERIENCE.md
    severity: medium
  - summary: >-
      An Unknown result that follows a mismatch still offers the mismatch actions built from the earlier observation (Use my cube adopt or re-solve, Scan all 6 faces, playback Undo).
    evidence: |-
      quickObserved keeps syncState and observed for an UNKNOWN check (Story 10.7 for an inconsistent Partial; now also an empty Partial per the PO decision), so offersFullScan, canAdoptObserved and canResolveObserved stay true and the card shows "I can't tell what changed." next to Use my cube, which adopts or re-solves from the earlier Full observation. Pre-existing for the inconsistent Partial path; the spec's action rules follow those flags. PO to decide whether an Unknown result should hide adopt/re-solve.
    location: >-
      app/src/main/kotlin/dev/cubentia/app/ui/lesson/LessonScreen.kt (Quick Check slot actions); app/src/main/kotlin/dev/cubentia/app/ui/solve/SolutionPlayback.kt (card actions)
    severity: low
  - summary: >-
      In a stage practice the Quick Check slot (and so the result card) hides once Story 10.7's suggestion gate closes, while the result stays in the session.
    evidence: |-
      A practice passes afterMismatch = false to quickCheckSuggested, so after a mismatch resolved by a VERIFIED Quick Check away from a boundary the slot is not offered; the stage header chip still shows the Certainty, but the card's frame text and message do not. Pre-existing Story 10.7 gate.
    location: >-
      app/src/main/kotlin/dev/cubentia/app/ui/lesson/LessonScreen.kt:647
    severity: low
  - summary: >-
      The refreshed epic-10-context.md may have dropped constraints later Epic 10 stories still need (scan-review "Discard this scan?", 20 dE likely-misread rule, side-face pre-fill, pastel no-global-loosening, capture cue, TalkBack order, 10.28 before new corner fixtures).
    evidence: |-
      Reviewers saw these bullets removed in the refresh after SCP #367. To settle: compare the bullets against the current planning artifacts (epics.md, EXPERIENCE.md, SCP 2026-11-24) and restore any rule that is still in force.
    location: >-
      _bmad-output/implementation-artifacts/epic-10-context.md
    severity: medium (unverified)
---

<intent-contract>

## Intent

**Problem:** A Lesson in Physical-cube Mode shows a Quick Check result across its stage header (frame label EXPECTED/VERIFIED, Certainty chip) and its step card (Likely copy and candidate, *Scan all 6 faces*, *Use my cube as it is now*, Diagnosis line and recovery prompt), while Solution Playback shows only a bare chip, the SEEN strip and loose text buttons beside the camera icon (#326). The PO found the difference confusing.

**Approach:** One shared Compose result component in `app/src/main/.../ui/lesson/` that both hosts render from `SessionState` (frame label, Certainty from `tutor/Assess`, copy from content strings, recovery actions as callbacks). The Lesson routes the actions through its existing step and recovery flow; Solution Playback routes them through its existing `SessionEvent` → `reduce()` transitions.

Refs: FR-26, FR-16, FR-9 · AD-3, AD-5, AD-20 · UX-DR12, UX-DR13, UX-DR20 · UX Flow 3 · Stories 10.6, 10.7, 14.3 · #326, #200

## Boundaries & Constraints

**Always:**
- AD-3: every state change is a `SessionEvent` through `reduce()`; no ViewModel or composable mutates state; async results echo effect id and revision.
- AD-5: Certainty comes only from `tutor/Assess` (`SessionState.quickCheck.certainty`); never a numeric confidence; a Partial verifies only a match.
- AD-20: Solution Playback keeps the shared `PlaybackController` and its `MoveNote` text; the result component adds no playback text of its own beyond content strings.
- The Certainty is announced to TalkBack once per result (#200): exactly one `certainty:chip` (a polite live region) on screen while the card shows.
- Learner text only from `core/domain/src/main/resources/content/strings/en.json` (`checkLearnerText`); fits at 200% font on a 600 dp width.
- Hidden without a `QuickCheckCapture` (`CAMERA_SCAN` OFF or unbound), as in Story 10.7.

**Never:**
- No feature-flag level change; no new dependency; no new `SessionEvent`; no debug-only acceptance path.
- No Guided recovery or `AdoptObserved` in Solution Playback (Story 10.7 boundary); no change to Lesson step/recovery routing (Undo / Guide me back / Try again stay in the step card's recovery prompt).
- Nothing drawn over the playback net (#200).
- Never weaken or delete an existing test (Story 12.8 rung tests, 10.7 tests); the one 10.7 empty-Partial assertion is updated as the PO decided.

## Settled UI behavior

- Animation: none (DiagnosisLine/CertaintyChip rule: no shake, no animation).
- Auto-play: none; nothing runs without a tap.
- Clearing: the card is a pure function of `SessionState`; it shows exactly while `quickCheck != null` and clears when the reducer clears it (an `expected` change, i.e. *Done*/Back/Undo, or leaving physical mode), identically on both hosts.
- Placement (PO 2026-11-25, Option A): Lesson — inside the existing Quick Check slot (step-card area, or ladder rung 0), under the Quick Check icon; the stage keeps its FrameLabel badge but hides its header `CertaintyChip` while the card shows. Playback — in the physical controls under the net, under the Quick Check icon, replacing the bare chip and loose buttons; SEEN strip and `PhysicalPlaybackControls` stay.
- Card frame label: plain text (no live region), `msg.physical.frame-verified` when `syncState == PHYSICAL_VERIFIED && quickCheck.certainty == VERIFIED`, else `msg.physical.frame-expected` (same rule as the stage FrameLabel).
- Message: UNKNOWN certainty -> "I can't tell what changed." (`msg.diagnosis.unknown-discrepancy`); else `offersFullScan` -> `msg.quick-check.likely-mismatch` + leading candidate (`msg.physical.mismatch.candidate`); else (a match, or a Full mismatch) no message line.
- Unreadable (PO, option b): an Assess UNKNOWN result and an empty Partial both count; the reducer records the empty Partial as `ObservationCheck(UNKNOWN, emptyList())` without changing `syncState` or `observed`.
- Actions, in order. Lesson: *Scan all 6 faces* (`offersFullScan`, `QuickCheckRequested(fullScan = true)`, tag `mismatch:scanAll`), *Use my cube as it is now* (`canAdoptObserved`, `AdoptObserved`, tag `mismatch:adopt`). Playback: *Undo* (`msg.recovery.offer.undo`, shown while `syncState == PHYSICAL_MISMATCH` and the check does not match, enabled as Back is, i.e. `canStepBack`; calls `stepBack()` → `SessionEvent.Undo`, tag `playback:undo`), *Scan all 6 faces* (`offersFullScan`, `requestQuickCheck(true)` → `QuickCheckRequested(fullScan = true)`, tag `playback:scanAll`), *Use my cube as it is now* (`canResolveObserved`, Full mismatch only, existing re-solve ending in `SolvePlaybackStarted`, tag `playback:useMyCube`; while `resolve.running` the existing `playback:resolving` indicator replaces it; `playback:resolveError` stays). No *Try again* in playback. Actions are outlined buttons, 48 dp min, in a wrapping FlowRow.

## I/O & Edge-Case Matrix

| Scenario | Input / State | Expected Output / Behavior | Error Handling |
|----------|--------------|---------------------------|----------------|
| Match | Full or covering Partial equal to expected | Card: VERIFIED frame text + ✓ chip, no message, no actions | — |
| Probable mismatch | consistent Partial, not expected | EXPECTED + ≈ chip, likely copy + candidate, Scan all (+ playback Undo) | — |
| Full mismatch | Full != expected | EXPECTED + chip, no message; Lesson adopt / playback Undo + Use my cube | re-solve error shown by host |
| Unreadable | inconsistent Partial, no candidate, or empty Partial | ? chip + "I can't tell what changed."; empty Partial keeps sync/observed | — |
| No result / no capture | `quickCheck == null` or capture null | no card; Lesson header chip rules unchanged | — |

</intent-contract>

## Code Map

- `app/src/main/kotlin/dev/cubentia/app/ui/lesson/LessonScreen.kt` -- Quick Check slot L747-802 (retry line, `QuickCheckButton`, Likely copy + candidate, `mismatch:scanAll`, `mismatch:adopt`) → replace the result part with the card; `LearnerStage` header `FrameLabel` + `CertaintyChip` L1270-1282 (add a hide-chip flag); `LearnerStage` call L895 (`camera = quickCheck != null`); `mismatchRung` L528; ladder shows the slot only at rung 0 (`MismatchLadder.kt`), panel only in non-mismatch, non-awaiting physical (`LessonPanel.kt` L243-254) → the "card shows" flag must mirror that.
- `app/src/main/kotlin/dev/cubentia/app/ui/lesson/CertaintyChip.kt` -- reuse as is (polite live region, tag `certainty:chip`).
- `app/src/main/kotlin/dev/cubentia/app/ui/solve/SolutionPlayback.kt` -- physical controls L478-529 (chip L481-484, retry, SEEN strip, `playback:quickCheck`, `playback:scanAll`, `playback:useMyCube` with `ResolveState`); `canStepBack` L270 / `stepBack` L273 dispatch `SessionEvent.Undo`; no FrameLabel today.
- `core/application/.../session/SessionState.kt` -- `quickCheck`, `offersFullScan` L181, `canResolveObserved` L189, `canAdoptObserved` L194.
- `core/application/.../session/Reduce.kt` -- `quickObserved` L1337 drops an empty Partial; clearing rule L58.
- `core/domain/.../tutor/Assess.kt` -- `check` L30 returns `ObservationCheck(UNKNOWN, emptyList())` for an empty Partial; `ObservationCheck` L383; `decide` certainty L272-279 reads `quickCheck`.
- `app/src/main/kotlin/dev/cubentia/app/MainActivity.kt` L241-255 -- debug diagnostics treat a new `quickCheck` instance as a run; an empty capture now logs as a run (debug only, no change needed).
- Tests: `core/application/src/test/.../session/QuickCheckSessionTest.kt` L258 (empty Partial), `app/src/test/.../ui/lesson/PhysicalLessonScreenTest.kt` (L822/836 chip, L1686-2060 Quick Check cases), `app/src/test/.../ui/solve/SolutionPlaybackTest.kt` L1242-1440 (setup pattern: `QuickCheckCapture {}`, `host.dispatch { QuickCheckRequested … }`, `QuickCheckObserved`; `@Config(qualifiers=…, fontScale = 2.0f)` L582), `AppShellSolveTest.kt`, `AppShellAlgorithmPracticeTest.kt`, `testDebug/.../QuickCheckRouteTest.kt` (use the existing tags).

## Tasks & Acceptance

**Execution:**
- `core/application/src/main/kotlin/dev/cubentia/application/session/Reduce.kt` -- in `quickObserved`, record an empty Partial as `quickCheck = ObservationCheck(Certainty.UNKNOWN, emptyList())` (via `Assess.check`, which already returns that) keeping `syncState`, `observed`, `touchedSinceVerified`, `physicalSolveConfirmed` unchanged -- PO decision (b).
- `core/application/src/test/kotlin/dev/cubentia/application/session/QuickCheckSessionTest.kt` -- update the empty-Partial test: `quickCheck` is the UNKNOWN check, observed/sync unchanged, pending cleared.
- `app/src/main/kotlin/dev/cubentia/app/ui/lesson/QuickCheckResultCard.kt` (new) -- `QuickCheckResultAction(label, tag, enabled, onClick)`; pure `quickCheckResultMessage(session, strings): String?` / frame-text helper per Settled UI behavior; `@Composable QuickCheckResultCard(session, strings, actions, modifier)` tag `quickCheck:result` with `quickCheck:frame`, `CertaintyChip(quickCheck.certainty)`, `quickCheck:message`, actions FlowRow; renders nothing when `session.quickCheck == null`.
- `app/src/main/kotlin/dev/cubentia/app/ui/lesson/LessonScreen.kt` -- slot: keep retry line + `QuickCheckButton`, then `QuickCheckResultCard` with the Lesson actions (same callbacks as today); compute `quickCheckCardShown` (slot offered && `quickCheck != null` && physical sync in PREDICTED/VERIFIED/MISMATCH && (not MISMATCH or `mismatchRung == 0`)) and pass it to `LearnerStage` to hide the header chip.
- `app/src/main/kotlin/dev/cubentia/app/ui/solve/SolutionPlayback.kt` -- replace the bare chip and the `playback:scanAll`/`playback:useMyCube` TextButtons with `QuickCheckResultCard` and the playback actions; keep retry line, SEEN strip, `playback:quickCheck`, resolving/error.
- `app/src/test/kotlin/dev/cubentia/app/ui/lesson/PhysicalLessonScreenTest.kt` -- add: match / probable mismatch / unreadable (inconsistent and empty Partial) at a normal step and at completion; assert card frame/message/actions, one `certainty:chip`, Scan all routes `QuickCheckRequested(fullScan=true)`, adopt unchanged; adjust any existing assertion that located the chip in the stage header while a result showed.
- `app/src/test/kotlin/dev/cubentia/app/ui/solve/SolutionPlaybackTest.kt` -- same three results at a normal step and at the end; Undo dispatches `SessionEvent.Undo` and clears the card; Scan all calls `requestQuickCheck(true)`; Use my cube calls `onUseMyCube` on a Full mismatch only; no Try again; one chip.
- `app/src/test/kotlin/dev/cubentia/app/ui/lesson/QuickCheckResultCardTest.kt` (new) -- each state at `w600dp` with `fontScale = 2.0f`: all text and actions displayed, buttons ≥ 48 dp, no clipping (`assertIsDisplayed` within a scroll).

**Acceptance Criteria:**
- Given a physical Quick Check result in a Lesson or Solution Playback, when it is shown, then both render `QuickCheckResultCard` (frame text, Assess Certainty chip, message per state, actions) and exactly one `certainty:chip` exists.
- Given the Lesson host, when *Scan all 6 faces* or *Use my cube as it is now* is tapped, then the same `QuickCheckRequested(fullScan = true)` / `AdoptObserved` as before is dispatched.
- Given the playback host, when *Undo*, *Scan all 6 faces* or *Use my cube as it is now* is tapped, then `SessionEvent.Undo`, `QuickCheckRequested(fullScan = true)` or the existing re-solve run; no composable mutates state.
- Given both hosts at a normal step and at completion, when a match, a probable mismatch and an unreadable result arrive, then the tests above pass and the card fits at 200% font on 600 dp.

## Spec Change Log

## Review Triage Log

### 2026-11-25 — Review pass
- verdicts: 39 findings — high 0, medium 2, low 23, false 10, maybe-false 4
- findings:
  - `false` `reject` (intent audit, first run) Lesson card visibility depends on UI state (ladder rung, offer gate), not only SessionState — the PO's placement (Option A) puts the card in the Quick Check slot, which only exists at rung 0 / when offered; the Clearing rule describes the result, the Placement rule where it renders.
  - `low` `defer` (intent audit, first run) Unreadable result after a mismatch still offers actions — grouped with the stale-actions deferral (frontmatter).
  - `low` `reject` (intent audit, first run) TalkBack once is checked only structurally (one polite chip) — speech is covered by the orchestrator's device gate; a JVM test cannot observe TalkBack.
  - `low` `reject` (intent audit, first run) 200% / 600 dp checked on the card alone — both hosts put the card in scrolling columns; the device gate checks hosts.
  - `low` `reject` (intent audit, first run) Resolving indicator renders under the card, not inside its action row — content and order match the spec; cosmetic.
  - `low` `reject` (intent audit, first run) Playback no-capture gate not newly asserted — code path unchanged (`if (quickCheck != null)`), existing 10.7 tests cover it.
  - `low` `reject` (intent audit) Expectations live on device, tests are Robolectric — device check is the human gate outside this run.
  - `false` `reject` (intent audit) epic-10-context.md rewrite is unrelated — the invocation hands this refresh to this run.
  - `false` `reject` (intent audit) Full mismatch shows EXPECTED with a Verified chip — Certainty comes only from Assess (AD-5); a Full observation is VERIFIED evidence that the cube differs; unchanged from 10.7.
  - `false` `reject` (intent audit) Undo gated on matchesExpected — `ObservationCheck.matchesExpected` is exactly "the check matches expected".
  - `low` `reject` (intent audit) quickCheckCardShown restates the slot's visibility rule — correct today and asserted by the chip-count tests; a single-source refactor adds structure for a hypothetical drift.
  - `false` `reject` (intent audit) Empty-Partial test gained assertions — only additions, nothing weakened.
  - `low` `defer` (blind) Empty Partial after a mismatch keeps stale adopt/re-solve/Undo — grouped stale-actions deferral; PO chose to keep sync and observed.
  - `false` `reject` (blind) VERIFIED frame drops to EXPECTED after an empty capture while sync stays VERIFIED — the frame rule shows the latest result's Certainty, as for an inconsistent Partial since 10.7; spec-settled.
  - `low` `patch` (blind) TRANSITIONS.md does not say the UNKNOWN result replaces the previous one — added that it replaces the previous result so a later mismatch decision reads UNKNOWN.
  - `low` `reject` (blind) quickCheckCardShown duplicates slot visibility — same as the intent-audit row above.
  - `maybe-false` `reject` (blind) Certainty may re-announce on card-to-header handoff / not re-announce identical results — needs TalkBack on device; if true only low; device gate covers it.
  - `low` `reject` (blind) Playback hard-codes "msg.recovery.offer.undo" instead of UNDO_LABEL — same key, checked by content validation; cosmetic duplication.
  - `low` `reject` (blind) Resolving/error states sit outside the card — spec-settled order; cosmetic.
  - `low` `reject` (blind) Missing tests (Undo disabled, non-degenerate inconsistent Partial, Lesson Full mismatch, leaving physical mode) — Lesson Full mismatch is asserted in the adopt test; clearing is reducer-tested since 10.7; the rest are rare; the unknown-after-mismatch gap was patched (see verification-gap row).
  - `low` `reject` (blind) Card test uses hand-made action sets — real sets are exercised by both host tests.
  - `false` `reject` (blind) epic-context rewrite does not belong in this diff — the invocation hands it to this run.
  - `maybe-false` `defer` (blind) epic-context rewrite dropped constraints still in force — recorded in frontmatter deferred (medium, unverified) with how to settle.
  - `low` `reject` (blind) epic-context says "colours" and "Enter colors" — cosmetic wording.
  - `false` `reject` (blind) EXPERIENCE.md update missing — already the spec's deferred PO item.
  - `low` `reject` (blind) Reduce builds the enumerator before the empty guard / checkNotNull may throw — a pending check implies tutorConfig is set (quickRequested requires it); keeping Assess.check as the only Certainty producer (AD-5).
  - `low` `reject` (blind) Odd line break in assertQuickCheckResult — formatter output; cosmetic.
  - `medium` `patch` (verification-gap) No UI test for an empty Partial arriving after a Partial mismatch — both host tests now observe it after the Likely step and assert Unknown chip, unknown copy, no candidate, and the remaining actions (Lesson scanAll; playback undo + scanAll).
  - `false` `reject` (edge) Empty Partial in MISMATCH should clear observed — the PO decision says the empty capture leaves sync state and observed unchanged.
  - `low` `defer` (edge) Lesson adopt offered with an UNKNOWN result — grouped stale-actions deferral.
  - `false` `reject` (edge) Scan all shown with Unknown copy and no candidate "disagree" — a full scan is the right next step for an unreadable partial; the candidate is spec'd only with the Likely copy.
  - `low` `defer` (edge) Playback Undo/Scan all/Use my cube shown with an Unknown result — grouped stale-actions deferral.
  - `low` `reject` (edge) Debug diagnostics now log empty captures as UNKNOWN runs — debug only, accepted in the spec's Code Map.
  - `low` `defer` (edge) Practice slot gate hides the card while a result exists — pre-existing Story 10.7 gate; frontmatter deferred.
  - `medium` `patch` (edge) Unreadable tested only from VERIFIED in playback — same root as the verification-gap row; patched there.
  - `maybe-false` `defer` (edge) epic-context dropped scan-review / ΔE / pre-fill rules — grouped epic-context deferral.
  - `maybe-false` `defer` (edge) epic-context dropped pastel no-global-loosening rule — grouped epic-context deferral.
  - `low` `defer` (edge) Use my cube offered while latest result is Unknown — grouped stale-actions deferral.
  - `low` `defer` (edge) Practice results sometimes never get the card — grouped with the practice-gate deferral.

## Design Notes

The PO answered the three open questions on 2026-11-25 (all recommended options); they are folded into Settled UI behavior above, replacing the earlier "Open questions (blocking)" section. The Lesson's Diagnosis-driven recovery prompt (Undo / Guide me back / Try again) stays in the step card, unchanged; the card's Lesson actions are the Quick Check result actions it already had. Playback has no Diagnosis, so the card carries its equivalents directly.

## Verification

**Commands:**
- `cd /mnt/e/Dynamic/IndFun/RubikXplorer-10-29 && export JAVA_HOME=/usr/lib/jvm/java-25-openjdk-amd64 ANDROID_HOME=$HOME/Android/Sdk && ./gradlew spotlessApply && ./gradlew spotlessCheck build` -- expected: BUILD SUCCESSFUL (tests, lint, Kover, content checks).

## Auto Run Result

Previous run: blocked on three open questions; answered by the PO 2026-11-25 and resumed.

Status: done

**Summary:** A new shared `QuickCheckResultCard` (frame text, Assess Certainty chip, message, host actions) replaces the Lesson's inline Quick Check result block and Solution Playback's bare chip and loose buttons. The Lesson stage hides its header chip while the card shows (one chip on screen). An empty Partial capture is now recorded as an UNKNOWN check without changing sync state or observed, so both hosts show "I can't tell what changed.".

**Files changed:**
- `app/src/main/kotlin/dev/cubentia/app/ui/lesson/QuickCheckResultCard.kt` -- new shared card, action type, frame and message helpers.
- `app/src/main/kotlin/dev/cubentia/app/ui/lesson/LessonScreen.kt` -- slot renders the card with Scan all / adopt; header chip hidden while the card shows.
- `app/src/main/kotlin/dev/cubentia/app/ui/solve/SolutionPlayback.kt` -- card with Undo (SessionEvent.Undo via stepBack), Scan all, Use my cube re-solve.
- `core/application/src/main/kotlin/dev/cubentia/application/session/Reduce.kt` -- empty Partial recorded as UNKNOWN check.
- `core/application/src/main/kotlin/dev/cubentia/application/session/TRANSITIONS.md` -- documents the change.
- Tests: `QuickCheckResultCardTest.kt` (new, 600 dp, 200% font), `PhysicalLessonScreenTest.kt`, `SolutionPlaybackTest.kt` (match / probable mismatch / unreadable at a normal step and completion, actions), `QuickCheckSessionTest.kt` (10.7 assertion updated per PO).
- `_bmad-output/implementation-artifacts/epic-10-context.md` -- refreshed epic context (carried from the previous run).

**Review findings:** 39 findings (medium 2, low 23, false 10, maybe-false 4). Patches applied: unknown-after-mismatch UI tests on both hosts (medium), TRANSITIONS.md wording (low). Deferred: stale mismatch actions next to an Unknown result (low, PO question), practice suggestion gate hides the card (low, pre-existing), epic-context dropped constraints (medium, unverified). Rejected findings and reasons are in the Review Triage Log.

**Follow-up review recommendation:** false (patched: 1 medium, 1 low; no high).

**Verification:** `./gradlew spotlessApply && ./gradlew spotlessCheck build` -- BUILD SUCCESSFUL (after patches). New tests confirmed in JUnit XML reports.

**Residual risks:** TalkBack announcement count and host layouts at 200% are checked only in Robolectric; the Lenovo/Galaxy device check is the orchestrator's human gate. Implementation ran on Claude after Codex hit its usage limit mid-run.
