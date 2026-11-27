# Continuous Quick Scan sessions (Story 10.34)

Each directory here is one **operator-curated** continuous Quick Scan session recorded from the **debug build's Solve-tab
Quick Scan** (its debug-only recording mode, AD-9 Frames as amended by SCP 2026-11-25 and SCP 2026-11-26). The frames come
from the same camera configuration (`ScanFrameSource`), detector, lattice fitter, sampler and stateful mapper, with the same
outlines, growing net, *Sides mapped* count and torch a Learner sees. Recording is developer tooling (AD-15): no
Learner-installable build can record a session (`check<Variant>FixtureWriter` fails any non-debuggable variant carrying a
class of the debug-only package `dev.cubentia.app.camera.session`, and those variants show no Rec control).

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

## Mapper parity sessions (#411)

`mapper-parity/` holds three **synthetic** sessions exported from the Python reference mapper `pc_spike/mapper.py`
(`_bmad-output/planning-artifacts/research/technical-continuous-facelet-scan-2026-11-24/pc_spike/`), one per
`run_spike.build_scenarios()` scenario with its config and seed unchanged:

| Session | Scenario | mapper.py outcome |
| --- | --- | --- |
| `pyspike-1-scrambled-stickered-regrip-v1/` | `1_scrambled_stickered_regrip` (regrip and merge) | COMPLETE |
| `pyspike-2-pastel-stickerless-awb-drift-v1/` | `2_pastel_stickerless_awb_drift` (AWB drift) | COMPLETE |
| `pyspike-7-mid-scan-layer-twist-u-v1/` | `7_mid_scan_layer_twist_U` (layer twist) | FAILED |

Their `frames.jsonl` holds the cell samples mapper.py itself computes for every detection (raw corner order, the
`QuadObservation` contract; no images), and their `snapshots.jsonl` holds **mapper.py's predictions**, not Kotlin goldens.
`MapperParityReplayTest` (in `:core:domain:test`) replays them through a fresh `ContinuousFaceletMapper` and requires the
per-frame differences on `net`, `globalAnchored`, `twistFrame`, `conflictedSlots`, `cue` (plus the session `outcome` and
`snapshotCount`) to equal, exactly, the list in `mapper-parity/expected-differences.json`; every entry names a deviation from
its catalogue, and the mapper's KDoc names every catalogued id (the test checks both). The test pins only the differences these
three sessions show: on them only E3 produces any (D1 only together with I401); D2, D3, D4, I401, I402 and most E ids are
catalogued but not exercised here. D1-D4, I401 and I402 are deliberate; E1-E8 were found by #411 and are not yet reviewed. `kind`, `state`, `trackedQuads`, the counts and
`pendingFragmentFaces` are not compared. So a plain replay of this folder
(`./gradlew :core:domain:replayContinuousSession --args='Fixtures/continuous/mapper-parity'`) reports diffs and exits 1:
that is expected. Never run `--refresh-golden` on it (it would overwrite the Python predictions).

The folder has no `manifest.json`, so the golden test `ContinuousSessionReplayTest` and the default replay task do not
see these sessions; `:checkFixtureSize` counts them in the folder total only.

When the test reports unlisted or stale entries, attribute each one by temporarily reverting the suspect Kotlin behaviour in
the working tree (never committed), re-running the test and restoring it. Then update the `frames` ranges, and for a new
cause add a catalogue id with its summary and a matching `**<id>**` entry in the `ContinuousFaceletMapper` KDoc.

The committed sessions were exported with opencv-python-headless 5.1.0 and numpy 2.5.5 (unpinned; another version can
change the cell samples). Regenerate (from `pc_spike/`; nothing is installed system-wide), then review the diff (only
`buildCommit` should change):

```sh
uv run --no-project --with opencv-python-headless --with numpy python export_parity_sessions.py
```

## Format (schema 1)

One neutral format (`core/domain/.../continuous/session/ContinuousSession.kt`) is used by the writer, the replay and the tests.

| File | Contents |
| --- | --- |
| `manifest.json` | Written **last**. `sessionId` (`<yyyyMMdd-HHmmss>-<device>[-<label>]`, `[a-z0-9-]`), `synthetic`, `createdAt`, `device` (model, manufacturer), `cube`, `lighting`, `partition` (`TUNING` \| `HELDOUT` \| `UNASSIGNED`, for the whole directory and everything derived from it), `model` (file, SHA-256, LiteRT version, delegate `XNNPACK`, threads 2), `buildCommit` (full hash), `frameCount`, `framesWithObservations`, `imageEvery` (N = 3), `imageFrames`, `geometry` (rotation, analysis size, crop rect, upright size, letterbox scale and pads into the 256 × 256 model input, as the detector used them on the first frame it read), `geometryChanges` (`[{frameIndex, geometry}]`, e.g. after a rotation; empty when every frame had `geometry`), `timing` (first/last sensor timestamp, duration, mean interval), `captureSkips`, `droppedFrames` (estimated from sensor-timestamp gaps), `stopReason` (`OPERATOR` \| `COMPLETE` \| `RESET` \| `FRAME_CAP` \| `SIZE_CAP`), `outcome` (`COMPLETE` \| `PARTIAL` \| `FAILED` \| `LOST_TRACKING`, derived, never chosen), `latencyP50Ms`/`latencyP95Ms`, `latencyBaselineP50Ms` (null when the device had no baseline yet), `defective` + `defectReasons`, `bytes` (frame lines, snapshot lines and PNGs), `goldenRevision` (set by `--refresh-golden`). |
| `frames.jsonl` | One line per frame the engine returned: `frameIndex`, `timestampMs` (sensor), `analyzerMs`, `inferenceMs`, `observations` (neutral `QuadObservation`s: quad, 9 cell RGBs and qualities, sharpness, glare, score; `[]` when no face was fitted), `peaks` (detected centres in model-input pixels), `image` (or null), `skipped` (true for a frame the pipeline did not read: an invalid crop or the detector unavailable; no observations and no snapshot line). |
| `snapshots.jsonl` | One line per read frame: the mapper's snapshot (kind, net as 54 URFDLB characters `W Y G B R O` / `.`, complete state, conflicts, tracked quads, anchoring, cue, counts). These are **predictions**. |
| `labels.json` | Optional operator **truth**, kept apart from predictions: 54 stickers URFDLB row-major, each `color` (null = explicit unknown) + `source` + `reviewed`, and `countCheck` (`PASSED` \| `OVERRIDDEN` + `overrideReason` \| `REFUSED`) from `StickerTruthCheck` (the Story 10.28 rule). |
| `images/frame-<idx>.png` | Every 3rd frame's detector model input: 256 × 256 RGB, lossless. |

On the device a session streams into `files/continuous-sessions/.tmp-<sessionId>/` (app-specific storage) and becomes
complete only when it is renamed to `<sessionId>/` after `manifest.json` is written. Every reader (replay, golden test;
`:checkFixtureSize` counts bytes only) ignores `.tmp-*` directories and any directory without `manifest.json`.

Budgets: the analyzer thread only copies the frame into one of 8 preallocated slots of a bounded queue; a frame that finds
no free slot is not captured and counts in `captureSkips`. JSON, PNG encoding and file I/O run on one writer thread.
Caps: 600 frames, 40,000,000 B on the device. A session is **defective** when `captureSkips > 0`, or when the device has a
no-capture baseline and the capture p50 is more than 1.10 × the baseline p50. A session saved with no baseline yet gets
`latencyBaselineP50Ms = null` and is judged on skips only.

## Operator procedure (PO step, debug build)

1. Install the debug build, open the Solve tab and start **Quick Scan** (the real entry point; there is no separate
   recorder). In the debug build the camera area shows a **Rec** control in its bottom-start corner.
2. Tap **Rec**. It clears the map (like Reset) and starts recording; a red dot reads `REC <frames> · <skips> skips` at the
   top of the camera area, and Rec reads **Stop**. Turn the cube as usual: outlines, net, count, cue and torch are the
   Learner's.
3. Recording stops when you tap **Stop**, when the scan completes (`Stopped: complete`; the sheet opens before Scan
   Review), when you tap Reset (`Stopped: reset`; the map is cleared after Save or Discard), or at a cap
   (`Stopped: frame cap` / `Stopped: size cap`). Leaving the screen (Close/Back, Scan face by face, Enter colors instead,
   Review and Correct, the background, the detector becoming unavailable) stops it first; after Review and Correct or the
   background the screen comes back to the sheet, and a screen left for good with the sheet unanswered deletes the
   `.tmp-*` directory.
4. The **session sheet** cannot be swiped away, closed by an outside tap or by Back. It shows the stop reason, frames,
   skips and MB, the capture p50/p95, the device baseline p50 (or *No baseline yet*), the derived outcome and, when it
   already applies, *Will be marked defective: …*. Fill **Cube** and **Lighting** (free text, pre-filled with the last values,
   chips `stla`/`stlb`/`stka`/`stkb` and `warm`/`low`/`cool`/`backlit`) and pick a **partition** (default `UNASSIGNED`).
5. **Run baseline (30 s)** first, once per device: the pending session is kept aside and a fresh engine with no capture runs
   on the live screen (indicator `Baseline <seconds> s`; Reset, Scan face by face, Review and Correct and Enter colors
   instead are disabled). After 30 s the per-device p50/p95 is stored in `files/continuous-sessions/.baseline-<model>.json`
   and the pending session's map, count, cue and hold come back with the sheet. Close/Back, the background or a detector
   failure end the run without storing a value.
6. **Save** writes the group and shows `Saved <sessionId>` (with ` · defective: <reasons>` when it applies) and **Label**.
   **Discard…** asks *Discard this recording?* and deletes the temp directory. For a session stopped by completion, Save
   holds the Scan Review hand-off until you dismiss the notice or close the labeller; Discard continues at once.
7. **Label** (optional) opens the full-screen labeller: six face chips, the face's net and a palette (six colours and
   **Unknown**, stored as `null`), pre-filled from the session's net (the complete export's canonical net, or the last
   confirmed net). **Save labels** stays locked until every pre-filled sticker is tapped; `StickerTruthCheck` decides, and a
   refused count (`Not saved: White 10 (over) · Blue 8 (under) · 2 unknown`) offers **Override…** (non-empty reason) or
   **Keep as refused**. Closing it returns to the notice.
8. adb mirrors the on-screen controls while Quick Scan is open (a command whose control is unavailable is ignored and
   logged under `QuickScanSession`; a save or discard sent while recording stops first). The receiver accepts only senders
   holding `android.permission.DUMP` (`adb shell` does; an installed app cannot):

   ```sh
   tools/cube-session.sh session lenovo start --cube stla --lighting warm --partition TUNING --label run1
   tools/cube-session.sh session lenovo stop          # opens the same sheet
   tools/cube-session.sh session lenovo baseline      # from the sheet only
   tools/cube-session.sh session lenovo save --lighting low   # extras override the sheet fields; or: discard
   # the same as: adb shell am broadcast -a dev.cubentia.debug.RECORDER_SESSION -p com.exxerpro.cubentia --es command start --es label run1
   adb logcat -s QuickScanSession:*
   ```

9. Pull: `tools/cube-session.sh session lenovo pull` copies `files/continuous-sessions/` (via `run-as`) into
   `build/continuous-sessions/<device>/`.
10. Curate: copy only the chosen complete session directories (never `.tmp-*`, never `.baseline-*.json`) into
    `Fixtures/continuous/`, keep or set their partition (a session and everything derived from it stay in one partition;
    held-out sessions are never used for tuning), then run `./gradlew :checkFixtureSize`. A checked-in session must stay
    ≤ 15,000,000 B; a full 600-frame capture with a PNG every 3rd frame (~200 KB each) can exceed it, so record a shorter
    session (about 200 frames or less) for check-in rather than deleting files from one.
11. Replay the curated session **plainly first** and archive its report before any golden refresh:
    `./gradlew :core:domain:replayContinuousSession --args='--session Fixtures/continuous/<id> --report docs/evidence/10-34/replay-<id>.md'`.

## Replay

State replay (neutral observations through a fresh `ContinuousFaceletMapper`; not part of `check`):

```sh
./gradlew :core:domain:replayContinuousSession                                   # every session here
./gradlew :core:domain:replayContinuousSession --args='--session Fixtures/continuous/<id> --report build/replay.md'
```

The report names the starting stage (`mapper (recorded observations)`), coverage (frames with observations, skipped
frames, sides mapped, committed stickers), the outcome, the final net (complete) or last confirmed net with unknowns as `.`
and the last cue and conflicts (partial/failed), per-frame snapshot/cue diffs (with a "not exactly replayable: N missing
frames (defective capture)" line when frame indices have gaps or the capture skipped frames), and label mismatches against
`labels.json` (unknown labels are reported, never compared; uncommitted stickers are never filled). A `skipped` frame is
not fed to the mapper and is not a gap. It exits 1 on any diff. The golden test `ContinuousSessionReplayTest` (in
`:core:domain:test`) requires zero diffs for every session here.

**Golden refresh** (the only way a mapper change is accepted; for a device session only after its plain replay report is
archived, step 11):

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
centre within 2 px (model-input pixels) after nearest matching, and the same per-sticker colour class; a candidate model's
differences are logged (`CubentiaImageReplay`), never failed.

`replayContinuousSession` replaces the Quick Scan use of `exportKotlinStickerReads`, which stays for its legacy
`Fixtures/corner` + `tools/sticker-model/holdout.json` readers (`docs/evidence/10-35/legacy-replay.md`).
