# Continuous Quick Scan sessions (Story 10.34)

Each directory here is one **operator-curated** continuous Quick Scan session recorded by the debug fixture
recorder's *Continuous session* mode (AD-9 Frames, amended SCP 2026-11-25). Sessions are developer tooling (AD-15): no
Learner-installable build can record one (`check<Variant>FixtureWriter` keeps the writer out of every non-debuggable
variant).

Size limits, enforced by `./gradlew :checkFixtureSize` (part of `check`/`build`, no Git LFS): **15,000,000 B per session
directory, 60,000,000 B for this whole folder** (decimal bytes).

## Checked-in sessions

| Session | What it is | Outcome | Partition |
| --- | --- | --- | --- |
| `synthetic-complete-v1/` | **Synthetic**, generated from `SyntheticCubeStream` scenario `1_scrambled_stickered_regrip` (130 frames, no images) | COMPLETE | UNASSIGNED |
| `synthetic-partial-v1/` | **Synthetic**, the first 96 frames of `SyntheticCubeStream` scenario `4_mid_solve_f2l_symmetric_regrip` (46 of 54 stickers committed, 8 unknown; no images) | PARTIAL | UNASSIGNED |

Both are honestly labelled `"synthetic": true` with device `synthetic (SyntheticCubeStream)`; their `labels.json` comes
from the generator's known cube state, not from an operator. They are the deterministic regression floor of state replay,
not device evidence. Regenerate them with `./gradlew :core:domain:generateSyntheticSessions` (then review the diff).
Curated Lenovo TB128XU sessions (one complete, one incomplete) are added by the PO at the human gate
(`docs/evidence/10-34/latency.md`).

## Format (schema 1)

One neutral format (`core/domain/.../continuous/session/ContinuousSession.kt`) is used by the writer, the replay and the tests.

| File | Contents |
| --- | --- |
| `manifest.json` | Written **last**. `bytes` counts frame lines, snapshot lines and PNGs (not labels or the manifest). `sessionId`, `synthetic`, `createdAt`, `device` (model, manufacturer), `cube`, `lighting`, `partition` (`TUNING` \| `HELDOUT` \| `UNASSIGNED`, for the whole directory and everything derived from it), `model` (file, SHA-256, LiteRT version, delegate `XNNPACK`, threads 2), `buildCommit` (full hash), `frameCount`, `framesWithObservations`, `imageEvery` (N = 3), `imageFrames`, `geometry` (rotation, analysis size, crop rect, upright size, letterbox scale and pads into the 256 × 256 model input), `timing` (first/last timestamp, duration, mean interval), `captureSkips`, `droppedFrames` (estimated from sensor-timestamp gaps), `stopReason` (`OPERATOR` \| `FRAME_CAP` \| `SIZE_CAP`), `outcome` (`COMPLETE` \| `PARTIAL` \| `FAILED` \| `LOST_TRACKING`, derived from the final export), `latencyP50Ms`/`latencyP95Ms`, `latencyBaselineP50Ms` (null without a baseline run), `defective` + `defectReasons`, `bytes`, `goldenRevision` (set by `--refresh-golden`). |
| `frames.jsonl` | One line per frame: `frameIndex`, `timestampMs` (sensor), `analyzerMs`, `inferenceMs`, `observations` (neutral `QuadObservation`s: quad, 9 cell RGBs and qualities, sharpness, glare, score; `[]` when no face was fitted), `peaks` (detected centres in model-input pixels), `image` (or null). |
| `snapshots.jsonl` | One line per frame: the mapper's snapshot (kind, net as 54 URFDLB characters `W Y G B R O` / `.`, complete state, conflicts, tracked quads, anchoring, cue, counts). These are **predictions**. |
| `labels.json` | Optional operator **truth**, kept apart from predictions: 54 stickers URFDLB row-major, each `color` (null = explicit unknown) + `source` + `reviewed`, and `countCheck` (`PASSED` \| `OVERRIDDEN` + `overrideReason` \| `REFUSED`) from `StickerTruthCheck` (the Story 10.28 rule). |
| `images/frame-<idx>.png` | Every 3rd frame's detector model input: 256 × 256 RGB, lossless. |

On the device, a session streams into `files/Fixtures/continuous/.tmp-<sessionId>/` and becomes complete only when it is
renamed to `<sessionId>/` after `manifest.json` is written. Every reader (replay, golden test, `:checkFixtureSize` counts
bytes only) ignores `.tmp-*` directories and any directory without `manifest.json`. Budgets: writer queue 8 frames
(a full queue skips the frame, counts `captureSkips` and marks the session defective), 600 frames, 40,000,000 B on device.
A session is **defective** when `captureSkips > 0`, or when this device model has a no-capture baseline and the capture
p50 is more than 1.10 × the baseline p50.

## Operator procedure (PO step, debug build)

1. Open the recorder: `tools/cube-session.sh recorder lenovo` (or
   `adb shell am start -n com.exxerpro.cubentia/dev.cubentia.app.camera.recorder.FixtureRecorderActivity`).
2. In *Cube and light*, tap **Continuous session…** (shown only when `QUICK_SCAN` composes the continuous engine).
   Fill in the cube and light, pick a partition chip (default `UNASSIGNED`).
3. **Baseline run** first, once per device: 30 s (or until Stop) of the same pipeline with no capture; it stores the
   no-capture p50/p95 in `files/Fixtures/continuous/.baseline-<model>.json` and writes no session.
4. **Start**, turn the cube naturally, **Stop**. The status line shows
   `Recording · frame N/600 · X.X MB · skips K · sides M/6` (plus `frame cap` / `size cap` after an automatic stop).
5. **Save** (`Saved <sessionId> · <outcome>[ · DEFECTIVE: reasons]`) or **Discard…** (confirmed; deletes the temp dir).
6. Optional: **Label stickers…** — the 54-sticker net pre-filled from the session's last net (source `prefilled`,
   unreviewed); tap a sticker, pick its colour; blanks stay explicit unknowns. **Save labels** runs `StickerTruthCheck`;
   a refusal shows the Story 10.28 text and offers **Override…** (reason required) or **Keep as refused**.

The same controls over adb while Continuous mode is open (`discard` skips the dialog):

```sh
adb shell am broadcast -p com.exxerpro.cubentia -a dev.cubentia.debug.RECORDER_SESSION --es command start --es label run1
adb shell am broadcast -p com.exxerpro.cubentia -a dev.cubentia.debug.RECORDER_SESSION --es command stop
adb shell am broadcast -p com.exxerpro.cubentia -a dev.cubentia.debug.RECORDER_SESSION --es command save      # or discard
adb shell am broadcast -p com.exxerpro.cubentia -a dev.cubentia.debug.RECORDER_SESSION --es command baseline
# or: tools/cube-session.sh session lenovo start run1
```

7. Pull: `tools/cube-session.sh pull lenovo` copies `files/Fixtures/continuous` into `build/continuous-sessions/<device>/`
   (equivalently `adb pull /sdcard/Android/data/com.exxerpro.cubentia/files/Fixtures/continuous build/continuous-sessions/`).
8. Curate: copy only the chosen complete session directories (never `.tmp-*`, never `.baseline-*.json`) into
   `Fixtures/continuous/`, keep or set their partition, then run `./gradlew :checkFixtureSize`. A checked-in session must
   stay ≤ 15,000,000 B; a full 600-frame capture with a PNG every 3rd frame (~200 KB each) can exceed it, so record a
   shorter session (about 200 frames or less) for check-in rather than deleting files from one.
9. Replay the curated session **plainly first** and archive its report before any golden refresh:
   `./gradlew :core:domain:replayContinuousSession --args='--session Fixtures/continuous/<id> --report docs/evidence/10-34/replay-<id>.md'`.
   Diffs there are a device-vs-JVM divergence (or, when the report says *not exactly replayable*, missing frames of a
   defective capture) and must be seen before `--refresh-golden` overwrites the recorded snapshots.

## Replay

State replay (neutral observations through a fresh `ContinuousFaceletMapper`; not part of `check`):

```sh
./gradlew :core:domain:replayContinuousSession                                   # every session here
./gradlew :core:domain:replayContinuousSession --args='--session Fixtures/continuous/<id> --report build/replay.md'
```

The report names the starting stage (`mapper (recorded observations)`), coverage (frames with observations, sides mapped,
committed stickers), the outcome, the final net (complete) or last confirmed net with unknowns as `.` and the last cue
and conflicts (partial/failed), per-frame snapshot/cue diffs (with a "not exactly replayable: N missing frames
(defective capture)" line when frame indices have gaps or the capture skipped frames), and label mismatches against `labels.json` (unknown labels
are reported, never compared; uncommitted stickers are never filled). It exits 1 on any diff. The golden test
`ContinuousSessionReplayTest` (in `:core:domain:test`) requires zero diffs for every session here.

**Golden refresh** (the only way a mapper change is accepted; for a device session only after its plain replay report
is archived, step 9):

```sh
./gradlew :core:domain:replayContinuousSession --args='Fixtures/continuous --refresh-golden'
```

It rewrites each session's `snapshots.jsonl` from HEAD's mapper and sets `goldenRevision` to the full HEAD hash; review
and commit the diff.

Image replay (instrumented debug test; LiteRT is Android-only):

```sh
adb push Fixtures/continuous/<id> /data/local/tmp/cubentia-10-34-sessions/<id>
adb push candidate.tflite /data/local/tmp/cubentia-10-34-candidate.tflite        # optional
./gradlew :app:connectedDebugAndroidTest -Pandroid.testInstrumentationRunnerArguments.class=dev.cubentia.app.camera.ContinuousImageReplayDeviceTest
```

With the recorded model (its SHA-256 must match the manifest) each stored PNG must give the same centre count, every
centre within 2 px (model-input pixels) after nearest matching, and the same per-sticker colour class; a candidate
model's differences are logged (`CubentiaImageReplay`), never failed.

`replayContinuousSession` replaces the continuous Quick Scan use of `exportKotlinStickerReads`, which stays for its
legacy `Fixtures/corner` + `tools/sticker-model/holdout.json` readers (`docs/evidence/10-34/legacy-replay.md`).
