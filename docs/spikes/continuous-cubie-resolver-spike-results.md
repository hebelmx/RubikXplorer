# Continuous Quick Scan Spike Report: CubieInvariantResolver (2026-11-27)

- **Status:** Parked / Deferred (re-evaluate once real Android device sessions land in `Fixtures/continuous/` in Story 10.36)
- **Branch:** `spike/cubie-invariant-resolver-main` (synced to `origin/main` `f4114a41`)
- **Command to re-run on new recordings:**
  ```sh
  ./gradlew :core:domain:test --tests "dev.cubentia.domain.recognition.evaluation.continuous.ContinuousCubieResolverSpikeTest" --rerun-tasks
  ```
- **Caveat on current dataset:** All 11 sessions currently in `Fixtures/continuous/` are synthetic / PC Logitech-webcam-derived (`SyntheticCubeStream` and `mapper-parity/`), not physical Android device captures (Galaxy S24 FE / Lenovo TB128XU). Real phone/tablet sessions with harsher mobile lighting, glare, or occlusion may stall at 50–53/54 with 6 mapped faces and 0 conflicts — re-run this spike test on those sessions before deciding whether to promote this into a story.

## Results on Current 11 Sessions (`Fixtures/continuous/`)

Mode A — Strict Gate (`globalAnchored && mappedFaces == 6 && conflictedSlots.isEmpty() && invariantViolations == 0`):

| Session | Baseline Outcome | Native Complete Frame | First Resolved Frame (unknowns) | Frames Saved | Wrong Resolved Frames | End-of-Session Outcome | Max Latency (ms) |
|---|---|---|---|---|---|---|---|
| `mapper-parity/pyspike-1-scrambled-stickered-regrip-v1` | COMPLETE (54/54) | frame 98 | - | - | 0 | COMPLETE | 0.00 |
| `mapper-parity/pyspike-2-pastel-stickerless-awb-drift-v1` | COMPLETE (54/54) | frame 107 | - | - | 0 | COMPLETE | 0.00 |
| `mapper-parity/pyspike-7-mid-scan-layer-twist-u-v1` | FAILED (3/54) | - | - | - | 0 | FAILED | 0.00 |
| `mapper-parity/pyspike-d1-early-twist-u-v1` | FAILED (3/54) | - | - | - | 0 | FAILED | 0.00 |
| `mapper-parity/pyspike-d2-strong-cast-v1` | COMPLETE (54/54) | frame 117 | - | - | 0 | COMPLETE | 0.00 |
| `mapper-parity/pyspike-d3-regrip-cast-drift-v1` | COMPLETE (54/54) | frame 96 | - | - | 0 | COMPLETE | 0.00 |
| `mapper-parity/pyspike-d4-pastel-strong-cast-v1` | PARTIAL (47/54) | - | - | - | 0 | PARTIAL (conflicts=7, unread=7) | 22.64 |
| `mapper-parity/pyspike-i401-skin-occluder-v1` | COMPLETE (54/54) | frame 117 | frame 116 (8) | 1 | 0 | COMPLETE | 4.30 |
| `mapper-parity/pyspike-i402-long-hold-v1` | COMPLETE (54/54) | frame 168 | frame 423 (1) | -255 | 0 | COMPLETE | 0.50 |
| `synthetic-complete-v1` | COMPLETE (54/54) | frame 98 | - | - | 0 | COMPLETE | 0.00 |
| `synthetic-partial-v1` | PARTIAL (46/54) | - | - | - | 0 | PARTIAL (conflicts=0, unread=8) | 0.00 |

## Summary & Decision
- **Total `resolveNet` evaluations:** 73; **worst-case latency:** 22.64 ms (on `pyspike-d4-pastel-strong-cast-v1` with 7 active conflicts).
- **Safety:** **0** wrong resolved frames across all 11 sessions in both strict and conflict-allowing modes.
- **Gain on current synthetic/PC dataset:** **0** partial sessions rescued; **1** frame saved (`pyspike-i401`). Not worth wiring into production until real device recordings (Story 10.36) demonstrate a stalled 6-face / 0-conflict failure mode that this rescues.
