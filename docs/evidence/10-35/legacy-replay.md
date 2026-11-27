# Legacy-replay record (Stories 10.35 and 10.34)

This is the single legacy-replay record: Story 10.34 appends its continuous-session replay below instead of creating
`docs/evidence/10-34/legacy-replay.md`.

## Where the recorder still exists

The separate debug fixture recorder (`dev.cubentia.app.camera.recorder`, `FixtureRecorderActivity`, all modes) was retired
by Story 10.35 (SCP 2026-11-26 retire-recorder). The last revision that contains it is
`af2a6d7a93d6caa737ac09ac5cc227eedbe2f0dd` (origin/main before this story). To look at it or run it, check it out in its own worktree, build and install its debug APK, and launch the recorder (it has no launcher icon):

```sh
git worktree add ../cubentia-recorder af2a6d7a93d6caa737ac09ac5cc227eedbe2f0dd
cd ../cubentia-recorder
export JAVA_HOME=/usr/lib/jvm/java-25-openjdk-amd64 ANDROID_HOME=$HOME/Android/Sdk
./gradlew :app:installDebug
adb shell am start -n com.exxerpro.cubentia/dev.cubentia.app.camera.recorder.FixtureRecorderActivity
```

In that worktree `tools/cube-session.sh recorder <lenovo|s24>` (the same launch) and `tools/cube-session.sh pull <lenovo|s24>`
(copy the recorded `Fixtures/{live,corner}` off the device) still exist.

## Story 10.35 legacy replay (2026-11-26)

Run on branch `feat/10-35-retire-the-quick-scan-only-two-corner-recorder-paths` after the removal, with
`export JAVA_HOME=/usr/lib/jvm/java-25-openjdk-amd64 ANDROID_HOME=$HOME/Android/Sdk`. Every command succeeded.

| Command | Fixtures read | Exit | Outcome |
| --- | --- | --- | --- |
| `./gradlew :core:domain:evaluateLiveBaseline` | `Fixtures/live` | 0 | BUILD SUCCESSFUL; rewrote the Live baseline span of `docs/spikes/oq-4-guided-scan.md` with no content change (line endings aside) |
| `./gradlew :core:domain:evaluateScanAccuracy` | `Fixtures/live` | 0 | BUILD SUCCESSFUL; rewrote `docs/scan-accuracy.md` with no content change (line endings aside) |
| `./gradlew :core:domain:evaluateRecognitionApproaches` | `Fixtures/live` (OpenCV from the Gradle cache) | 0 | BUILD SUCCESSFUL; rewrote `docs/spikes/oq-7-recognition-approach.md` with no content change (line endings aside) |
| `./gradlew :core:domain:evaluateMatrix` | `Fixtures/matrix` | 0 | BUILD SUCCESSFUL; "Updated docs/spikes/oq-8-recognition-matrix.md" (see the note below) |
| `./gradlew :core:domain:evaluateQuickScan --args="$PWD/Fixtures/matrix/lenovo-tb128xu_stka_warm_quickscan.log"` | `Fixtures/matrix` | 0 | BUILD SUCCESSFUL; Runs 1, successful 0, fallback faces 1, re-prompted faces 1, recommendation BETA (report in `core/domain/build/reports/quick-scan-measurement.md`, untracked) |
| `./gradlew :core:domain:exportKotlinStickerReads` | `Fixtures/corner` + `tools/sticker-model/holdout.json` | 0 | BUILD SUCCESSFUL; wrote `docs/evidence/10-23/kotlin-reads.json` (see the note below) |
| `./gradlew :core:domain:test --tests '*QuickScanSideFaceReplayTest*' --tests '*QuickScanGateTuningTest*' --tests '*CornerGroupIndexTest*' --tests '*LiveFixtureTest*'` | `Fixtures/corner` decoding | 0 | BUILD SUCCESSFUL; 30 tests, 0 failed, 0 skipped (11 + 2 + 5 + 12) |
| `uv run --with pillow==12.3.0 python tools/evaluate_fixture_photos.py` (the header's `pip install -r tools/requirements-photo-eval.txt` dependency, Pillow 12.3.0, through uv) | `Fixtures/labeled` photo groups | 0 | 135/216 correct (62.5%), as documented in `Fixtures/README.md`; rewrote `Fixtures/labeled/photo-evaluation-results.json` and the two overlays with no content change (line endings aside) |
| `uv run --with pillow==12.3.0 python tools/evaluate_fixture_photos.py --check` | `Fixtures/labeled` | 0 | "photo evaluation output matches" |

### Tracked outputs

- Identical to their committed copies after line-ending normalisation (`git diff --stat` empty; the tools wrote LF over
  the CRLF working copies, which `git restore` put back): `docs/spikes/oq-4-guided-scan.md` (Live baseline span),
  `docs/scan-accuracy.md`, `docs/spikes/oq-7-recognition-approach.md` and `Fixtures/labeled/photo-evaluation-results.json`.
  The two photo overlays (`photo-evaluation-overlay.jpg`, `photo-calibration-overlay.jpg`) were rewritten byte-identical.
  `evaluateQuickScan` writes only the untracked `core/domain/build/reports/quick-scan-measurement.md`.
- Different from their committed copies, on the baseline tree too (so not caused by this story):
  `docs/spikes/oq-8-recognition-matrix.md` (+36 rows: the `*_cool_guided` cells) and `docs/evidence/10-23/kotlin-reads.json`
  (894 changed lines). The difference predates this story: the committed copies
  are older than later fixtures and recognition changes (`oq-8` was last generated in `915ebdff`, 2026-11-18, before the
  cool-light matrix logs; `kotlin-reads.json` in `44ef3e8f`, 2026-11-23, before the #247 dark-cell fix `97954a84`, the 10.28
  relabelling and the 10.30-10.32 domain changes). To prove it, the same commands were run on an untouched export of the
  baseline tree (`git archive d5662d8f614948fee3639c917c6257012e6af8f5`, same `Fixtures/`): `exportKotlinStickerReads`,
  `evaluateMatrix`, `evaluateLiveBaseline`, `evaluateScanAccuracy`, `evaluateRecognitionApproaches` and `evaluateQuickScan`
  produced byte-identical outputs (line endings aside) to the post-removal run. The removal changes no reader output. Both
  files were restored with `git restore`; refreshing them is left out of this story (it is not a recorder change).

## Story 10.34 continuous-session replay (2026-11-26)

Run on branch `feat/10-34-rebuild-debug-quick-scan-recording` (based on `79704f22`) with
`export JAVA_HOME=/usr/lib/jvm/java-25-openjdk-amd64 ANDROID_HOME=$HOME/Android/Sdk`, after the synthetic sessions were
regenerated for the format additions (`geometryChanges`, `skipped`). Every command succeeded.

| Command | Fixtures read | Exit | Outcome |
| --- | --- | --- | --- |
| `./gradlew :core:domain:replayContinuousSession` | `Fixtures/continuous` (new continuous sessions) | 0 | `synthetic-complete-v1`: COMPLETE recorded and replayed, 116/130 frames with observations, 0 skipped, 6/6 sides, 54/54 stickers, 0 snapshot/cue diffs, 0 label mismatches. `synthetic-partial-v1`: PARTIAL recorded and replayed, 83/96 frames with observations, 0 skipped, 5/6 sides, 46/54 stickers (8 unknown, never filled), last cue `TurnRemaining(1)`, 0 snapshot/cue diffs, 0 label mismatches |
| `./gradlew :core:domain:test` (includes `ContinuousSessionReplayTest`, the golden state replay) | `Fixtures/continuous` | 0 | BUILD SUCCESSFUL |
| `./gradlew :core:domain:evaluateQuickScan --args="$PWD/Fixtures/matrix/lenovo-tb128xu_stka_warm_quickscan.log"` | `Fixtures/matrix` (old fixture) | 0 | BUILD SUCCESSFUL; Runs 1, successful 0, fallback faces 1, recommendation BETA (the same as the Story 10.35 row above) |

Device sessions recorded from the debug build's Quick Scan are added by the PO at the Lenovo TB128XU gate; each gets its
plain replay report under `docs/evidence/10-34/` before any `--refresh-golden` (`docs/evidence/10-34/latency.md`).
