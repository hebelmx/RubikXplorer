# Story 10.34: Quick Scan session recording latency (Lenovo TB128XU)

Recording a session from the debug build's Solve-tab Quick Scan must not raise the analyzer p50 by more than 10% (Epic 10
latency requirement), and **a non-defective Lenovo session is part of the human gate**. This page holds the method, the
measurement table and the gate checklist. **No device numbers are recorded yet: every value below is a PO step.**

## Method

1. Same device (Lenovo TB128XU, the gating minimum), same debug build, same cube and light, from the Solve tab's Quick
   Scan (the real entry point).
2. **Baseline** (no capture): tap Rec, Stop, then **Run baseline (30 s)** on the sheet. A fresh engine with no capture runs
   on the live screen, timed exactly like a recording (`engine.processFrame` on the analyzer thread, `System.nanoTime` in
   `ScanFrameSource.read`), and stores the device's p50/p95 in `files/continuous-sessions/.baseline-<model>.json`.
3. **Capture session**: Rec, turn the cube naturally, Stop (or let it complete), Save. The manifest records `latencyP50Ms`,
   `latencyP95Ms` (the same measurement, which then includes the tap's copy into its slot), `latencyBaselineP50Ms`,
   `captureSkips` and `droppedFrames`.
4. A session is **defective** when `captureSkips > 0` or `latencyP50Ms > 1.10 × latencyBaselineP50Ms` (`defective` +
   `defectReasons` in the manifest; the notice shows `Saved <id> · defective: …`). Without a baseline it is judged on
   skips only (`latencyBaselineP50Ms = null`).
5. Read the numbers from the manifests and the baseline file; do not estimate.

## Measurements

| Device | Build commit | Run | Frames | p50 (ms) | p95 (ms) | Capture skips | Dropped (est.) | p50 vs baseline | Verdict |
| --- | --- | --- | ---: | ---: | ---: | ---: | ---: | --- | --- |
| Lenovo TB128XU | PO step | Baseline (no capture) | PO step | PO step | PO step | — | — | — | — |
| Lenovo TB128XU | PO step | Capture session (complete) | PO step | PO step | PO step | PO step | PO step | PO step | PO step |
| Lenovo TB128XU | PO step | Capture session (incomplete) | PO step | PO step | PO step | PO step | PO step | PO step | PO step |

## Human-gate checklist (PO, after the code lands, before merge)

- [ ] Install the debug build on the Lenovo TB128XU and open Quick Scan from the Solve tab.
- [ ] Record once and run the 30 s baseline from the sheet; note the stored p50/p95.
- [ ] Record sessions; the PO picks **one complete** and **one incomplete** (partial, failed or lost-tracking) session, at
      least one of them **not defective**; label them from the notice (an override needs a reason).
- [ ] Pull (`tools/cube-session.sh session lenovo pull`), copy the two chosen session directories into
      `Fixtures/continuous/`, set their partition, run `./gradlew :checkFixtureSize` (≤ 15,000,000 B per session: record
      about 200 frames or less rather than trimming files out of a session).
- [ ] **Plain replay first** (no refresh): `./gradlew :core:domain:replayContinuousSession --args='--session Fixtures/continuous/<id> --report docs/evidence/10-34/replay-<id>.md'`
      for each, keeping the reports. Diffs here mean the JVM mapper diverged from the device's recorded snapshots (or,
      with missing frames, a defective capture).
- [ ] Only then, if a refresh is accepted: `./gradlew :core:domain:replayContinuousSession --args='Fixtures/continuous --refresh-golden'`,
      then `./gradlew :core:domain:replayContinuousSession` shows 0 diffs and `:core:domain:test` passes.
- [ ] Image replay: push both sessions to `/data/local/tmp/cubentia-10-34-sessions/` and run
      `ContinuousImageReplayDeviceTest` (recorded model: count, ≤ 2 px, colour; optional candidate model reported).
- [ ] Fill the table above from the manifests and the baseline file, with the verdict (defective or not) the rule gives.
