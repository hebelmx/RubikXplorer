---
title: 'Story 10.7: Quick Check in Physical-cube Mode and playback'
type: 'feature'
created: '2026-11-17'
status: 'done'
baseline_revision: '90dbabbd58686039904032a6d28cded59ddbee54'
review_loop_iteration: 0
followup_review_recommended: true
context:
  - '{project-root}/AGENTS.md'
  - '{project-root}/_bmad-output/implementation-artifacts/epic-10-context.md'
  - '{project-root}/_bmad-output/implementation-artifacts/spec-10-6-quick-check-evidence-and-certainty.md'
warnings: [oversized]
deferred:
  - summary: >-
      A LIKELY Quick Check match (several candidates, the empty sequence among them) offers no Guided 6-face Scan.
    evidence: |-
      FR-26 consequence 3: "When a Quick Check shows a mismatch, or several candidates fit, the tutor reports likely and offers a Guided 6-face Scan." SessionState.offersFullScan requires PHYSICAL_MISMATCH and observed is Partial; a LIKELY match leaves Sync State and observed unchanged, so no offer shows. Story 10.7's ACs do not require it.
    location: >-
      core/application/src/main/kotlin/dev/cubentia/application/session/SessionState.kt:181
    severity: medium
---

<intent-contract>

## Intent

**Problem:** Story 10.6 built Quick Check evidence (`Assess.check`, `QuickCheckRequested/Observed/Cancelled`) but nothing in the app offers, captures or shows it: no Quick Check entry, no Verified frame, no SEEN strip, no adopt, no Quick Check in Solution Playback. Three 10.6 gate findings also sit under this UI: an empty `Partial` moves to `PHYSICAL_MISMATCH`, `enterEditor` keeps `pendingQuickCheck`, and `SessionState.decision` ignores `quickCheck` (Certainty reads UNKNOWN in mismatch).

**Approach:** Fix the three reducer/decision findings, add `AdoptObserved`, allow Quick Check in physical Solution Playback, then wire the main app: a `QuickCheckCapture` port started from `MainActivity` (a face-subset mode of `GuidedScanActivity`, or the Guided 6-face Scan), offered at Objective boundaries, as rung 0 of the mismatch ladder, as *Scan it* on the Confirm-your-cube card and at any playback step; all hidden when `CAMERA_SCAN` is OFF or unbound.

## Boundaries & Constraints

**Always:**
- Only `tutor/Assess.kt` names a Certainty value. A Partial verifies only a match; a Partial mismatch is LIKELY and offers *Scan all 6 faces*; only a Full observation verifies a mismatch (AD-5 as amended, SCP 2026-11-17).
- A Partial is never completed from `expected`, never drawn as a whole cube, never adopted. `AdoptObserved` needs `observed is Full` in `PHYSICAL_MISMATCH`.
- Learner text only in `core/domain/src/main/resources/content/strings/en.json`. New keys (exact copy): `btn.physical.quick-check` "Quick Check", `btn.physical.confirm-scan` "Scan it", `btn.physical.scan-all-faces` "Scan all 6 faces", `btn.physical.use-my-cube` "Use my cube as it is now", `msg.quick-check.likely-mismatch` "That turn probably moved the cross.", `msg.physical.mismatch.quick-check` "Check the faces you turned with the camera.", `msg.physical.frame-verified` "VERIFIED", `msg.quick-check.seen` "SEEN".
- Every Quick Check entry, chip state, SEEN strip and rung appears only when a `QuickCheckCapture` is passed, which `MainActivity` does only when `CAMERA_SCAN` is enabled (through `ScanPortGate`) and a `ScanLauncher` is bound. With none, every existing physical-mode screen and test is unchanged.
- Camera code stays in `app/src/camera/`; `:app` main reaches it only via `ScanLauncher`. Async results echo the effect's id and revision (AD-3).

**Never:**
- No feature-flag level change (`QUICK_SCAN` stays OFF; it is Story 11.1). No new dependency, no numeric confidence, no debug-only launcher as the acceptance path.
- No change to the Story 12.8 rung tests (rungs 1–3 keep their tags and behavior), Story 8.7 / 12.10 tests, `CertaintyProducerTest`, or the 10.6 `Assess.check` rule. Never weaken or delete a test.
- No Solve-from-scan flow (Story 10.15), no adopt or Guided recovery in Solution Playback.

## I/O & Edge-Case Matrix

| Scenario | Input / State | Expected Output / Behavior | Error Handling |
|----------|--------------|---------------------------|----------------|
| Empty Partial (fix a) | pending check, `QuickCheckObserved(Partial(emptyMap()))` | pending cleared; Sync State, `observed`, `quickCheck` unchanged | no evidence |
| Editor after scan start (fix b) | pending Learner check, then `EditorEntered`, then the old result | `enterEditor` clears `pendingQuickCheck`; old result → `lastDiscarded`, `quickCheckRetry` stays null | — |
| Decision in mismatch (fix c) | `PHYSICAL_MISMATCH`, `quickCheck` LIKELY Partial `[R2]` / VERIFIED Full | `decision.certainty` LIKELY / VERIFIED; `offersFullScan` true for the Partial, `canAdoptObserved` true for the Full | with no `quickCheck`: UNKNOWN as today |
| Full scan request | `QuickCheckRequested(fullScan = true)` | effect faces = all six | — |
| Adopt | `PHYSICAL_MISMATCH`, `observed` Full S, Lesson | `expected = virtual = S`, history `MoveHistory(S)` (undo barrier), `observed` Full S, `touchedSinceVerified` = {}, `PHYSICAL_PREDICTED`, Diagnosis as `enterEditor`'s against the previous Checkpoint (kept); if `canGuideBack` then `Effect.PlanRecovery` issued (`guidedRecovery = Planning`) | Partial / no observation / not mismatch / playback → unchanged |
| Verified solved at Objective | Lesson, `objectiveMet`, unconfirmed, VERIFIED match | also `physicalSolveConfirmed = true` (SessionHost records completion; Stage 6 shows the first-solve milestone) | LIKELY/UNKNOWN → not confirmed |
| Playback Quick Check | `SOLVE_PLAYBACK` physical, started with a `tutorConfig` | Requested/Observed/Cancelled apply as in a Lesson (VERIFIED match → `PHYSICAL_VERIFIED`, mismatch → `PHYSICAL_MISMATCH`) | no `tutorConfig` → Requested ignored |
| Playback Done in mismatch | `SOLVE_PLAYBACK`, `PHYSICAL_MISMATCH`, Done | Move committed on top (never replaces the last step), `PHYSICAL_PREDICTED` | — |
| Partial capture | faces {R, U}, saved calibration | `GuidedScanActivity` prompts each face with its named top (`GuidedScanPlan.reprompt`), assembles, keeps only faces whose stickers are all CONFIDENT, returns that Partial (no review) | none kept → empty Partial (no evidence) |
| No saved calibration / six faces | any Quick Check | the Guided 6-face Scan with review; Accepted → `Full(state)` | Cancelled / FellBackToEditor → `QuickCheckCancelled` |

</intent-contract>

## Code Map

- `core/application/.../session/Reduce.kt` -- `reduce` clearing block (L38-56: add `AdoptObserved` to `preservesCoverage`); `applied` (L129-152: stale guard needs `kind != null`, `playbackAllowed` must admit the three Quick Check events in SOLVE_PLAYBACK; `keeps` must include `AdoptObserved`); `transition` `when` (L176-310); `commitPhysical` (L683 `replacing`: only when `kind.takesMoves`); `canConfirmPhysicalSolve` (L742); `enterEditor` (L776, fix b); `guideBack` (L906) reusable for adopt; `startPlayback` (L401, store `tutorConfig`); `quickAllowed`/`quickRequested`/`quickObserved` (L1250-1304, fix a at the `consistent` computation).
- `core/application/.../session/SessionState.kt` -- `decision` (L187, fix c: pass `quickCheck`); new fields go last; add derived `offersFullScan`, `canAdoptObserved`.
- `core/application/.../session/{SessionEvent,Effect,TRANSITIONS.md}` -- `QuickCheckRequested(byLearner, at)` (add `fullScan: Boolean = false` last), `SolvePlaybackStarted` (add `tutorConfig: TutorConfig? = null` last), new `AdoptObserved(at)`. TRANSITIONS.md: one row per event and the Quick Check paragraph.
- `core/domain/.../tutor/Assess.kt` -- `decide` (L241, certainty `when` L268): add `quickCheck: ObservationCheck? = null` last; `ObservationCheck` (L381).
- `core/application/src/test/.../session/{QuickCheckSessionTest,TransitionsTableTest,PhysicalModeSessionTest,SolvePlaybackSessionTest,GuidedRecoverySessionTest}.kt` -- fixtures and style; `assertEquals(34, eventTypes.size)` at TransitionsTableTest L475.
- `app/src/main/kotlin/dev/cubentia/app/MainActivity.kt` -- registers `ScanFlowContract` for Solve; same pattern for the Quick Check contract; `sessionHost` injected.
- `app/src/main/kotlin/dev/cubentia/app/scan/{ScanLauncher,ScanFlowContract,ScanOutcome}.kt` -- port default-method pattern; encode/decode via `CubeValidator`.
- `app/src/camera/kotlin/dev/cubentia/app/camera/{GuidedScanActivity,GuidedScanFlow,CameraScanLauncher}.kt` -- `CALIBRATION_MODE` extra pattern; `GuidedScanViewModel.newController()`; `handle` → `GuidedScanFlow.reviewOf` → `publish`; `finishWith`. `GuidedScanController(plan, reprompt)` accepts any plan.
- `core/domain/.../recognition/{GuidedScan,ScanAssembler,SelfCalibration}.kt` -- `GuidedScanPlan.reprompt(face)` (named top, so a <6 face sample is posed); `ScanAssembler.assemble(samples, calibration)` returns `ScanResult.Partial` with `confidence`; fewer than six centers need a saved calibration.
- `app/src/main/kotlin/dev/cubentia/app/ui/shell/AppShell.kt` -- `onScan` threading (L123/166/650); `LessonScreen(` calls (L314/379/432); `SolvePlaybackScreen(` (L261); `SolvePlaybackStarted` dispatch (L651, pass `ready.bundle.tutorConfig`).
- `app/src/main/kotlin/dev/cubentia/app/ui/lesson/LessonScreen.kt` -- `cameraCheck` (L159, drives `physicalObjectiveMetCopy` L912); `LessonBody` `mismatchRung` (L528) and its reset effect (L536); `LessonPanel` wiring (L684-712); editor overlay (L860); `LearnerStage` header chip (L1133-1140); `FrameLabel` (L1415); `GuidedRecoveryRunner`.
- `app/src/main/kotlin/dev/cubentia/app/ui/lesson/{LessonPanel,MismatchLadder,ConfirmCubeCard,CertaintyChip}.kt` -- physical controls row (LessonPanel L248-355), ladder rungs 1–3, Confirm card buttons.
- `app/src/main/kotlin/dev/cubentia/app/ui/solve/SolutionPlayback.kt` -- `SolutionPlaybackState`, `PhysicalPlaybackControls` (L321), `FrameLabel` use (L291).
- Tests to mirror: `app/src/test/.../ui/lesson/{PhysicalLessonScreenTest,FrameLabelTest}.kt`, `app/src/test/.../ui/shell/AppShellCameraScanTest.kt`, `app/src/testDebug/kotlin/dev/cubentia/app/ScanRouteTest.kt` (Hilt + Robolectric + `FakeFrames` route through the real `MainActivity`).

## Tasks & Acceptance

**Execution:**
- `core/domain/.../tutor/Assess.kt` -- `decide(..., quickCheck: ObservationCheck? = null)`: when physical, in mismatch, and `quickCheck` is non-null and not `matchesExpected`, certainty is `quickCheck.certainty` (checked before the UNKNOWN branch). Test in `AssessTest`.
- `core/application/.../session/SessionState.kt` -- pass `quickCheck` to `decide`; add `offersFullScan` (`PHYSICAL_MISMATCH`, `observed is Partial`, `quickCheck?.matchesExpected == false`) and `canAdoptObserved` (`PHYSICAL_MISMATCH`, `observed is Full`, `kind.takesMoves`), with KDoc.
- `core/application/.../session/{SessionEvent,Effect}.kt` -- `QuickCheckRequested.fullScan`, `SolvePlaybackStarted.tutorConfig`, `AdoptObserved(at)`.
- `core/application/.../session/Reduce.kt` -- fixes a/b; `fullScan` → all six faces; Quick Check allowed in physical SOLVE_PLAYBACK with a `tutorConfig`; `commitPhysical` replaces only in a Moves-taking session; `AdoptObserved` per the matrix (kept event, coverage preserved; plan via `guideBack` on the adopted state); a VERIFIED match sets `physicalSolveConfirmed` when `canConfirmPhysicalSolve`.
- `core/application/.../session/TRANSITIONS.md` + `TransitionsTableTest.kt` -- `AdoptObserved` row, samples, scenarios; count 35; paragraph covering fixes, adopt, playback, auto-confirm.
- `core/application/src/test/.../session/QuickCheckSessionTest.kt` (+ `SolvePlaybackSessionTest.kt`) -- one test per matrix row above (fixes a, b, c each separately).
- `core/domain/src/main/resources/content/strings/en.json` -- the keys above.
- `app/src/main/.../scan/QuickCheckCapture.kt` (new) -- `fun interface QuickCheckCapture { fun start(effect: Effect.QuickCheck) }`; `QuickCheckOutcome { Observed(PhysicalObservation), Cancelled }`; `QuickCheckContract(launcher)` with input the face set and a `fullScan` flag: six faces or no saved calibration → `guidedScanIntent` (decoded as `ScanFlowContract` does; Accepted → `Observed(Full)`, else Cancelled); otherwise `launcher.quickCheckIntent(context, faces)` returning faces + 9 colors each (decode validates names and sizes; anything malformed → Cancelled). Unit-test encode/decode.
- `app/src/main/.../scan/ScanLauncher.kt` -- `fun quickCheckIntent(context: Context, faces: Set<Face>): Intent = guidedScanIntent(context)`; `CameraScanLauncher` adds a `QUICK_CHECK_FACES` extra.
- `app/src/camera/.../{GuidedScanActivity,GuidedScanFlow}.kt` -- quick-check mode: plan = the faces in URFDLB order mapped by `GuidedScanPlan.reprompt`, no re-prompt round; on `Done`, pure `GuidedScanFlow.quickCheckOf(samples, saved)` (assemble with the saved calibration, keep all-CONFIDENT faces) → finish with the encoded Partial, no review. Unit-test `quickCheckOf` in `GuidedScanFlowTest`.
- `app/src/main/kotlin/dev/cubentia/app/MainActivity.kt` -- when `CAMERA_SCAN` is enabled and the launcher present: inject `CalibrationRepository`, register `QuickCheckContract`, build a `QuickCheckCapture` that launches it (a second start while one is in flight does nothing); on the result read `sessionHost.state.value.pendingQuickCheck` and dispatch `QuickCheckObserved(pending.effectId, pending.revision, obs)` or `QuickCheckCancelled`. Pass it to `AppShell`.
- `app/src/main/.../ui/shell/AppShell.kt` -- thread `quickCheck: QuickCheckCapture? = null` to the three `LessonScreen` calls and `SolvePlaybackScreen`; pass `ready.bundle.tutorConfig` in `SolvePlaybackStarted`.
- `app/src/main/.../ui/lesson/LessonScreen.kt`, `LessonPanel.kt`, `MismatchLadder.kt`, `ConfirmCubeCard.kt`, new `SeenStrip.kt` -- with a capture: `cameraCheck` = capture present; a request helper dispatches `QuickCheckRequested(fullScan)` and starts the returned effect. Quick Check icon button (`lesson:quickCheck`, label `btn.physical.quick-check`) in the physical row when `decision.advance || session.objectiveMet`; `quickCheckRetry` line (`quickCheck:retry`) plus the button. Mismatch ladder: an optional rung 0 (`mismatch:quickCheck` rung, initial rung 0 only with a capture) showing the prompt and button, then the result: `msg.quick-check.likely-mismatch` with the leading candidate (`msg.physical.mismatch.candidate`) and `mismatch:scanAll` when `offersFullScan`; `mismatch:adopt` dispatching `AdoptObserved` when `canAdoptObserved`; *Next question* goes to rung 1. Confirm card: `confirmCube:scan` ("Scan it") runs a full scan whose Full result is dispatched as `EditorEntered(state)`. `FrameLabel(verified)` shows `msg.physical.frame-verified` with the status-verified icon when `PHYSICAL_VERIFIED` and `quickCheck?.certainty == VERIFIED`. `SeenStrip` (`stage:seen`, one 3×3 thumbnail per observed face tagged `seen:face:<F>`, differing stickers vs `expected.reoriented(HOME)` outlined in status-likely/verified and marked by semantics) under the stage in `PHYSICAL_MISMATCH` with an observation.
- `app/src/main/.../ui/solve/{SolutionPlayback,SolvePlaybackScreen}.kt` -- with a capture: `playback:quickCheck` at every physical step, chip from `session.quickCheck`, VERIFIED frame, `SeenStrip` in mismatch, `playback:scanAll` when `offersFullScan`.
- App tests -- `PhysicalLessonScreenTest`: rung 0 only with a capture, rungs 1–3 unchanged without; chip/copy/scan-all for LIKELY Partial, adopt for Full, VERIFIED frame then EXPECTED after *Done*; *Scan it*; no Quick Check node without a capture. `AppShellCameraScanTest`: with a capture, the Stage 6 camera copy and Quick Check button show. A playback screen test. `app/src/testDebug/.../QuickCheckRouteTest.kt` (new, like `ScanRouteTest`): real `MainActivity`, physical Lesson, *My cube doesn't match* → rung 0 *Quick Check* → started intent is `GuidedScanActivity` with the planned faces → fake frames (saved calibration in the repository) → result back → ≈ *Likely* chip, `msg.quick-check.likely-mismatch`, *Scan all 6 faces*.

**Acceptance Criteria:**
- Given physical mode with a capture, when an Objective boundary, the Stage 6 end or a mismatch report occurs, then *Quick Check* is offered, *Done*/*Next* still work, and on the ladder it is rung 0 above unchanged rungs 1–3.
- Given a matching result, when shown, then ✓ *Verified* chip and VERIFIED frame show until the next *Done*; a mismatch shows the SEEN strip of observed faces only, with differing stickers outlined.
- Given a Full observation in mismatch, when *Use my cube as it is now* is chosen, then `AdoptObserved` replaces Expected and starts a Guided recovery when one applies; a Partial offers only *Scan all 6 faces* (or the Editor).
- Given physical Solution Playback or the Stage 6 end, when a Quick Check runs, then it is available at any step, a verified solved cube at Stage 6 shows the first-solve milestone and records completion, *Scan it* is on the Confirm card, and the camera first-solve copy shows only with the Quick Check action.
- Given `CAMERA_SCAN` OFF or no binding, when any physical screen renders, then no Quick Check entry, VERIFIED frame, SEEN strip or rung 0 exists, and existing 8.7/12.8/12.10 tests pass unchanged.
- Given `./gradlew spotlessCheck build` and `:app:testDebugUnitTest`, then both pass, including `QuickCheckRouteTest`, `TransitionsTableTest`, `CertaintyProducerTest`, content tests and Kover ≥ 80%.
- Human gate (not automatable): a device session on the main debug build where the PO confirms the Likely copy and *Scan all 6 faces* label.

## Spec Change Log

## Review Triage Log

### 2026-11-17 — Review pass
- verdicts: 19 findings — high 0, medium 3, low 10, false 6, maybe-false 0
- findings:
  - `[low]` `[reject]` Blind: `QuickCheckContract.decode` turns `FellBackToEditor` into Cancelled, so *Enter colors instead* in the scan's denied card opens no Editor — the spec matrix chose Cancelled; the Editor stays one tap away (ladder rung 3, Confirm card *Enter it*), camera denial is rare, and routing it adds a new outcome path.
  - `[low]` `[reject]` Blind: an all-discarded capture returns an empty Partial with no message — no evidence is the intended outcome (fix a); the Quick Check button stays visible to retry; a message adds UI and copy for a rare case.
  - `[medium]` `[defer]` Blind: a LIKELY match (several candidates incl. `[]`) offers no Guided 6-face Scan, though FR-26 says "or several candidates fit … offers a Guided 6-face Scan" — real, but Story 10.7's ACs (the intent: "exactly this story's acceptance criteria") only require the offer on a Partial mismatch; recorded in `deferred`.
  - `[false]` `[reject]` Blind: playback cannot trigger the first-solve milestone — the first-solve milestone exists only for Stage 6's last Lesson (`isCourseFinale`, Story 12.7); Solve playback has no Course milestone, and the AC's "Stage 6 end" clause carries it.
  - `[false]` `[reject]` Blind: playback lacks *Scan it* — `switchMode` puts SOLVE_PLAYBACK straight into `PHYSICAL_PREDICTED` (Reduce.kt `switchMode`), so playback never shows the Confirm card.
  - `[low]` `[patch]` Blind: SEEN strip's six 54dp thumbnails overflow narrow stages — `SeenStrip` now uses a wrapping `FlowRow`.
  - `[low]` `[patch]` Blind: differing SEEN stickers are not announced — they now carry `stateDescription` "Differs from the expected cube" (new key `msg.quick-check.seen-differs`); asserted in `PhysicalLessonScreenTest`.
  - `[low]` `[reject]` Blind: "That turn probably moved the cross." shows for every Partial mismatch — the intent mandates this exact copy pending PO confirmation at the device session.
  - `[false]` `[reject]` Blind: a failing `result.launch` strands `pendingQuickCheck` — `MainActivity` rethrows, so the app crashes rather than continuing stranded, the same pattern as the Solve-tab scan launch.
  - `[low]` `[reject]` Blind: *Scan all 6 faces* / *Use my cube* vanish after *Next question* — the Learner chose the next rung; rung 3 still offers re-entry, and keeping rung-0 actions on every rung changes the 12.8 rungs the AC protects.
  - `[low]` `[reject]` Edge: recreation in the milliseconds between request and launch (calibration load) leaves `quickInFlight` restored with nothing launched — very unlikely; fixing needs a persisted launch phase.
  - `[low]` `[patch]` Edge: SEEN strip overflow — same root cause as the Blind row; fixed by `FlowRow`.
  - `[medium]` `[patch]` Verification gap: *Scan it* → `EditorEntered` branch in `MainActivity` untested — added `QuickCheckRouteTest.confirmCardScanItEntersTheScannedCube` (real `MainActivity`, result returned, Expected/virtual = scanned cube, `PHYSICAL_VERIFIED`).
  - `[medium]` `[patch]` Verification gap: in-flight Quick Check restoration across `MainActivity` recreation untested — added `QuickCheckRouteTest.quickCheckResultReturningAfterRecreationStillApplies` (recreate, deliver result, pending cleared, evidence applied).
  - `[false]` `[reject]` Intent: the intent is judged end-to-end (R5b) but most tests inject observations — `QuickCheckRouteTest` now drives three real-`MainActivity` routes (Partial capture through `GuidedScanActivity`, *Scan it*, recreation); screen tests cover the remaining UI states, which the reducer reaches the same way.
  - `[low]` `[patch]` Intent: no test asserts the SEEN outline on differing stickers — `PhysicalLessonScreenTest` now asserts `SeenDifferenceKey` true/false nodes.
  - `[false]` `[reject]` Intent: the 12.8/8.7 test harness injects a capture when `cameraCheck = true` — no test body changed; `cameraCheck` was the stand-in for "a camera check action exists", which is now the capture itself; LessonScreen KDoc updated to say `cameraCheck` no longer selects copy.
  - `[false]` `[reject]` Intent: playback verified-solved milestone untested — same refutation as the Blind playback-milestone row.
  - `[low]` `[reject]` Intent: the `MainActivity` flag-OFF branch has no test — it mirrors the tested Solve `onScan` condition and `ScanPortGate` (tested) already reads every camera flag OFF without a binding; composable tests cover "no capture → no nodes".

## Design Notes

**Why *Scan it* dispatches `EditorEntered`:** the spine routes `PhysicalAwaitingConfirm → PhysicalPredicted` on a Full scan or the Editor; no `ScanAccepted` event exists, and `EditorEntered(state)` already is the Full-observation entry (Expected := state, VERIFIED, coverage reset). A Quick Check there would judge candidates from a predicted cube the Learner has not confirmed.

**Why fewer than six faces need saved calibration:** `SelfCalibration` needs all six centers; with only two captured faces the other colors cannot be classified honestly, so without a saved calibration the check runs the Guided 6-face Scan, which yields a Full observation.

**Why only CONFIDENT faces enter a Partial:** a misread sticker is false evidence; dropping the face only lowers coverage, so the result can drop to LIKELY but never become a false VERIFIED.

## Verification

**Commands:**
- `export JAVA_HOME=/usr/lib/jvm/java-25-openjdk-amd64 ANDROID_HOME=$HOME/Android/Sdk && ./gradlew spotlessApply` -- expected: formatting applied
- `./gradlew :core:domain:test :core:application:test` -- expected: BUILD SUCCESSFUL
- `./gradlew :app:testDebugUnitTest` -- expected: BUILD SUCCESSFUL including `QuickCheckRouteTest`
- `./gradlew spotlessCheck build` -- expected: BUILD SUCCESSFUL

## Auto Run Result

Status: done

**Summary:** Story 10.7 wires Quick Check into the main app. A `QuickCheckCapture` port started from `MainActivity` (only with `CAMERA_SCAN` enabled and a `ScanLauncher` bound) runs a face-subset mode of `GuidedScanActivity` (named-top prompts, saved calibration, only all-CONFIDENT faces kept, no review) or the Guided 6-face Scan. Quick Check is offered at Objective boundaries and the Stage 6 end, as rung 0 of the mismatch ladder, as *Scan it* on the Confirm-your-cube card (Full result → `EditorEntered`) and at every physical Solution Playback step. Results show the ✓ Verified chip and VERIFIED frame until the next *Done*, the SEEN strip of observed faces with differing stickers outlined, the Likely copy "That turn probably moved the cross." with *Scan all 6 faces* for a Partial mismatch, and *Use my cube as it is now* (`AdoptObserved`) for a Full mismatch. Story 10.6 gate fixes: an empty Partial is no evidence; `enterEditor` clears `pendingQuickCheck`; `decision.certainty` uses `quickCheck` in mismatch, with `offersFullScan`/`canAdoptObserved` surfacing the offers. A VERIFIED match at an unconfirmed physical Objective confirms the solve (Stage 6 first-solve milestone, recorded by `SessionHost`).

**Files changed:**
- `core/domain/.../tutor/Assess.kt` -- `decide(quickCheck)` certainty in mismatch.
- `core/domain/src/main/resources/content/strings/en.json` -- 9 new Learner keys (incl. `msg.quick-check.seen-differs`).
- `core/application/.../session/{Reduce,SessionEvent,SessionState}.kt`, `TRANSITIONS.md` -- fixes a/b/c, `AdoptObserved`, `QuickCheckRequested.fullScan`, `SolvePlaybackStarted.tutorConfig`, playback Quick Check, playback Done-in-mismatch commits on top, auto-confirm on verified match.
- `app/src/main/.../scan/{QuickCheckCapture,ScanLauncher}.kt` -- port, outcome, contract (encode/decode), `quickCheckIntent`.
- `app/src/main/.../MainActivity.kt` -- registers the contract, tracks the in-flight effect across recreation, dispatches results.
- `app/src/main/.../ui/lesson/{LessonScreen,LessonPanel,MismatchLadder,ConfirmCubeCard,SeenStrip}.kt`, `ui/shell/AppShell.kt`, `ui/solve/{SolutionPlayback,SolvePlaybackScreen}.kt`, `res/drawable/ic_camera.xml` -- entries, rung 0, chip/frame, SEEN strip, playback controls.
- `app/src/camera/.../{GuidedScanActivity,GuidedScanFlow,CameraScanLauncher}.kt` -- quick-check capture mode and `quickCheckOf`.
- Tests: `QuickCheckSessionTest`, `TransitionsTableTest`, `GuidedRecoverySessionTest`, `AssessTest`, `QuickCheckContractTest`, `PhysicalLessonScreenTest`, `AppShellCameraScanTest`, `SolutionPlaybackTest`, `GuidedScanFlowTest`, `QuickCheckRouteTest` (3 real-`MainActivity` routes), `Cube3DEmphasisTest` (idle waits for a timing race; assertions unchanged).

**Review:** 19 findings. Patched 5 entries (medium 2, low 3): SEEN strip wraps (`FlowRow`); differing stickers announced; *Scan it* and recreation route tests; SEEN outline assertion. Deferred 1 (medium): FR-26's 6-face offer on a LIKELY match. Rejected 13 (6 false, 7 low), reasons in the Review Triage Log.

**Follow-up review recommended:** true -- two medium entries were patched on this first pass (patched: high 0, medium 2, low 3). Unverified risk: `MainActivity`'s in-flight capture bookkeeping (`quickInFlight`, the `-1` effect id for *Scan it*, restore after recreation) now has route tests but has not been re-reviewed.

**Verification:** `./gradlew spotlessApply`; `./gradlew :core:application:test --tests QuickCheckSessionTest` passed (incl. the added playback-without-config test); `./gradlew :app:testDebugUnitTest --tests QuickCheckRouteTest` 3/3 and `PhysicalLessonScreenTest` 47/47 passed; `./gradlew spotlessCheck build` BUILD SUCCESSFUL (Kover, lint, content tests, `CertaintyProducerTest`, `TransitionsTableTest`, policy gates, APKs). Matrix audit: every I/O row has a passing test.

**Residual risks:** the Likely copy is lesson-agnostic ("…moved the cross.") until the PO confirms copy at the device session; without a saved calibration every Quick Check runs the full 6-face scan; a recreation in the milliseconds before the capture launches can leave a pending check until the next session change; the 10.6 `R U2 R'` Partial-match alias remains. Human gate: device session on the main debug build.
