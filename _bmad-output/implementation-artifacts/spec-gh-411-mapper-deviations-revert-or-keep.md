---
title: 'GitHub #411 part (b): make D1-D4, I401, I402 observable in the mapper parity replay, then revert or keep each'
type: 'feature'
created: '2026-11-27'
status: 'done'
baseline_revision: 'd0468518be02c6f2b5faca5ed56c009b986e3761'
review_loop_iteration: 0
followup_review_recommended: false
context:
  - '{project-root}/AGENTS.md'
  - '{project-root}/Fixtures/continuous/README.md'
  - '{project-root}/_bmad-output/implementation-artifacts/spec-gh-411-mapper-parity-golden-replay.md'
warnings: ['oversized']
deferred:
  - summary: >-
      The D1-D4, I401 and I402 verdicts rest on one synthetic evidence session each; they were not re-checked on a real Quick Scan or real clips.
    evidence: |-
      The PO limited part (b) to synthetic scenarios and no device or camera was available. No mapper behaviour changed (all six kept, comment-only Kotlin diff), so the issue's Done-when owes no device check. An optional real-clip or device confirmation pairs with #413 (phone-clip A/B). Raised by the intent-alignment review, not by the issue.
    location: >-
      Fixtures/continuous/mapper-parity/expected-differences.json
    severity: low
---

<intent-contract>

## Intent

**Problem:** Part (a) (PR #427) added `MapperParityReplayTest`, which replays three synthetic sessions exported from `pc_spike/mapper.py` through `ContinuousFaceletMapper` and pins their per-frame differences. But none of the six named deviations (D1 twisted-line observations dropped and AWB step withheld, D2 joint log-AWB scaling, D3 merge AWB rescale, D4 AWB-compensated contradiction guard, I401 sticker-colour twist guard, I402 per-cell cap) changes a compared field in those sessions, so reverting any of them leaves the test green, and none has evidence that it is better or worse than mapper.py (action item `issue-411-deferred-parity-sessions-miss-d1-d4`).

**Approach:** Add synthetic mapper.py-exported parity sessions, at least one per deviation, built so that each deviation changes at least one compared field. Then for each of D1-D4 decide by a fixed truth-based rule: keep it (one-line reason in code, verdict and evidence in the catalogue, and a test that checks the kept deviation is not worse than mapper.py against the scenario's truth labels) or revert it to mapper.py's behaviour. I401 and I402 are deliberate issue fixes and get the same not-worse evidence. Confirm the KDoc lists every intentional deviation and its verdict.

## Boundaries & Constraints

**Always:**
- Synthetic data only: frames from `pc_spike/synth_video.py` `generate_sequence` (new `ScenarioConfig`s and/or deterministic synthetic post-processing of its frames, such as a stronger per-channel colour cast, a cast that changes during a regrip blackout, spliced frames from a twisted copy of the same sequence, or longer holds). Never real webcam or phone data.
- The new exporter is `tools/mapper-parity/export_deviation_sessions.py`. It imports the pc_spike modules (`sys.path` to the pc_spike dir) and reuses `export_parity_sessions.export_scenario` and the session format unchanged (it may substitute the module-level `generate_sequence` it calls). Run Python only as `uv run --no-project --with opencv-python-headless --with numpy python <script>`; nothing is installed system-wide. Output is deterministic (fixed seeds): a re-run is byte-identical except `buildCommit`.
- New session ids match `pyspike-[a-z0-9]+-[a-z0-9-]+-v1` and name the deviation (for example `pyspike-d2-strong-cast-v1`); they sit in `Fixtures/continuous/mapper-parity/` next to the three part (a) sessions, which stay byte-identical. Each session directory is at most 15,000,000 B and `Fixtures/continuous/` stays under 60,000,000 B.
- "Bites" means: with the Kotlin behaviour as committed, the session has at least one compared-field difference attributed to that id in `expected-differences.json`, and temporarily switching that one behaviour to the other side (kept: to mapper.py's; reverted: back to the old Kotlin behaviour) in the working tree changes the observed compared-difference set of that session. Attribution is by temporary revert in the working tree only, never committed; record the result in the catalogue `note`.
- Decision rule for D1-D4, judged on each of the deviation's evidence sessions from the last snapshot against `labels.json` truth: `wrong` = committed stickers that differ from truth; `correct` = the session outcome equals the scenario's true outcome (`FAILED` when the scenario has a real layer twist, else `COMPLETE`). Kotlin is **not worse** when `kotlinWrong <= pythonWrong` and `pythonCorrect` implies `kotlinCorrect`, on every evidence session. Not worse (including a tie) → **keep**. Worse on any evidence session → **revert** the Kotlin behaviour to mapper.py's (`mapper.py` line refs in the Code Map) and re-check.
- Each catalogue entry for D1-D4, I401, I402 gets `verdict` (`keep` | `reverted`), `reason` (one line) and `evidence` (list of `{session, trueOutcome}`), and its `note` gives the measured `wrong`/`correct` values and the attribution result. A kept deviation's code site carries a one-line `// Kept (GH #411 <id>): <reason>` comment; a reverted one's site says it now matches mapper.py.
- Every new compared-field difference in a new session goes into `differences`, attributed to its first cause (existing E ids where they apply); a new cause gets its own catalogued id and a `**<id>**` KDoc entry.
- `./gradlew spotlessCheck build` passes.
- If an id cannot be made to bite with synthetic generation after a documented search, do not exempt it in the test: leave it out of the bite check only by reporting it as incomplete, with every attempted scenario and its measured effect, in the implementation report.

**Never:**
- Do not edit anything under `_bmad-output/planning-artifacts/` (including `pc_spike/mapper.py`, `export_parity_sessions.py`, `run_spike.py`, `synth_video.py`), `_bmad/`, `.claude/`, `sprint-status.yaml`.
- Do not change E1-E8 behaviour, and do not touch the bugs tracked as #432, #433, #437 or anything from Story 10.37, even where they share code with the mapper.
- Do not loosen `MapperParityReplayTest` into a tolerance or a count: differences stay an exact (session, frame, field) set.
- Do not change `ContinuousSessionReplayTest` expectations or the `synthetic-*-v1` sessions unless a D1-D4 revert changes them; then regenerate them with `./gradlew :core:domain:generateSyntheticSessions` and say so in the catalogue note.
- Never put close, fix or resolve directly before an issue number in code, docs or commits; write `Refs #N` or `(#N)`.

## I/O & Edge-Case Matrix

| Scenario | Input / State | Expected Output / Behavior | Error Handling |
|----------|--------------|---------------------------|----------------|
| Parity holds | all parity sessions and the expected list as committed | `MapperParityReplayTest` passes | — |
| Deviation bites | any of D1-D4, I401, I402 switched to the other side in the working tree | the parity test fails (unlisted or stale entries for that id's session) | — |
| Kept and not worse | verdict `keep`, Kotlin final net and outcome vs truth | not-worse check passes for each evidence session | fails naming id, session, both `wrong` counts and outcomes |
| Kept but worse | a kept id whose Kotlin result is worse than mapper.py's | not-worse check fails | message names id and session |
| Missing verdict | D1-D4, I401 or I402 without `verdict`, `reason` or `evidence`, or an evidence session that does not exist | test fails | — |
| Kept with no bite | verdict `keep` but no listed difference cites the id in any of its evidence sessions | test fails | — |

</intent-contract>

## Code Map

- `core/domain/src/main/kotlin/dev/cubentia/domain/recognition/continuous/ContinuousFaceletMapper.kt` -- class KDoc deviations list (~35-66; update the summary paragraph that says only E3 bites and that part (b) decides, add each verdict); D1 `lineTwistedFaces` and the withheld AWB step (~798-891); D2 `normalizeLogAwbInPlace` (~1528-1540); D3 merge AWB in `tryMergePending` (~952, log-AWB add and renormalise ~1086); D4 contradiction guard in `attachBySharedEdge` (~665+); I401 `isStickerColour` / `twistStickerPrototypes` (~852, ~934, ~1523); I402 `MAX_OBS_PER_CELL` (33) and `addObs` (~148-170).
- `.../pc_spike/mapper.py` (read-only) -- D2 per-channel clip 594-599; AWB scale 601; twist check and observation recording 603-662 (D1, I401 counterparts: `de_all > 33`, glare wash-out, streak 5); D4 raw-Lab guard 549-552; merge `_try_merge_pending` 673+ (D3 counterpart); `_center_of`, `face_robust_lab`.
- `.../pc_spike/export_parity_sessions.py` (read-only) -- reuse `export_scenario(index, cfg, out_root, commit)`, `build_commit`; it calls the module-global `generate_sequence(cfg, WIDTH, HEIGHT)`, so a wrapper can be substituted there for frame post-processing.
- `.../pc_spike/synth_video.py` (read-only) -- `ScenarioConfig` (awb_drift, regrip_range/regrip_return_euler, twist_at_frame/twist_move, finger_occlusion, n_frames, palette, stickerless, seed); AWB drift is the `awb_rgb` multiply (~264-299); `SyntheticFrame(frame_idx, ts, image_bgr, detections, truth)`. `run_spike.build_scenarios()` and `apply_scramble` for states.
- `core/domain/src/test/kotlin/dev/cubentia/domain/recognition/evaluation/continuous/MapperParityReplayTest.kt` -- extend: session count 3 → 3 + new, id regex, verdict/evidence parsing, not-worse check, kept-has-bite check.
- `core/domain/src/evaluation/kotlin/.../evaluation/continuous/ContinuousSessionReplay.kt` -- `replay(dir)` `Report` (diffs, labelMismatches); reuse it, or replay frames through a fresh `ContinuousFaceletMapper` in the test to get Kotlin's last snapshot.
- `Fixtures/continuous/mapper-parity/expected-differences.json` -- catalogue + differences; add verdict fields and the new sessions' differences.
- `core/domain/src/test/kotlin/dev/cubentia/domain/recognition/continuous/ContinuousFaceletMapperTest.kt` -- has unit tests touching `normalizeLogAwbInPlace` / `MAX_OBS_PER_CELL` / `isStickerColour`; update only if a revert changes the pinned behaviour.
- `Fixtures/continuous/README.md` -- mapper parity section: list the new sessions, the second exporter command, the verdict rule.

## Tasks & Acceptance

**Execution:**
- `tools/mapper-parity/export_deviation_sessions.py` -- new exporter of the deviation scenarios (argparse `--out`, default repo `Fixtures/continuous/mapper-parity`; docstring names each scenario, what it does to the frames and which id it targets) -- the Python half of the new parity pairs.
- `Fixtures/continuous/mapper-parity/pyspike-<id>-*-v1/` -- the exported sessions, committed; iterate scenario design until each of D1-D4, I401, I402 bites (export, replay with the test or `./gradlew :core:domain:replayContinuousSession --args='<dir>'`, temporarily revert the Kotlin behaviour, compare).
- `Fixtures/continuous/mapper-parity/expected-differences.json` -- new differences; `verdict`/`reason`/`evidence` on D1-D4, I401, I402; notes with measurements and attribution.
- `core/domain/src/main/kotlin/.../continuous/ContinuousFaceletMapper.kt` -- apply the verdicts (revert code only where the rule says worse); one-line reason comments; KDoc deviations list and summary paragraph updated so every intentional deviation and its verdict is named.
- `core/domain/src/test/kotlin/.../evaluation/continuous/MapperParityReplayTest.kt` -- new checks per the I/O matrix.
- `Fixtures/continuous/README.md` -- document the new sessions, exporter command and rule.

**Acceptance Criteria:**
- Given the committed sessions, when `./gradlew :core:domain:test --tests '*MapperParityReplayTest*'` runs, then it passes, including the not-worse check for every kept id.
- Given each of D1-D4, I401, I402, when its Kotlin behaviour is temporarily switched to the other side, then `MapperParityReplayTest` fails (recorded per id in the catalogue note).
- Given a clean checkout, when both exporters are re-run with the uv command, then `git status` shows no change in `Fixtures/continuous/mapper-parity/` except `buildCommit`.
- Given the mapper KDoc, when read, then every catalogued id is named with what differs from mapper.py, and D1-D4, I401, I402 each state their verdict.

## Spec Change Log

## Review Triage Log

### 2026-11-27 — Review pass
- verdicts: 17 findings — high 0, medium 0, low 13, false 4, maybe-false 0
- findings:
  - `[low]` `[reject]` (blind) Evidence `trueOutcome` is not checked against the manifest or labels — the manifest outcome is mapper.py's, labels cannot encode a twist; a cross-check needs a new truth field. Unlikely to be met, fix adds mechanism.
  - `[low]` `[reject]` (blind) A tie between two failures (D4: 3 wrong, PARTIAL each) still keeps the deviation — this is the spec's "not worse" rule; a tie is not worse.
  - `[low]` `[reject]` (blind) I402 is scored on the final snapshot only — per-frame differences stay pinned exactly by the parity test; the rule scores the end state by design.
  - `[low]` `[reject]` (blind) The bite test does not re-run the attribution revert — attribution by temporary working-tree revert is the spec's method; spot-checked here (per-channel clip for D2 made the parity test fail: stale D2 entries at pyspike-d2 frame 116 plus one unlisted pyspike-1 entry).
  - `[low]` `[reject]` (blind) Duplicate evidence entries accepted — no duplicates exist; the fix adds a guard.
  - `[low]` `[reject]` (blind) README does not say how a `reverted` verdict is tested — none is reverted; record-only today.
  - `[low]` `[reject]` (blind) E1-E8 still not reviewed — out of scope by the intent (only the four deviations are decided).
  - `[low]` `[reject]` (blind) D4 note does not name the three wrong stickers — cosmetic; the counts are what the rule uses.
  - `[false]` `[reject]` (blind) I402 reason (time/heap) unsupported by this evidence — the resource benefit was measured by #402; this run only shows the cap is not worse on output.
  - `[low]` `[patch]` (blind) Exporter docstring claims byte-identical re-runs although opencv/numpy are unpinned — docstring now qualifies it with the versions used.
  - `[false]` `[reject]` (verification-gap) JUnit XML shows two MapperParityReplayTest failures — that XML came from the reviewer-side D2 attribution run (temporary revert); after restore the test passes 10/10 and `spotlessCheck build` passes.
  - `[low]` `[reject]` (intent) Evidence only on sessions built for each deviation, not neutral inputs — the part (a) sessions are the neutral inputs and show no difference from these ids (catalogue notes), so Kotlin equals mapper.py there.
  - `[false]` `[reject]` (intent) Not-worse check ignores cross-session effects — the cited effect (reverting D1 misses pyspike-7's twist) is from reverting; the kept behaviour matches mapper.py's net/outcome on all part (a) sessions.
  - `[low]` `[reject]` (intent) Attribution not machine-checked — same as the blind row.
  - `[low]` `[defer]` (intent) No device/real-clip confirmation recorded — deferred (low): not owed by the issue since no behaviour changed; optional pairing with #413.
  - `[false]` `[reject]` (intent) The not-worse rule is the implementer's own — it is the spec's rule, matching the intent's "evidence it is not worse".
  - `[low]` `[patch]` (intent) KDoc summary says only E3 produces differences, omitting part (b) E3 and E8 differences — reworded (comment only).

## Design Notes

Why truth labels decide: mapper.py is the validated reference, but on synthetic inputs both mappers can be scored against the generator's known cube state, so "not worse" is measurable without real clips. Ties keep the Kotlin behaviour (no churn in a path that devices already exercise).

Example catalogue entry shape:
```json
{"id": "D2", "deliberate": true, "summary": "...", "verdict": "keep",
 "reason": "joint scaling keeps the cast's chromaticity direction when one channel saturates the bound",
 "evidence": [{"session": "pyspike-d2-strong-cast-v1", "trueOutcome": "COMPLETE"}],
 "note": "Kotlin wrong 0 / COMPLETE, mapper.py wrong 3 / PARTIAL; per-channel clip in Kotlin removes the 6 cue+net diffs."}
```

## Verification

**Commands:**
- `cd _bmad-output/planning-artifacts/research/technical-continuous-facelet-scan-2026-11-24/pc_spike && uv run --no-project --with opencv-python-headless --with numpy python export_parity_sessions.py && cd - && uv run --no-project --with opencv-python-headless --with numpy python tools/mapper-parity/export_deviation_sessions.py && git status --short Fixtures/continuous/mapper-parity` -- expected: only `buildCommit` lines differ from the committed sessions (after the first commit of the new ones).
- `export JAVA_HOME=/usr/lib/jvm/java-25-openjdk-amd64 ANDROID_HOME=$HOME/Android/Sdk && ./gradlew :core:domain:test --tests '*MapperParityReplayTest*' --no-daemon` -- expected: pass.
- `./gradlew spotlessCheck build --no-daemon` -- expected: BUILD SUCCESSFUL.

## Auto Run Result

Status: done

**Summary:** Six synthetic mapper.py-exported parity sessions (one per D1, D2, D3, D4, I401, I402) make each deviation change compared fields in `MapperParityReplayTest`; switching any one back to mapper.py's behaviour fails the test. By the truth-label rule (no more wrong stickers, true outcome whenever mapper.py reaches it) Kotlin is never worse, so all six are kept with a one-line `// Kept (GH #411 <id>)` reason; none reverted. D1, D3, I401 are better than mapper.py (D1 catches an early twist mapper.py misses; D3 completes where mapper.py commits nothing; I401 avoids mapper.py's false twist); D2, D4, I402 tie. The KDoc names every catalogued id with its verdict.

**Files changed:**
- `tools/mapper-parity/export_deviation_sessions.py` — new exporter reusing part (a)'s `export_scenario` with deterministic synthetic frame edits.
- `Fixtures/continuous/mapper-parity/pyspike-{d1,d2,d3,d4,i401,i402}-*-v1/` — six new sessions (folder total ~2.8 MB).
- `Fixtures/continuous/mapper-parity/expected-differences.json` — new differences; verdict, reason, evidence and measured notes for the six ids.
- `core/domain/src/test/kotlin/.../evaluation/continuous/MapperParityReplayTest.kt` — 9 sessions; verdict completeness, kept-bites and not-worse checks plus rule self-tests (10 tests).
- `core/domain/src/main/kotlin/.../continuous/ContinuousFaceletMapper.kt` — comments/KDoc only.
- `Fixtures/continuous/README.md` — new sessions, second exporter command, decision rule.

**Review:** 17 findings; 2 patched (low), 1 deferred (low: optional real-clip/device confirmation, pairs #413), 14 rejected with reasons above. Patched by verdict: high 0, medium 0, low 2.

**Follow-up review recommended:** false (no high or medium patched).

**Verification:** new exporter re-run byte-identical; `MapperParityReplayTest` 10/10; D2 attribution spot-check (per-channel clip) fails the test as expected and was restored; `./gradlew spotlessCheck build --no-daemon` exit 0; mapper diff has no non-comment line.

**Residual risks:** each verdict rests on one designed scenario; D2, D4 and I402 are ties; I401 can also hide a real early twist in a variant not committed (mapper.py misses it too) — worth an issue if the PO wants.
