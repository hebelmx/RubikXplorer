# Story 10.34 — legacy fixture readers and the new continuous replay

Story 10.34 (record and replay a continuous Quick Scan session) adds a new fixture root, `Fixtures/continuous/`, and
must leave every existing fixture root with a working reader (SCP 2026-11-25, "Preserve historical evidence"). This page
records, for each legacy reader, the command, the result of running it on this story's tree, and the archived revision
of the score it produced. Base revision of this run: `62e9565945a57063e36a32108f8566ab9e99f95e` plus the Story 10.34
working tree, 2026-11-26, JDK 25, `export JAVA_HOME=/usr/lib/jvm/java-25-openjdk-amd64 ANDROID_HOME=$HOME/Android/Sdk`.

"Same output" means the regenerated file equals the checked-in one (ignoring CR/LF). "Runs; output drifted" means the
reader runs and decodes its fixtures, but the score differs from the checked-in report for reasons older than this story
(see the note); the regenerated files were **reverted, not committed**, because refreshing those reports is out of scope.

| Fixture root | Reader | Command | Result (this run) | Archived score: revision |
| --- | --- | --- | --- | --- |
| `Fixtures/live` | Live baseline | `./gradlew :core:domain:evaluateLiveBaseline` | Runs, exit 0; `docs/spikes/oq-4-guided-scan.md` regenerated with the same output | `698489a8cddbe386888ea4693842ebc02cd8ba5b` |
| `Fixtures/live` | Scan accuracy (Story 10.1) | `./gradlew :core:domain:evaluateScanAccuracy` | Runs, exit 0; `docs/scan-accuracy.md` same output | `9d5fda35cbc909d703231cfdffaa6647170c368d` |
| `Fixtures/live` | OQ-7 approaches (OpenCV spike set) | `./gradlew :core:domain:evaluateRecognitionApproaches` | Runs, exit 0; `docs/spikes/oq-7-recognition-approach.md` same output | `0490f8d044419bf0b4acf8ca00b36ca6902c3b3c` |
| `Fixtures/live` | `LiveFixture` decoding | `./gradlew :core:domain:test --tests 'dev.cubentia.domain.recognition.LiveFixtureTest' --tests 'dev.cubentia.domain.recognition.evaluation.LiveBaselineEvaluatorTest'` | Pass | — (tests) |
| `Fixtures/corner` + `tools/sticker-model/holdout.json` | `exportKotlinStickerReads` | `./gradlew :core:domain:exportKotlinStickerReads` | Runs, exit 0, writes `docs/evidence/10-23/kotlin-reads.json`; output drifted (some `self` reads now CONFIDENT) | `44ef3e8febc80260673767718b0559a62b56952f` |
| `Fixtures/corner` | Side-face replay, group index | `./gradlew :core:domain:test --tests 'dev.cubentia.domain.recognition.QuickScanSideFaceReplayTest' --tests 'dev.cubentia.domain.recognition.CornerGroupIndexTest'` | Pass | — (tests) |
| `Fixtures/matrix` | `evaluateMatrix` (OQ-8) | `./gradlew :core:domain:evaluateMatrix` | Runs, exit 0; output drifted (newer `*_cool_guided` logs in `Fixtures/matrix/` add rows) | `915ebdfff672230d966ff2a4bb16778041231507` |
| `Fixtures/matrix` | Matrix scoring tests | `./gradlew :core:domain:test --tests 'dev.cubentia.domain.recognition.MatrixEvaluationTest'` | Pass | — (tests) |
| `Fixtures/labeled` (`face-samples.json`) | Guided scan spike fixture | `./gradlew :core:domain:test --tests 'dev.cubentia.domain.recognition.ScanFixtureEvaluationTest'` | Pass | — (tests) |
| `Fixtures/labeled` + photo groups (`*_S1..S4`) | Offline S3/S4 photo colour check | `python3 tools/evaluate_fixture_photos.py --check` | "photo evaluation output matches" | `5dfdd6597096da849420bb3e8f1de310501ac7bc` |
| `docs/evidence/quick-scan-2026-11-22/bursts` | Two-corner burst replay (**retired by Story 10.32**) | at the archived revision: `git worktree add ../replay-10-22 52b66aefa63ab02f8ae6b08cc6a6e5d769756be8 && cd ../replay-10-22 && ./gradlew :core:domain:test --tests 'dev.cubentia.domain.recognition.QuickScanBurstReplayTest'` | Not re-run here (as in `docs/evidence/10-32/camera-size.md` §3) | `52b66aefa63ab02f8ae6b08cc6a6e5d769756be8` |

Note on the two drifted outputs: neither reader's code nor its inputs is touched by Story 10.34 (the story's only change
near them is a KDoc paragraph in `KotlinStickerReads.kt`). `kotlin-reads.json` was last written at `44ef3e8f` before
later classifier work; `oq-8-recognition-matrix.md` was last written at `915ebdff` before the `*_cool_guided` matrix
logs were recorded. Refreshing those reports belongs to their owners (Stories 10.23/10.8 follow-ups), not here.

To reproduce an archived score exactly, run its command at the listed revision in a separate worktree
(`git worktree add ../score-<rev> <rev>`), with the environment of that revision's `AGENTS.md`.

## New continuous replay (this story)

| Fixture | Command | Result |
| --- | --- | --- |
| `Fixtures/continuous/synthetic-complete-v1` (synthetic) | `./gradlew :core:domain:replayContinuousSession` | COMPLETE; 116/130 frames with observations; 6/6 sides; 54/54 committed; final net equals the labels; 0 snapshot/cue diffs; 0 label mismatches |
| `Fixtures/continuous/synthetic-partial-v1` (synthetic) | same | PARTIAL; 83/96 frames with observations; 5/6 sides; 46/54 committed; last confirmed net `WWWWWGBRBRWGRRRRRRWWWGGGGGGYYYYYYYYYGBOOOOOOO....B....` (8 unknowns kept); last cue `TurnRemaining(1)`; 0 diffs; 0 mismatches, 8 labelled stickers not committed |
| both | `./gradlew :core:domain:test --tests '*ContinuousSessionReplayTest'` | Pass (golden: exact snapshots and cue stream; tampered snapshot reported; temp/manifest-less dirs ignored) |

So one old fixture root (`Fixtures/corner` through `exportKotlinStickerReads` and the side-face replay) and one new
continuous (synthetic) session replay successfully on this tree.

**PO step (pending):** replay of a real recorded Lenovo TB128XU session (state replay, golden refresh and
`ContinuousImageReplayDeviceTest`) after the PO curates one complete and one incomplete session — see
[`latency.md`](latency.md) for the human-gate checklist. No device session is claimed here.
