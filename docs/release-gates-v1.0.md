# Release gates for v1.0 (draft)

**Status: draft for Story 12.4. Not complete.** This checklist uses only evidence that already exists in the repo, in GitHub Actions and in the issue tracker (checked 2026-11-19 against `main` at `c2f5613`). Gates 1 and 2 have passing automated evidence. Gate 3 passes in JVM CI on every merge, but the device run of the end-to-end suite was last recorded at `v0.9.0`. Gate 5 is partly evidenced. Gate 4 (the beginner pilot) has not run yet: closed testing in [#127](https://github.com/hebelmx/RubikXplorer/issues/127) is still open. So no SM-1–SM-9 baseline exists, and the severity proposals below come from internal testing and device sessions, not from the pilot record. Gate 6 is N/A for v1.0.

Sources: PRD §7 release gates and §8 success metrics (`_bmad-output/planning-artifacts/prds/prd-RubikXplorer-2026-10-29/prd.md`), and the Story 12.4 acceptance criteria in `_bmad-output/planning-artifacts/epics.md`.

## Gates

CI on `main`, latest green run: [44470618432](https://github.com/hebelmx/RubikXplorer/actions/runs/44470618432) (merge of #214, 2026-11-19). `./gradlew spotlessCheck build` runs every JVM test named below. Device instrumentation runs only in the release workflow: [Release pilot run 43358827489](https://github.com/hebelmx/RubikXplorer/actions/runs/43358827489) (tag `v0.9.0`, 2026-11-13, success).

| Gate | What it requires (PRD §7) | Evidence | Status |
| --- | --- | --- | --- |
| 1 | The cube engine's invariants and the Algorithm Library verification pass in CI | `core/domain/src/test/.../cube/` (`CubeStateTest`, `CubeValidatorTest`, `CubiesTest`, `MoveTest`, `OrientationTest`, …); `ShippedAlgorithmVerificationTest` and `MistypedAlgorithmFixtureTest` (build fails on a wrong Algorithm, PR #89); `AlgorithmLibraryTest`. Run in CI 44470618432. Stories 1.2 (#5), 1.4 (#7), 9.2 (#89) | Pass |
| 2 | Every Lesson has tested success and failure paths, every Error Category has a tested Recovery Path, and hints and mastery follow their configuration | `ShippedLessonPathsTest`, `TeachingModesScenarioTest`, `StepDerivationScenarioTest`, `DiagnosisSessionScenarioTest` (`core/application`); `DiagnosisScenarioTest`, `RecoveryPathScenarioTest`, `RecoveryPlannerTest`, `HintLadderTest`, `MasteryTest`, `TwoLookMasteryTest` (`core/domain`). Stories 4.1–4.6 (#42–#47), 7.5 (#68), 9.8 (#95). Run in CI 44470618432. This draft has not re-checked that every Error Category is covered | Pass (coverage not re-audited) |
| 3 | The end-to-end suite shows no silent desync between virtual and physical state, and undo, pause, restart and resume keep the state valid | `EndToEndSyncVerificationTest`, both the Robolectric version (`app/src/test/.../session/`, every CI run) and the device version (`app/src/androidTest/.../session/`); `SessionSnapshotRestartTest`, `Cube3DRenderSyncTest`, `KeyFlowRotationTest` (androidTest). Story 8.6 (#76), 8.4 (#74), 6.1 (#57). The device suite passed in release run 43358827489 at `v0.9.0`. It has not run since, because CI does not run `connected*` tasks | Partial (device run predates Epic 12 changes) |
| 4 | A beginner pilot completes the Course, Move instructions make sense without Notation, Learners recover from deliberate mistakes, completion, hints and confusion are recorded, and every severity-1 and severity-2 issue is fixed | Protocol: `docs/pilot-session-protocol.md`. Runbooks: `docs/release.md`, `docs/play-first-publish.md`. Story 7.8 (#126). Pilot tracking: #127 (open; closed-testing promotion, pilot installs and sessions are unticked). No pilot record exists yet. Internal-testing and device-session triage below is a stand-in | Missing |
| 5 | Offline behavior, state recovery and accessibility are checked | Offline: the release manifest has no `INTERNET` permission, enforced by `:app:checkReleaseMergedManifest` and `:app:checkPrivacyFacts` in CI; `docs/privacy-facts.json` (`"internetPermission": false`). State recovery: Stories 6.1 (#57), 6.2 (#58), 6.5 (#61), 12.13 (#103); `SessionSnapshotRestartTest`, `RoomCheckpointRepositoryTest`, `CheckpointTest`. Accessibility: Story 12.1 automated audit (#101; `app/src/test/.../ui/a11y/`). The PO reported the device TalkBack and Switch Access pass on 2026-11-13 (#135, `docs/accessibility-manual-check.md`), but the per-flow table there is empty. Offline run: every Key Flow (1, 2, 3, 6, 7, 8, 9) passed from a cleared install with airplane mode on, each flow checking it saw airplane mode on, on the `GalaxyS24FE_API36` emulator with the debug build (Story 12.3, `docs/evidence/12-3/README.md`, `docs/evidence/12-3/offline/`); release tags now run the Key Flow suite offline (`.github/scripts/run-instrumentation.sh`). The release build on a physical device offline is still open (PO item in `docs/evidence/12-3/README.md`) | Partial |
| 6 | *(v1.1)* Every scan flow has a manual fallback and no unvalidated Scan Result is used. N/A when every camera flag is OFF | See below | N/A for v1.0 |

### Gate 6: N/A for v1.0

- Release flag levels: `build-logic/src/main/kotlin/dev/cubentia/buildlogic/policy/FeatureFlagLevels.kt`. In release, only `ADAPTIVE_ASSISTANCE` (GA) and `TEST_ME` (BETA) are above OFF. `CAMERA_SCAN`, `QUICK_SCAN`, `PLACEMENT` and `CASE_RECOGNITION` are OFF.
- Story 10.9 (PR #146) keeps camera code off the release classpath (`:app:checkReleaseCameraClasspath` in CI).
- Story 12.10 (PR #87) proves end to end that a release declaring `CAMERA` fails `checkReleaseMergedManifest`. Story 8.7 (PR #79) added the release runtime-permission gate.
- `docs/privacy-facts.json`: `"runtimePermissions": []`, checked by `:app:checkPrivacyFacts`.

## Success metrics SM-1–SM-9 (PRD §8)

All nine are pilot metrics; SM-5 and SM-7 also use on-device statistics. The pilot has not run, so no baseline exists for any of them.

| Metric | Baseline | Source |
| --- | --- | --- |
| SM-1 First-solve completion | Not measured yet | Pilot (#127) |
| SM-2 Independent solve of a fresh Scramble | Not measured yet | Pilot (#127) |
| SM-3 Recovery without restarting the Lesson | Not measured yet | Pilot (#127) |
| SM-4 Comprehension of Objectives and Move instructions | Not measured yet | Pilot (#127) |
| SM-5 Hints per attempt falling over attempts | Not measured yet | Pilot (#127) and on-device |
| SM-6 Time and sessions to a first solve | Not measured yet | Pilot (#127) |
| SM-7 Incorrect Moves per Lesson and Stage | Not measured yet | Pilot (#127) and on-device |
| SM-8 Scans valid on the first attempt | N/A for the v1.0 pilot, which has no camera (`docs/pilot-session-protocol.md`). `docs/scan-accuracy.md` measures recognition accuracy, not this metric | v1.1 |
| SM-9 2-look LL progression | Not measured yet | Pilot (#127) |

## Feedback triage (proposed, PO to confirm)

Severity uses the pilot protocol's scale: **1** blocks progress with no workaround; **2** major confusion or failure that needs help; **3** recoverable friction; **4** minor copy or presentation. Internal testing (2026-11-18) gave F1–F6 (F4 was dropped). The device sessions were for Stories 12.14/12.15 and for 10.8 (session C, 2026-11-18). Issues marked v1.1 are in camera flows, which are OFF in v1.0, so they don't gate v1.0.

| Issue | Source | Proposed severity | Reason | State |
| --- | --- | --- | --- | --- |
| #206 Gestures misfire and block the Course (F2) | Internal testing | 1 | Turns misfire and block the Course on the virtual cube | Fixed in PR #213 (Story 12.16) |
| #207 Show when the view is rotated, Reset view (F1) | Internal testing | 2 | After rotating, a Learner can't predict a turn | Fixed in PR #214 (Story 12.17); issue still open |
| #209 Choose an entry level for "I can already solve it" (F3) | Internal testing | 3 | Not a defect; walking through every Stage check slows intermediate Learners | Open (v1.x idea) |
| #208 "I'm doing it on my cube" for every Algorithm (F5) | Internal testing | – | Feature idea; needs Quick Check (v1.1) | Open (v1.x idea) |
| #210 Method-aware solutions (F6) | Internal testing | – | Epic-level feature idea | Open (v1.x idea) |
| #175 Playback: switch 2D net and 3D | Device session | 3 | Missing view option | Fixed in PR #187 (Story 12.14) |
| #176 Move glyph next to each label | Device session | 3 | Helps reading Moves without Notation | Fixed in PR #188 (Story 12.15) |
| #200 Playback: certainty shown twice, label overlaps the net | Device session C | 3 | Covers part of the white face at the end of playback | Open |
| #201 Playback: solution length not obvious | Device session C | 3 | Learner misjudges the plan's length | Open |
| #202 Solver: merge same-face turns (`R R2`) | Device session C | 3 | The plan is correct, but an unsimplified Move confuses a beginner | Open |
| #204 Manual Cube Editor: about 96 taps per cube | Device session C | 3 | Works, but entering a cube in v1.0 takes a lot of effort | Open |
| #194 Quick Scan merges a re-asked face rotated 180° | Device session C | 1 (v1.1) | The cube becomes invalid | Open, v1.1 |
| #195 Quick Scan corner guide can't be matched | Device session C | 1 (v1.1) | No real camera view can fit the guide | Open, v1.1 |
| #196 Quick Scan corners fail on dark glossy backgrounds | Device session C | 2 (v1.1) | Scanning fails in common conditions | Open, v1.1 |
| #197 Quick Scan: no capture feedback | Device session C | 3 (v1.1) | Learner unsure a photo was taken | Open, v1.1 |
| #198 Quick Scan to Guided Scan keeping faces | Device session C | 3 (v1.1) | Enhancement | Open, v1.1 |
| #199 Scan review: link errors to pieces | Device session C | 3 (v1.1) | Learner has to hunt for the conflicting stickers | Open, v1.1 (scan flow) |
| #203 Guided Scan confirms the cube twice | Device session C | 3 (v1.1) | Redundant step | Open, v1.1 (scan flow) |
| #205 Placement outcome screen breaks the dark theme | Device session C | 3 (v1.1) | Status bar unreadable; Placement is OFF in v1.0 | Open, v1.1 (Placement) |

Measurement-driven revisions, already resolved: `sprint-change-proposal-2026-11-11-preparing-copy-and-benchmark.md` (#112, closed; #123, closed) and `sprint-change-proposal-2026-11-12-lenovo-minimum-reference.md` (PR #120, `docs/evidence/lenovo-tb128xu/`). #119 is still open; it covers the limits of the Lenovo measurements and gaps in the harness, not a usability issue.

## Still needed to close 12.4

- Run the closed-testing pilot through #127: promote to closed testing, have about 8 beginners install it, and run the sessions with `docs/pilot-session-protocol.md`. Then write down the SM-1–SM-7 and SM-9 baselines here (SM-8 is N/A).
- Triage the pilot's severity-1 and severity-2 findings and fix them or resolve them explicitly.
- PO confirms or changes the proposed severities above. Close #207 if PR #214 resolves it.
- Gate 3: run the device instrumentation suite (`.github/scripts/run-instrumentation.sh`) on the release candidate, for example through the next release tag, and link the run.
- Gate 5: fill in the per-flow table in `docs/accessibility-manual-check.md`, and record an offline physical-device check of the release build (airplane mode, cold start, resume) in `docs/evidence/12-3/README.md`.
- Gate 2: confirm that every Error Category maps to a named Recovery Path test, or record the gap.
- Link the final CI and release run URLs for the release-candidate commit.
