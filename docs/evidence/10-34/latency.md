# Story 10.34 — recorder capture latency (Lenovo TB128XU)

Story 10.34's recorder must not raise the analyzer p50 by more than 10% when it captures (Epic 10 latency requirement).
This page holds the method, the measurement table and the human-gate checklist. **No numbers are recorded yet: every
device value below is a PO step.**

## Method

1. Same device (Lenovo TB128XU, the gating minimum), same debug build, same cube and light, recorder in *Continuous
   session* mode.
2. **Baseline run** (no capture): the recorder runs the Solve-tab continuous pipeline for 30 s (or until Stop) with no
   frame tap and stores the analyzer p50/p95 in `files/Fixtures/continuous/.baseline-<model>.json`.
3. **Capture session**: Start, turn the cube naturally, Stop, Save. The manifest records `latencyP50Ms`,
   `latencyP95Ms` (per-frame analyzer time with capture, the same measurement as the baseline: `engine.processFrame` on
   the analyzer thread, which includes the tap's copy), `latencyBaselineP50Ms`, `captureSkips` and `droppedFrames`.
4. A session is **defective** when `captureSkips > 0` or `latencyP50Ms > 1.10 × latencyBaselineP50Ms`
   (`defective` + `defectReasons` in the manifest; the Save status line shows `DEFECTIVE: …`).
5. Read p50/p95 from the two manifests / the baseline file; do not estimate.

## Measurements

| Device | Run | Frames | p50 (ms) | p95 (ms) | Capture skips | Dropped (est.) | p50 vs baseline | Defective |
| --- | --- | ---: | ---: | ---: | ---: | ---: | --- | --- |
| Lenovo TB128XU | Baseline (no capture) | PO step: pending Lenovo TB128XU | PO step: pending Lenovo TB128XU | PO step: pending Lenovo TB128XU | — | — | — | — |
| Lenovo TB128XU | Capture session (complete) | PO step: pending Lenovo TB128XU | PO step: pending Lenovo TB128XU | PO step: pending Lenovo TB128XU | PO step: pending | PO step: pending | PO step: pending | PO step: pending |
| Lenovo TB128XU | Capture session (incomplete) | PO step: pending Lenovo TB128XU | PO step: pending Lenovo TB128XU | PO step: pending Lenovo TB128XU | PO step: pending | PO step: pending | PO step: pending | PO step: pending |

## Human-gate checklist (PO, after the code lands, before merge)

- [ ] Install the debug build on the Lenovo TB128XU; open the recorder (`tools/cube-session.sh recorder lenovo`) and
      choose **Continuous session…**.
- [ ] Baseline run (30 s); note the stored p50/p95.
- [ ] Record at least one session; PO picks **one complete** and **one incomplete** (partial, failed or lost-tracking)
      session; label them (optional override needs a reason).
- [ ] Pull (`tools/cube-session.sh pull lenovo`), copy the two chosen session directories into `Fixtures/continuous/`,
      set their partition, run `./gradlew :checkFixtureSize`. A checked-in session must stay ≤ 15,000,000 B: a full
      600-frame capture with a PNG every 3rd frame (~200 KB each) can exceed it, so record a shorter session (about
      200 frames or less) rather than trimming files out of one.
- [ ] **Plain replay first** (no refresh): for each curated device session run
      `./gradlew :core:domain:replayContinuousSession --args='--session Fixtures/continuous/<id> --report docs/evidence/10-34/replay-<id>.md'`
      and keep the report (diffs or none) under `docs/evidence/10-34/`. Diffs here mean the JVM mapper diverged from the
      device's recorded snapshots (or, with missing frames, a defective capture); record them before anything else.
- [ ] Only then, if a refresh is accepted: `./gradlew :core:domain:replayContinuousSession --args='Fixtures/continuous --refresh-golden'`,
      then `./gradlew :core:domain:replayContinuousSession` shows 0 diffs and `:core:domain:test` passes.
- [ ] Image replay: push both sessions to `/data/local/tmp/cubentia-10-34-sessions/` and run
      `ContinuousImageReplayDeviceTest` (recorded model: count, ≤ 2 px, colour; optional candidate model reported).
- [ ] Fill the table above from the manifests and baseline file; mark a session defective if the rule says so.
