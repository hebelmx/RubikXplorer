---
title: 'GitHub #411: mapper.py vs ContinuousFaceletMapper golden parity replay (part a, synthetic)'
type: 'feature'
created: '2026-11-27'
status: 'done'
baseline_revision: 'a541ee5ed4e419045e35a75fdac507b134a482b3'
review_loop_iteration: 0
followup_review_recommended: false
context:
  - '{project-root}/AGENTS.md'
  - '{project-root}/Fixtures/continuous/README.md'
warnings: ['oversized']
deferred:
  - summary: >-
      The parity sessions exercise none of D1-D4, I401, I402 on their own (only E3 yields compared diffs), so reverting them still passes MapperParityReplayTest.
    evidence: |-
      expected-differences.json notes: log-AWB peaks at 0.315 (< 0.36 bound), the only merge has |log_awb| < 0.02, longest per-cell list 84 (< 256), reverting D4/I401 alone changes nothing, D1 and I401 cancel. Scenarios that make each deviation bite (strong AWB drift, merge with large AWB offset, forced conflict, long scan) belong with part (b) revert-or-keep evidence.
    location: >-
      Fixtures/continuous/mapper-parity/expected-differences.json
    severity: medium
---

<intent-contract>

## Intent

**Problem:** `ContinuousFaceletMapper` claims to be a port of `pc_spike/mapper.py` but deviates in at least four undocumented places (issue #411 table: D1 twisted-line observations dropped + AWB step withheld; D2 AWB bounded by joint scaling instead of per-channel clip; D3 pending merge adds pending AWB into master and rescales merged `rgbAwb`; D4 shared-edge contradiction guard uses AWB-compensated Lab) plus the #401 sticker-colour twist guard and the #402 per-cell observation cap. No test feeds the same input to both, so any drift is invisible.

**Approach:** A Python exporter in `pc_spike/` renders 3 synth_video scenarios, records the exact per-quad cell samples mapper.py itself computes as Story 10.34 neutral observations (`frames.jsonl`) and mapper.py's per-frame snapshots (`snapshots.jsonl`), under `Fixtures/continuous/mapper-parity/<id>/`. A Kotlin JVM test replays each through a fresh `ContinuousFaceletMapper` via `ContinuousSessionReplay.replay` and requires the set of per-frame differences on the compared fields to equal, exactly, a committed expected-difference list whose every entry names a catalogued deviation. KDoc on the mapper names every deviation.

## Boundaries & Constraints

**Always:**
- Run Python only as `uv run --no-project --with opencv-python-headless --with numpy python <script>` from `pc_spike/`; never install packages system-wide.
- Synthetic data only: scenarios `1_scrambled_stickered_regrip` (regrip/merge: D3), `2_pastel_stickerless_awb_drift` (AWB: D2), `7_mid_scan_layer_twist_U` (twist: D1, #401) from `run_spike.build_scenarios()`, unchanged configs and seeds.
- Observations are the values mapper.py sees: for every detection of a frame (unfiltered, original corner order), `_sample_face_cells(image_bgr, det.corners)` gives `cellRgbs` (raw corner order, which equals the Kotlin `QuadObservation` contract), `cellQualities`, `sharpness`, `hasGlare`; `score` = `det.score`; full float precision; `peaks: []`, `image: null`, `skipped: false`.
- Python snapshots are mapped faithfully into the schema-1 `SessionSnapshot` shape: net as 54 chars URFDLB (`W Y G B R O`, `.`), `conflictedSlots` as `U4`-style sorted, `globalAnchored`, `twistFrame` (`twist_frame` or null), `kind` `TWIST_DETECTED` when twist else `SCANNING` (mapper.py has no validated complete state; `state` null), cue string mapped 1:1 to `SessionCue` types (`Bring cube into view`→BringIntoView, `Turn cube to show remaining N side(s)`→TurnRemaining(N), `Tilt slightly away from light (N side(s) left)`→TiltAwayLightRemaining(N), `Tilt slightly away from light for clearer view`→TiltAwayLight, `Tilt slightly to show an adjacent face`→TiltAdjacent, `Keep turning to re-align…`→KeepTurningRealign, `Show face F clearly…`→ResolveConflict(F), `All 54 stickers verified!`→AllVerified, `Tilt remaining faces toward camera (n/54)`→TiltRemainingStickers(n), `Layer turned…`→LayerTwistDetected); an unmapped cue string fails the export. Counts and `pendingFragmentFaces` from the snapshot; `mappedFacesCount` = `len(master.confirmed_faces())` (0 without master); `trackedQuads` from `tracked_quads` (`face` null for `?`-prefixed local labels).
- Manifest: schema 1, `synthetic: true`, device `synthetic (pc_spike synth_video.py)`, partition `UNASSIGNED`, `cube` names scenario + seed, `buildCommit` = full `git rev-parse HEAD`, frame/timing counts from the frames, `outcome` derived from mapper.py's last snapshot with the `SessionOutcome.derive` rules (twist→FAILED, 54 committed with 0 invariant violations and no conflicts→COMPLETE, last frame tracks no quad→LOST_TRACKING, else PARTIAL). `labels.json` from the scenario's truth state (`source` synthetic, count check PASSED), like the existing synthetic sessions.
- Compared fields: `net`, `globalAnchored`, `twistFrame`, `conflictedSlots`, `cue`, plus the frame -1 `outcome` and `snapshotCount` diffs. Not compared (documented as such): `kind`, `state`, `trackedQuads`, counts, `pendingFragmentFaces`.
- Every observed diff goes into the list, attributed to its first cause; causes beyond the six named are catalogued with their own id (e.g. Kotlin's early return after a twist, `AllVerified` checked before `BringIntoView`, cue sides counted from confirmed faces, `hasPrevUnattached` reset on an empty frame, the extra `tracked.isEmpty()` pending reset, convexity/finite filters) and named in the KDoc. Attribution is established by temporarily reverting one Kotlin behaviour in the working tree, regenerating the diff, and restoring it (never committed).
- `./gradlew spotlessCheck build` passes; `:checkFixtureSize` total stays under 60,000,000 B; each parity session directory ≤ 15,000,000 B.

**Never:**
- No change to mapper behaviour in `ContinuousFaceletMapper.kt` or in `mapper.py` / other pc_spike files (part (b), revert-or-keep, is out of scope) — Kotlin changes are KDoc only.
- No real webcam/phone clip data, no images committed.
- Never edit other planning artifacts, `_bmad/`, `.claude/skills/`, `sprint-status.yaml`; never change the existing `synthetic-*-v1` sessions or `ContinuousSessionReplayTest`'s expectations.
- Do not loosen the test into a tolerance or a count: it compares exact (session, frame, field) sets.

## I/O & Edge-Case Matrix

| Scenario | Input / State | Expected Output / Behavior | Error Handling |
|----------|--------------|---------------------------|----------------|
| Parity holds | session whose Kotlin replay diffs equal the listed set | test passes | — |
| New drift | a mapper change adds or removes one compared diff | test fails naming session, frame, field, recorded (Python) and replayed (Kotlin) values | — |
| Stale entry | listed diff no longer occurs | test fails naming the stale entry | — |
| Unknown cause | entry cites an id missing from the catalogue | test fails | — |
| Missing catalogue | catalogue lacks D1, D2, D3, D4, I401 or I402 | test fails | — |
| Discovery | `Fixtures/continuous/` scanned by `ContinuousSessionReplay.sessionsUnder` | `mapper-parity/` (no manifest) is not a session; existing golden test unaffected | — |

</intent-contract>

## Code Map

- `_bmad-output/planning-artifacts/research/technical-continuous-facelet-scan-2026-11-24/pc_spike/mapper.py` -- read-only reference: `_sample_face_cells` (201), `process_frame` (980), `_compute_cue` (956), `_snapshot` (1072); D2 at 595-599, D4 at 549-552, twist/record 613-662, merge 673+.
- `.../pc_spike/run_spike.py` `build_scenarios()`; `.../pc_spike/synth_video.py` `generate_sequence`, `DetectedQuad`, `FrameTruth`; `.../pc_spike/cube_model.py` `FACES`, `COLORS`, `check_local_invariants`.
- `core/domain/src/main/kotlin/dev/cubentia/domain/recognition/continuous/ContinuousFaceletMapper.kt` -- class KDoc (~35-41) to extend; D1 at ~837-855 (`lineTwistedFaces`, AWB withheld), D2 `normalizeLogAwbInPlace` (~1483), D3 in `tryMergePending` (~912+), D4 in `matchToTracked`/`attachBySharedEdge` centre helpers (~561-700), #401 `twistStickerPrototypes`/`isStickerColour`, #402 `MAX_OBS_PER_CELL` (KDoc already present), early twist return in `processFrame` (~317), `computeCue` (~1340).
- `core/domain/src/main/kotlin/dev/cubentia/domain/recognition/continuous/session/ContinuousSession.kt` -- schema-1 types: `SessionSnapshot`, `SessionCue`, `SnapshotKind`, `SessionOutcome.derive`, manifest/labels/frames codecs, `ContinuousSessionFormat.sessions` (direct children with manifest only).
- `core/domain/src/evaluation/kotlin/.../evaluation/continuous/ContinuousSessionReplay.kt` -- reuse `replay(dir).diffs` (fields + frame -1 outcome); no change needed.
- `core/domain/src/test/kotlin/.../evaluation/continuous/ContinuousSessionReplayTest.kt` -- pattern for locating `Fixtures/continuous`; `core/domain/src/test/kotlin/.../continuous/session/SyntheticSessionGenerator.kt` -- pattern for manifest/labels of synthetic sessions.
- `Fixtures/continuous/synthetic-complete-v1/` -- format examples; `Fixtures/continuous/README.md` -- document the new folder.
- `build-logic/.../tasks/CheckFixtureSizeTask.kt` -- per-session limit applies to top-level dirs with a manifest; nested dirs count in the total only.

## Tasks & Acceptance

**Execution:**
- `.../pc_spike/export_parity_sessions.py` -- new exporter (argparse `--out`, default repo `Fixtures/continuous/mapper-parity`); writes `pyspike-<n>-<slug>-v1/` (`[a-z0-9-]`) with manifest.json (pretty, written last), frames.jsonl, snapshots.jsonl, labels.json per the Always rules; deterministic re-runs -- the Python half of the golden pair.
- `Fixtures/continuous/mapper-parity/pyspike-*-v1/` -- the 3 exported sessions, committed.
- `Fixtures/continuous/mapper-parity/expected-differences.json` -- `{"comparedFields":[...], "deviations":[{"id","summary","deliberate":true|false}], "differences":[{"session","field","frames":[[from,to],...],"deviation","reason"}]}`; catalogue includes D1-D4, I401, I402 (I402 with a note if it yields no diff at these lengths) and every extra cause found.
- `core/domain/src/test/kotlin/dev/cubentia/domain/recognition/evaluation/continuous/MapperParityReplayTest.kt` -- for each session dir under `mapper-parity/` (expect exactly 3), replay, keep compared-field diffs, assert equality with the expanded list (messages list unlisted and stale entries), assert catalogue completeness and id references; also a check that the frames decode and the session is synthetic.
- `core/domain/src/main/kotlin/.../continuous/ContinuousFaceletMapper.kt` -- KDoc only: replace "pure-JVM port" wording with a deviations list naming each catalogued id with a one-line description, pointing to `MapperParityReplayTest` and the expected-difference file; brief KDoc at each deviation site referencing its id.
- `Fixtures/continuous/README.md` -- add a "Mapper parity sessions (#411)" section: what they are, regeneration command, that `snapshots.jsonl` there are mapper.py predictions and replay CLI diffs against them are expected.

**Acceptance Criteria:**
- Given the committed parity sessions, when `./gradlew :core:domain:test --tests '*MapperParityReplayTest*'` runs, then it passes, and temporarily editing one compared field in one Python snapshot line makes it fail naming that frame.
- Given a clean checkout, when the exporter is re-run with the uv command, then `git status` shows no diff in `Fixtures/continuous/mapper-parity/` except `buildCommit`.
- Given the whole build, when `./gradlew spotlessCheck build` runs, then it passes, including `:checkFixtureSize` and `ContinuousSessionReplayTest`.
- Given the mapper KDoc, when read, then every id in the catalogue is named with what differs from mapper.py.

## Spec Change Log

## Review Triage Log

### 2026-11-27 — Review pass
- verdicts: 36 findings — high 0, medium 1, low 18, false 6, maybe-false 0 (11 rejected low/out-of-design)
- findings:
  - `[medium]` `[defer]` (blind) The six named deviations (D1-D4, I401, I402) change no compared field on these three sessions, so reverting them leaves the test green — notes record why (AWB peak 0.315 < 0.36, merge with tiny AWB, max list 84 < 256); scenarios that make each bite belong with part (b); deferred.
  - `[low]` `[reject]` (blind) Revert attribution evidence not reproducible — results are recorded per id in the catalogue notes and the README now documents the revert procedure; scripting reverts would add tooling beyond scope.
  - `[low]` `[patch]` (blind) KDoc-names-every-id claim untested — test now asserts every catalogue id appears as **id** in ContinuousFaceletMapper.kt.
  - `[low]` `[patch]` (blind) Vacuous set-subtraction assertion — deleted.
  - `[low]` `[patch]` (blind) "deliberate" true for E1-E8 prejudges part (b) — E1-E8 now false, KDoc says found / not yet reviewed.
  - `[low]` `[reject]` (blind) New replay fields silently ignored — unlikely; replay field list is stable and named in comparedFields/notCompared; extra guard not worth it.
  - `[low]` `[patch]` (blind) Truth labels never checked — COMPLETE sessions now assert no label mismatches and nothing uncommitted.
  - `[low]` `[patch]` (blind) Unpinned opencv/numpy — versions used (opencv-python-headless 5.1.0, numpy 2.5.5) recorded in exporter docstring and README; command kept as the PO specified.
  - `[low]` `[reject]` (blind) buildCommit cannot detect stale fixtures — adding source hashes is new mechanism; low.
  - `[false]` `[reject]` (blind) labels source spec mismatch — the schema has no synthetic source value; exporter matches existing synthetic sessions; fix would edit the spec.
  - `[low]` `[patch]` (blind) I402 summary repeats note — summary now behaviour only.
  - `[low]` `[patch]` (blind) 15 MB per-session limit not enforced for nested dirs — sessions test asserts each dir ≤ 15,000,000 B.
  - `[low]` `[patch]` (blind) readExpected range validation — ranges not exactly two ints are reported as problems.
  - `[low]` `[patch]` (blind) Duplicate diff keys collapse in toMap — snapshot frameIndex sequence now asserted equal to frames.indices.
  - `[low]` `[patch]` (blind) README lacks list-maintenance procedure — added.
  - `[low]` `[patch]` (blind) Exporter cleanup — unused FACES import removed, createdAt comment added; orphan-dir removal rejected (exactly-3 test catches it).
  - `[low]` `[reject]` (blind) Mutation test covers only globalAnchored — the diff machinery is shared per field and covered by ContinuousSessionReplayTest; low.
  - `[low]` `[patch]` (edge) Duplicate keys via toMap — same fix as above (snapshot index check).
  - `[low]` `[patch]` (edge) snapshots frameIndex sequence unchecked — asserted.
  - `[low]` `[patch]` (edge) Range not exactly two ints — reported as problem.
  - `[low]` `[patch]` (edge) E1-E8 removable silently / KDoc sync — KDoc id assertion added.
  - `[false]` `[reject]` (edge) Missing deliberate key crashes — a loud failure on malformed committed data is correct behaviour.
  - `[false]` `[reject]` (edge) Unknown scenario name KeyError — loud failure on a developer tool; fine.
  - `[low]` `[reject]` (edge) Orphan sessions on re-export — exactly-3 test fails loudly; low.
  - `[false]` `[reject]` (edge) Raw vs rolled quad sampling float rounding — the replay matches mapper.py on every net/anchor/twist/conflict frame, and re-export is byte-identical; no observed misattribution.
  - `[low]` `[patch]` (edge) E2 wording "54 committed alone" wrong — now "54 committed with no conflict" in JSON and KDoc.
  - `[false]` `[reject]` (edge) labels source "entered" vs spec — same as blind row; schema constraint.
  - `[medium]` `[defer]` (gap, pre-verified) Fixtures never exercise most catalogued deviations or the conflict path — grouped with the first blind row; deferred; KDoc/README reworded from "checked" to "catalogued; only E3 (and D1 with I401) observable here" (patch).
  - `[low]` `[patch]` (gap) REQUIRED_DEVIATIONS partial — KDoc id assertion covers every catalogue id.
  - `[low]` `[patch]` (gap) Vacuous assertion — deleted.
  - `[low]` `[reject]` (gap) KDoc check manual — now automated (see patch).
  - `[low]` `[reject]` (gap) Exporter not run in CI — Python is not part of the Gradle build by design (uv-only, no system install); determinism verified manually.
  - `[low]` `[reject]` (intent) Named deviations unobserved — grouped with the deferred row.
  - `[low]` `[reject]` (intent) Revert evidence only in notes — as blind row.
  - `[false]` `[reject]` (intent) Fields excluded from comparison — PO intent asks for nets and flags; excluded fields are documented overlay/derived data.
  - `[low]` `[reject]` (intent) Kotlin sampling stage not exercised — the intent targets mapper parity on replayable observations; sampler parity is #413 / Story 10.31.

## Design Notes

Why snapshots.jsonl holds Python predictions in a nested folder: it keeps each parity session a valid, replayable schema-1 session whose recorded predictions are mapper.py's, so `ContinuousSessionReplay` diffs are the parity diffs directly; the nested `mapper-parity/` (no manifest) is invisible to the existing Kotlin golden test and the default replay task, which would otherwise demand zero diffs.

Cell order: mapper.py samples with the aligned (rolled) quad; Kotlin rotates the raw-order grid CCW by the shift. The 96×96 warp is symmetric under quarter turns, so sampling with raw corners and letting Kotlin rotate reproduces mapper.py's aligned samples.

## Verification

**Commands:**
- `cd .../pc_spike && uv run --no-project --with opencv-python-headless --with numpy python export_parity_sessions.py` -- expected: 3 session dirs written, exit 0.
- `export JAVA_HOME=/usr/lib/jvm/java-25-openjdk-amd64 ANDROID_HOME=$HOME/Android/Sdk && ./gradlew :core:domain:test --tests '*MapperParityReplayTest*' --no-daemon` -- expected: pass.
- `./gradlew spotlessCheck build --no-daemon` -- expected: BUILD SUCCESSFUL.

## Auto Run Result

Status: done

**Summary:** Added a golden parity replay between `pc_spike/mapper.py` and `ContinuousFaceletMapper`. A uv-run Python exporter writes three synthetic run_spike scenarios (1 regrip, 2 pastel AWB drift, 7 layer twist) as Story 10.34 sessions whose observations are mapper.py's own cell samples and whose snapshots are mapper.py's predictions. `MapperParityReplayTest` replays them and requires the compared-field differences to equal `expected-differences.json` exactly. Finding: the Kotlin replay matches mapper.py on every frame's net, anchoring, twist frame, conflicts and outcome; the only differences are 20 cue frames from E3 (remaining sides counted from confirmed vs observed faces). The six named deviations are catalogued but not exercised by these inputs (deferred).

**Files changed:**
- `_bmad-output/planning-artifacts/research/technical-continuous-facelet-scan-2026-11-24/pc_spike/export_parity_sessions.py` — new exporter.
- `Fixtures/continuous/mapper-parity/pyspike-{1,2,7}-*-v1/` — three sessions (~200 KB each).
- `Fixtures/continuous/mapper-parity/expected-differences.json` — catalogue D1-D4, I401, I402 (deliberate), E1-E8 (found, not yet reviewed) and the E3 cue differences.
- `core/domain/src/test/kotlin/dev/cubentia/domain/recognition/evaluation/continuous/MapperParityReplayTest.kt` — parity test (6 tests).
- `core/domain/src/main/kotlin/dev/cubentia/domain/recognition/continuous/ContinuousFaceletMapper.kt` — KDoc/comments only, naming every deviation id.
- `Fixtures/continuous/README.md` — parity section, regeneration, versions, maintenance procedure.

**Review:** 36 findings; 18 patched (all low, plus KDoc rewording from the medium gap finding), 1 deferred (medium: named deviations unexercised), 17 rejected with reasons in the triage log. Patched counts by verdict: high 0, medium 0 (rewording only), low 18.

**Follow-up review recommended:** false (no high patched; no two medium patched).

**Verification:** exporter re-run → byte-identical output; `./gradlew spotlessCheck build --no-daemon` → exit 0; MapperParityReplayTest 6/6, ContinuousSessionReplayTest 13/13; Kotlin mapper diff contains no non-comment line.

**Residual risks:** parity is pinned only for the behaviour these synthetic inputs exercise; fixtures depend on opencv-python-headless 5.1.0 / numpy 2.5.5 for byte-identical regeneration.
