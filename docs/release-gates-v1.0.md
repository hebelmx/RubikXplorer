# Release gates for v1.0

**Status: checklist for Story 12.4, run 2026-11-24 (dates are CST, UTC-6, unless marked UTC) against `main` at `9862b71b9f16b0f32905d9f7e1e7406c35373990` (merge of PR #360). Production is not cleared.** Gates 1 and 2 pass. Gate 6 is N/A for v1.0 (every camera flag OFF in release). Gate 3 is partial: no green release device-suite run exists after `v0.9.0`. Gate 4 is missing: the beginner pilot in [#127](https://github.com/hebelmx/RubikXplorer/issues/127) has not run, so no SM-1–SM-9 baseline exists. Gate 5 is partial. Of the six severity-1 and severity-2 issues known so far, four are fixed; #195 (1) and #196 (2) are open and not reachable in this build because `QUICK_SCAN` is OFF in release (the PO's 2026-11-24 call), deferred with the rule that they are fixed or re-triaged before any `QUICK_SCAN` raise (see below). The pilot may add more. The open items are listed under "Still needed".

Sources: PRD §7 release gates and §8 success metrics (`_bmad-output/planning-artifacts/prds/prd-RubikXplorer-2026-10-29/prd.md`), AD-14 (quality gates in CI), and the Story 12.4 acceptance criteria in `_bmad-output/planning-artifacts/epics.md`. This checklist supersedes the 2026-11-19 draft (checked then against `c2f5613`).

## Gates

CI on `main`, latest green run: [45520622483](https://github.com/hebelmx/RubikXplorer/actions/runs/45520622483) at `9862b71b9f16b0f32905d9f7e1e7406c35373990` (merge of #360, 2026-11-24 18:45 CST = 2026-11-25 00:45 UTC). `./gradlew spotlessCheck build` runs every JVM test named below. Device instrumentation runs only in the release workflow. Latest green release device-suite run: [Release pilot run 43358827489](https://github.com/hebelmx/RubikXplorer/actions/runs/43358827489) (tag `v0.9.0`, `efa3b589fd305702774aafe81730ac398355d80d`, 2026-11-13, success).

| Gate | What it requires (PRD §7) | Evidence | Status |
| --- | --- | --- | --- |
| 1 | The cube engine's invariants and the Algorithm Library verification pass in CI | `core/domain/src/test/.../cube/` (`CubeStateTest`, `CubeValidatorTest`, `CubiesTest`, `MoveTest`, `OrientationTest`, …); `ShippedAlgorithmVerificationTest` and `MistypedAlgorithmFixtureTest` (build fails on a wrong Algorithm, PR #89); `AlgorithmLibraryTest`. Run in CI [45520622483](https://github.com/hebelmx/RubikXplorer/actions/runs/45520622483). Stories 1.2 (#5), 1.4 (#7), 9.2 (#89) | Pass |
| 2 | Every Lesson has tested success and failure paths, every Error Category has a tested Recovery Path, and hints and mastery follow their configuration | `ShippedLessonPathsTest`, `TeachingModesScenarioTest`, `StepDerivationScenarioTest`, `DiagnosisSessionScenarioTest` (`core/application`); `DiagnosisScenarioTest`, `RecoveryPathScenarioTest`, `RecoveryPlannerTest`, `HintLadderTest`, `MasteryTest`, `TwoLookMasteryTest` (`core/domain`). Stories 4.1–4.6 (#42–#47), 7.5 (#68), 9.8 (#95). Run in CI [45520622483](https://github.com/hebelmx/RubikXplorer/actions/runs/45520622483). Error Category audit (2026-11-24): `core/domain/src/test/kotlin/dev/cubentia/domain/tutor/RecoveryPathScenarioTest.kt` has one named scenario per `ErrorCategory`, 7 of 7 (wrong move or direction, wrong piece, wrong piece orientation, unexpected orientation, completed layer disturbed, invalid entered state, unknown discrepancy), and the guard test `every category has a scenario here` pins the category count at 7 (`ErrorCategory.entries.size`), so a new category must add a scenario. Each scenario checks its category's Recovery Path | Pass |
| 3 | The end-to-end suite shows no silent desync between virtual and physical state, and undo, pause, restart and resume keep the state valid | `EndToEndSyncVerificationTest`, both the Robolectric version (`app/src/test/.../session/`, every CI run) and the device version (`app/src/androidTest/.../session/`); `SessionSnapshotRestartTest`, `Cube3DRenderSyncTest`, `KeyFlowRotationTest` (androidTest). Story 8.6 (#76), 8.4 (#74), 6.1 (#57). Release device suite: last green in run [43358827489](https://github.com/hebelmx/RubikXplorer/actions/runs/43358827489) at `v0.9.0`; the `v0.10.0` run failed (see below). Device session 2026-11-23, Block 5 ([first run](evidence/release-device-session/README.md#block-5-automated-device-tests), [continued](evidence/release-device-session/README.md#block-5-continued)): Galaxy S24 FE `Cube3DRenderSyncTest` 4/4 and `EndToEndSyncVerificationTest` pass (10,000 steps in 183 s); Lenovo `EndToEndSyncVerificationTest` passes, and its 10,000-step `Cube3DRenderSyncTest` passes only with a temporary 15-minute timeout (464.6 s; the committed 300 s limit is too tight for the minimum device, not a sync fault: [#314](https://github.com/hebelmx/RubikXplorer/issues/314), open). On the Lenovo, `physicalSwitchKeepsTheStepControlsAndQuickCheckEvidence` was flaky (failed once with "No compose hierarchies found", passed in isolation). Items 5.3–5.5 were not run | Partial (no green release device-suite run after `v0.9.0`) |
| 4 | A beginner pilot completes the Course, Move instructions make sense without Notation, Learners recover from deliberate mistakes, completion, hints and confusion are recorded, and every severity-1 and severity-2 issue is fixed | Protocol: `docs/pilot-session-protocol.md`. Runbooks: `docs/release.md`, `docs/play-first-publish.md`. Story 7.8 (#126). Pilot tracking: [#127](https://github.com/hebelmx/RubikXplorer/issues/127) (open). No pilot record exists. Of the severity-1/2 issues from internal testing and the device sessions, four are fixed and #195/#196 are open but not reachable in this build (`QUICK_SCAN` OFF; below). None of them are pilot findings | Missing |
| 5 | Offline behavior, state recovery and accessibility are checked | Offline: the release manifest has no `INTERNET` permission, enforced by `:app:checkReleaseMergedManifest` and `:app:checkPrivacyFacts` in CI; `docs/privacy-facts.json` (`"internetPermission": false`). Every Key Flow (1, 2, 3, 6, 7, 8, 9) passed offline from a cleared install on the `GalaxyS24FE_API36` and `GalaxyTabS7FE_API34` emulators with the debug build (Story 12.3, [`docs/evidence/12-3/README.md`](evidence/12-3/README.md)); release tags run the Key Flow suite offline (`.github/scripts/run-instrumentation.sh`). Release code on a physical device (device session 2026-11-23, Block 1, benchmark build at `dfc5c3d6864968e455f09de4bfd3e0ab87e8228b`, before the later editor and scan fixes #321 and #352–#354, [evidence](evidence/release-device-session/README.md#block-1-release-build-smoke-blocks-127)): 1.3 PASS, Learn, a Lesson check, a Stage 2 Lesson, the Library, *Enter colors* and Solution Playback work in airplane mode on the Lenovo (benchmark build = release code, R8, debug-signed, `dfc5c3d6864968e455f09de4bfd3e0ab87e8228b`); 1.4 PASS, no Android permission requested on either device. State recovery: Stories 6.1 (#57), 6.2 (#58), 6.5 (#61), 12.13 (#103); `SessionSnapshotRestartTest`, `RoomCheckpointRepositoryTest`, `CheckpointTest`; Block 1.2 PASS (progress kept after force-stop and relaunch). Accessibility: Story 12.1 automated audit (#101; `app/src/test/.../ui/a11y/`). The PO reported the device TalkBack and Switch Access pass on 2026-11-13 (#135, `docs/accessibility-manual-check.md`), but the per-flow table there is still empty. Device session 6.8 (2026-11-24, debug build at `880ff8e84edead1f1064d428c8afae75753c2939`, Lenovo and Galaxy, [evidence](evidence/release-device-session/README.md)): the cube editor passes TalkBack (Galaxy, PO listening) and 200% text (Lenovo, Galaxy); 6.9 (debug build at `3cf4307222fee4bc07f6bbe02823ad5ddbb2a2cd` on the `GalaxyS24FE_API36` emulator) re-checked the #354 Close dialog under TalkBack (PR #359). Open: the per-flow a11y table, and the release candidate's release build installed from a Play track and run offline on a physical device, re-running the Block 1 release-build smoke (PO item in `docs/evidence/12-3/README.md`) | Partial |
| 6 | *(v1.1)* Every scan flow has a manual fallback and no unvalidated Scan Result is used. N/A when every camera flag is OFF | See below | N/A for v1.0 (every camera flag OFF in release, no `CAMERA` permission) |

### Gate 3: the v0.10.0 release run

- `v0.10.0` was tagged at `23e1de476278a7decdd0308e55b78f8582975582` (merge of PR #359). Its Release pilot run [45514196023](https://github.com/hebelmx/RubikXplorer/actions/runs/45514196023) (started 2026-11-24 17:57 CST = 23:57 UTC) failed at the `instrumentation` job: Key Flow `flow2`, `flow3`, `flow6` and `flow8` failed (`flow1`, `flow7` and `flow9` passed), because the androidTest suite lagged the Story 14.1 entry-level picker and the #352/#253 editor changes. The `release` job was skipped, so nothing was uploaded to Play.
- PR [#360](https://github.com/hebelmx/RubikXplorer/pull/360) (merge `9862b71b9f16b0f32905d9f7e1e7406c35373990`) fixed the tests. It is test-only; 7 of 7 flows pass locally on the `GalaxyS24FE_API36` emulator, offline.
- The `v0.10.0` tag was deleted. No newer release run exists, so the latest green release device-suite run is still `v0.9.0`, run [43358827489](https://github.com/hebelmx/RubikXplorer/actions/runs/43358827489) (`efa3b589fd305702774aafe81730ac398355d80d`, 2026-11-13).

### Gate 6: N/A for v1.0 (every camera flag OFF in release, no `CAMERA` permission)

- Release flag levels: `build-logic/src/main/kotlin/dev/cubentia/buildlogic/policy/FeatureFlagLevels.kt`. In release, only `ADAPTIVE_ASSISTANCE` (GA) and `TEST_ME` (BETA) are above OFF. `CAMERA_SCAN`, `QUICK_SCAN`, `PLACEMENT` and `CASE_RECOGNITION` are OFF, so the release declares no `CAMERA` permission.
- Story 10.9 (PR #146) keeps camera code off the release classpath (`:app:checkReleaseCameraClasspath` in CI).
- Story 12.10 (PR #87) proves end to end that a release declaring `CAMERA` fails `checkReleaseMergedManifest`. Story 8.7 (PR #79) added the release runtime-permission gate.
- `docs/privacy-facts.json`: `"runtimePermissions": []`, checked by `:app:checkPrivacyFacts`.
- PRD gate 6 is checked by Story 10.11 for each camera feature it raises; this checklist records it N/A only while every camera flag is OFF.

## Success metrics SM-1–SM-9 (PRD §8)

All nine are pilot metrics; SM-5 and SM-7 also use on-device statistics. The pilot has not run, so no baseline exists for any of them. Each is an open follow-up of the pilot ([#127](https://github.com/hebelmx/RubikXplorer/issues/127)).

| Metric | Baseline | Source |
| --- | --- | --- |
| SM-1 First-solve completion | Not measured yet (open: #127) | Pilot (#127) |
| SM-2 Independent solve of a fresh Scramble | Not measured yet (open: #127) | Pilot (#127) |
| SM-3 Recovery without restarting the Lesson | Not measured yet (open: #127) | Pilot (#127) |
| SM-4 Comprehension of Objectives and Move instructions | Not measured yet (open: #127) | Pilot (#127) |
| SM-5 Hints per attempt falling over attempts | Not measured yet (open: #127) | Pilot (#127) and on-device |
| SM-6 Time and sessions to a first solve | Not measured yet (open: #127) | Pilot (#127) |
| SM-7 Incorrect Moves per Lesson and Stage | Not measured yet (open: #127) | Pilot (#127) and on-device |
| SM-8 Scans valid on the first attempt | N/A for the v1.0 pilot, which has no camera (`docs/pilot-session-protocol.md`). `docs/scan-accuracy.md` measures recognition accuracy, not this metric | v1.1 |
| SM-9 2-look LL progression | Not measured yet (open: #127) | Pilot (#127) |

## Severity-1 and severity-2 issues

Severity uses the pilot protocol's scale: **1** blocks progress with no workaround; **2** major confusion or failure that needs help. Severities are quoted from their source. The PO triaged them through the issue-orchestrate runs and the device sessions; this checklist does not re-triage. Evidence: [device-session README](evidence/release-device-session/README.md) (sessions 2026-11-23/24, including 6.8 and 6.9) and the 2026-11-18 internal-testing and device-session C records.

| Issue | Severity (source) | Resolution |
| --- | --- | --- |
| [#206](https://github.com/hebelmx/RubikXplorer/issues/206) gestures misfire | 1 (internal testing) | Fixed, PR #213 (Story 12.16, `aa0ef871943fe2aff440780d93c0b6dbf99e698e`); closed |
| [#207](https://github.com/hebelmx/RubikXplorer/issues/207) view rotated / Reset view | 2 (internal testing) | Fixed, PR #214 (Story 12.17, `c2f56134eb52849efc698bb07a0e71431c696090`); closed |
| [#354](https://github.com/hebelmx/RubikXplorer/issues/354) editor Close discards without asking | 2 proposed (device session 6.8, F4) | Fixed, PR #357 (`3cf4307222fee4bc07f6bbe02823ad5ddbb2a2cd`); re-checked in 6.9 (PR #359); closed |
| [#194](https://github.com/hebelmx/RubikXplorer/issues/194) Quick Scan re-asked face merged 180° | 1 (device session C) | Fixed, PR #217 (`1bc07e41f00d3435db09f21ac7312e883969fcdb`); closed |
| [#195](https://github.com/hebelmx/RubikXplorer/issues/195) Quick Scan corner guide can't be matched | 1 (device session C) | Open. Not reachable in this release: `QUICK_SCAN` is OFF in release (`FeatureFlagLevels.kt`; PO call 2026-11-24, action item `epic-10-deferred-10-21-po-beta-quick-scan-off-pipeline-rethink`). Must be fixed or re-triaged before a Story 10.11 run raises `QUICK_SCAN` |
| [#196](https://github.com/hebelmx/RubikXplorer/issues/196) Quick Scan corners fail on dark glossy backgrounds | 2 (device session C) | Same as #195 |

No PO confirmation of #354's proposed severity 2 is recorded. The other 2026-11-23/24 device-session issues (#313–#326, #337) have no severity recorded in their sources; none is recorded as severity 1 or 2.

Severity-3/4 and v1.x items stay in GitHub issues; see the [device-session README](evidence/release-device-session/README.md) and `_bmad-output/planning-artifacts/sprint-change-proposal-2026-11-24-github-issue-triage.md` (SCP 2026-11-24).

Measurement-driven revisions, already resolved: `sprint-change-proposal-2026-11-11-preparing-copy-and-benchmark.md` (#112, closed; #123, closed) and `sprint-change-proposal-2026-11-12-lenovo-minimum-reference.md` (PR #120, `docs/evidence/lenovo-tb128xu/`). #119 is still open (limits of the Lenovo measurements, harness gaps). Severity-3/4 findings, including the 2026-11-19 draft's #200–#205 rows and the Block 1 S3 notes (for example the *Continue* card pointing at a finished Lesson after a restart), are recorded in the [device-session README](evidence/release-device-session/README.md) and their GitHub issues.

## Still needed before production

- Run the closed-testing pilot through [#127](https://github.com/hebelmx/RubikXplorer/issues/127) with `docs/pilot-session-protocol.md` (about 8 beginners), then record the SM-1–SM-7 and SM-9 baselines here (SM-8 is N/A).
- Triage the pilot's severity-1 and severity-2 findings, if any, and fix or explicitly resolve them.
- The PO confirms the proposed severity 2 of [#354](https://github.com/hebelmx/RubikXplorer/issues/354) (no confirmation is recorded).
- Gate 3: a green release device-suite run on the release candidate, through the PO's next release tag (after PR #360), linked here.
- Gate 3: a device-tiered timeout for the 10,000-step `Cube3DRenderSyncTest` ([#314](https://github.com/hebelmx/RubikXplorer/issues/314)).
- Gate 3: the flaky `physicalSwitchKeepsTheStepControlsAndQuickCheckEvidence` on the Lenovo, and device-session items 5.3–5.5, not run yet.
- Gate 5: fill in the per-flow table in `docs/accessibility-manual-check.md`.
- Gate 5: the release candidate's release build installed from a Play internal or closed track and run offline on a physical device (airplane mode, cold start, resume), re-running the Block 1 release-build smoke, recorded in `docs/evidence/12-3/README.md`.
- Stability: Android vitals from the closed-testing build (`docs/evidence/12-3/README.md` lists them as "Pending"; the internal track had "Not enough data").
- Before any Story 10.11 run raises `QUICK_SCAN`: fix or re-triage [#195](https://github.com/hebelmx/RubikXplorer/issues/195) and [#196](https://github.com/hebelmx/RubikXplorer/issues/196).
