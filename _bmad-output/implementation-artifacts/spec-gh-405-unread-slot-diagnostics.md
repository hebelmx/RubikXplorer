---
title: 'GH #405: debug diagnostics for unread Continuous Quick Scan net slots'
type: 'feature'
created: '2026-11-27'
status: 'done'
baseline_revision: '5fbb6fb2573ad7ed289bef8530b9a82cbfb5bedb'
review_loop_iteration: 0
followup_review_recommended: true
context: []
warnings: [oversized]
deferred: []
---

<intent-contract>

## Intent

**Problem:** On device (Lenovo, 10.32 pass, #391) the continuous Quick Scan stalls at 6/6 sides with 6-8 stickers unread, and no log separates a sticker that is not yet "ready" (too little evidence) from one that is ready but whose colour reading fails the distance/margin test. Without that split the fix planned for Story 10.36 cannot be confirmed.

**Approach:** The mapper records, during its existing colour solve, why each non-centre net slot stayed unread, with the numbers; a debug-only `ScanDiagnostics` call logs one line of that classification while the scan is stalled with all six sides mapped. Release gets a no-op twin.

## Boundaries & Constraints

**Always:** Diagnostics are read-only: the net, slot support, conflicted slots, cues, snapshots and exports stay bit-identical (S1-S7 stress scenarios, hand-off tests and the Story 10.34 golden sessions replay unchanged without `--refresh-golden`). Thresholds the diagnostics report come from the same named constants the solve uses (no duplicated literals). Log lines carry numbers and fixed words only, never pixels or frames (AD-13). The release `ScanDiagnostics` twin compiles, computes nothing and never invokes the supplier it is given.

**Never:** No change to the 8-per-colour assignment, the per-cell thresholds, readiness rules or the reopening loop (issue steps 2-3 go to Story 10.36). No new Learner text, no feature-flag change, no new module, no planning-artifact edits.

## I/O & Edge-Case Matrix

| Scenario | Input / State | Expected Output / Behavior | Error Handling |
|----------|--------------|---------------------------|----------------|
| Not observed | slot's master cell has no robust reading (face never seen, or no observations) | `UnreadSlot.NotObserved(slot)` | No error expected |
| Not ready | robust reading with `qualitySum < 0.55` or `count < 2` | `UnreadSlot.NotReady(slot, qualitySum, count)` | No error expected |
| Below margin | ready, fewer than 48 ready cells, `bestD >= 36` or `margin < 8` | `UnreadSlot.BelowMargin(slot, bestD, margin)` | No error expected |
| Balanced too far | ready, exactly 48 ready cells, chosen distance `>= 55` | `UnreadSlot.AboveDistance(slot, chosenD)` | No error expected |
| Reopened | committed by the solve, then cleared by the local-invariant loop | `UnreadSlot.Reopened(slot)` | No error expected |
| Read slot / centre | slot holds a colour, or cellIndex 4 | not listed | No error expected |
| Not anchored | no global rotation, fewer than 6 centre prototypes, or twist detected | `unreadSlots()` returns an empty list | No error expected |
| Debug log | anchored scanning snapshot, 6 mapped faces, unread non-empty | one `quickScan unread …` line with per-kind counts and per-slot numbers; repeated identical summaries and lines within 1000 ms of the last are suppressed | No error expected |

</intent-contract>

## Code Map

- `core/domain/src/main/kotlin/dev/cubentia/domain/recognition/continuous/ContinuousFaceletMapper.kt` -- `solveGlobalColorsAndNet()` (~line 1060-1243): readiness filter (`r.qualitySum < 0.55 || r.count < 2`), 48-cell balanced branch (`chosenD < 55.0`), per-cell branch (`bestD < 36.0 && margin >= 8.0`), 12-pass local-invariant reopening into `conflicted`. Early returns (no master, twist, <2 centres, no `globalRotIdx`, `protos.size < 6`) must leave diagnostics empty. `reset()` (~line 282) and `promotePendingToMaster` (~line 1026) clear state. Companion object (~line 1355) holds constants.
- `core/domain/src/main/kotlin/dev/cubentia/domain/recognition/continuous/ContinuousTypes.kt` -- `FaceSlot` (line 81), reuse as the slot key.
- `app/src/debug/kotlin/dev/cubentia/app/ScanDiagnostics.kt` -- debug logger; follow the `onFrame` pattern (`nowMs` parameter, `AtomicLong` gate, `format()` with `Locale.ROOT`), tag `TAG`.
- `app/src/release/kotlin/dev/cubentia/app/ScanDiagnostics.kt` -- no-op twin; every debug public function needs a matching signature here.
- `app/src/camera/continuous/kotlin/dev/cubentia/app/camera/continuous/ContinuousScanPipeline.kt` -- owns the private `mapper`; expose `unreadSlots()`.
- `app/src/camera/continuous/kotlin/dev/cubentia/app/camera/continuous/ContinuousScanEngineModule.kt` -- `PipelineEngine.processFrame` is the call site for the diagnostics hook.
- `core/domain/src/test/kotlin/dev/cubentia/domain/recognition/continuous/ContinuousFaceletMapperTest.kt` -- `SyntheticCubeStream` scenario/mutation patterns (see the conflict test ~line 179) to build mapper states.
- `app/src/testDebug/kotlin/dev/cubentia/app/ScanDiagnosticsRunLineTest.kt` -- Robolectric + `ShadowLog` pattern for debug log tests.
- `core/domain/src/test/kotlin/dev/cubentia/domain/recognition/continuous/ContinuousStressScenariosTest.kt`, `ContinuousHandoffTest.kt`, `core/domain/src/test/kotlin/dev/cubentia/domain/recognition/evaluation/continuous/ContinuousSessionReplayTest.kt` -- read-only regression guards; must pass unmodified.

## Tasks & Acceptance

**Execution:**
- `core/domain/src/main/kotlin/dev/cubentia/domain/recognition/continuous/UnreadSlot.kt` -- new public sealed interface `UnreadSlot { val slot: FaceSlot }` with `NotObserved`, `NotReady(qualitySum, count)`, `BelowMargin(bestD, margin)`, `AboveDistance(chosenD)`, `Reopened` -- typed, numbers-only diagnostic surface.
- `core/domain/src/main/kotlin/dev/cubentia/domain/recognition/continuous/ContinuousFaceletMapper.kt` -- hoist the literals 0.55, 2, 55.0, 36.0, 8.0 into named private constants used by the solve unchanged; record per-canonical-slot readiness/distance numbers while the existing loops run (no extra robust-Lab computation, no change to values written to the net/support); add public `fun unreadSlots(): List<UnreadSlot>` (sorted by slot, centres excluded, empty when not anchored per the matrix); clear the record on `reset()`, pending promotion and every early return -- the classification source.
- `app/src/camera/continuous/kotlin/dev/cubentia/app/camera/continuous/ContinuousScanPipeline.kt` -- add `fun unreadSlots() = mapper.unreadSlots()`.
- `app/src/camera/continuous/kotlin/dev/cubentia/app/camera/continuous/ContinuousScanEngineModule.kt` -- after each processed frame call `ScanDiagnostics.onQuickScanUnread(SystemClock.elapsedRealtime(), result.snapshot) { pipeline.unreadSlots() }`.
- `app/src/debug/kotlin/dev/cubentia/app/ScanDiagnostics.kt` -- `onQuickScanUnread(nowMs, snapshot, unread: () -> List<UnreadSlot>)`: only for `ContinuousScanSnapshot.Scanning` with `globalAnchored` and `mappedFacesCount == 6`; logs `quickScan unread frame=<n> committed=<c> notObserved=<k> notReady=<k> belowMargin=<k> aboveDistance=<k> reopened=<k> slots=<list>` where each slot reads e.g. `U1:notReady(q0.42/min0.55,n1/min2)`, `F3:belowMargin(d31.2/max36,m5.1/min8)`, `R5:aboveDistance(d58.0/max55)`, `B7:reopened`, `L2:notObserved`; suppresses a line equal to the last logged one and any line within 1000 ms of the last logged one. Thresholds in the line come from the mapper's constants (expose them as public consts on a companion of `UnreadSlot` or the mapper).
- `app/src/release/kotlin/dev/cubentia/app/ScanDiagnostics.kt` -- matching no-op `onQuickScanUnread` that never calls `unread`.
- `core/domain/src/test/kotlin/dev/cubentia/domain/recognition/continuous/UnreadSlotDiagnosticsTest.kt` -- JVM tests driving `ContinuousFaceletMapper` with synthetic streams covering every matrix row from NotObserved through Not anchored (e.g. low-quality cells for NotReady; a cell recoloured to sit between two prototypes for BelowMargin; the reopened slot from the conflict pattern; an unanchored or partial-face mapper for empty/NotObserved), and asserting `unreadSlots()` excludes read slots and centres.
- `app/src/testDebug/kotlin/dev/cubentia/app/ScanDiagnosticsUnreadLineTest.kt` -- Robolectric `ShadowLog` tests for the debug line format, the gating (not anchored / fewer than 6 faces / Complete logs nothing), duplicate and 1000 ms suppression.

**Acceptance Criteria:**
- Given a debug build scanning a cube with all six sides mapped and some stickers unread, when frames arrive, then `adb logcat -s CubentiaScanDiag` shows `quickScan unread` lines that say, per unread slot, whether it is not ready or ready but below the margin/distance, with its numbers.
- Given the existing JVM suites, when `./gradlew spotlessCheck build` runs, then S1-S7, hand-off and session-replay tests pass unmodified and `replayContinuousSession` reports no drift against the committed golden sessions.
- Given a release build, when it compiles, then `ScanDiagnostics.onQuickScanUnread` exists as a no-op and the supplier is never invoked.

## Spec Change Log

## Review Triage Log

### 2026-11-27 — Review pass
- verdicts: 21 findings — high 0, medium 2, low 11, false 8, maybe-false 0
- findings:
  - `[false]` `[reject]` (intent) Diff adds NotObserved/AboveDistance/Reopened beyond the two named categories — finer split of the same "not ready / ready but rejected" question; BelowMargin carries bestD and margin as the intent's "below-margin/bestD" bucket; no wrong outcome.
  - `[low]` `[reject]` (intent) PipelineEngine wiring path is not exercised by tests — same as the verification-gap finding below; see that row.
  - `[false]` `[reject]` (intent) No evidence that spotlessCheck build and golden replays ran — both ran in this build: BUILD SUCCESSFUL, replayContinuousSession 0 snapshot/cue diffs on both sessions, Fixtures untouched.
  - `[low]` `[reject]` (gap) PipelineEngine's call to onQuickScanUnread has no test — real, but closing it needs a fake detector replaying an anchored six-face stream at the app layer (new scaffolding) for a debug-only log whose formatting and gating are pinned by ScanDiagnosticsUnreadLineTest; the device session will exercise it.
  - `[false]` `[reject]` (edge) Anchored six-face scan with protos.size < 6 logs nothing — mappedFacesCount counts confirmedFaces (centre count >= 2 and qualitySum >= 0.40), which implies all six centres pass the centreLabs gate (qualitySum >= 0.40), so protos.size == 6 whenever the log gate is open.
  - `[medium]` `[patch]` (edge) A repeated body did not advance the 1000 ms gate, so the supplier and line building ran every frame during a stall — fixed: unreadLastMs is set before the repeat check; test asserts no supplier call within 1000 ms of a suppressed repeat.
  - `[false]` `[reject]` (edge) Skipped frame logs a stale frame index — skipped frames leave the mapper untouched, so the stale snapshot's index is the frame the numbers describe, and a repeat body is suppressed.
  - `[false]` `[reject]` (edge) else -> Unit could silently drop a blank slot recorded READ — a READ slot is written to the net; it can only become blank via the reopening loop, which puts it in conflictedSlots (listed as Reopened first).
  - `[low]` `[reject]` (edge) Dedupe compares rounded numbers, hiding sub-rounding changes — by design; sub-0.01 quality or sub-0.1 distance changes carry no diagnostic value.
  - `[medium]` `[patch]` (blind) NotObserved KDoc omitted contradictory strongest readings (faceRobustLab returns null when top two differ by > 18) — fixed: KDoc now defines it as "no robust reading" with all three causes.
  - `[low]` `[patch]` (blind) Supplier called every frame once a stall repeats — same root cause as the edge-case gate finding; fixed by the same patch.
  - `[low]` `[reject]` (blind) An unchanged stall is logged only once and may rotate out of logcat — any change in committed count or per-slot numbers re-emits; a heartbeat adds a second interval for a rare case.
  - `[low]` `[reject]` (blind) Dedupe state not cleared between scans — dropping a second scan's first line needs a byte-identical body (all numbers to 2/1 dp); next change re-emits.
  - `[low]` `[patch]` (blind) Not-anchored row untested for twist, protos < 6, promotion — added a layer-twist-after-anchored-scan test; protos < 6 is unreachable under the log gate (see above) and promotion clearing is a one-line reset covered by the reset path.
  - `[low]` `[reject]` (blind) Wiring and release no-op untested — wiring as the gap row; the release twin is a `= Unit` body that cannot invoke the supplier and compiles in assembleRelease.
  - `[false]` `[reject]` (blind) BelowMargin misnames distance failures — the intent itself names the bucket "below-margin/bestD"; each slot carries both numbers against their thresholds.
  - `[low]` `[reject]` (blind) Six-face gate written twice — the engine pre-check only avoids reading SystemClock in plain unit tests; the debug function keeps the authoritative gate; no caller diverges today.
  - `[low]` `[reject]` (blind) Solve thresholds live on UnreadSlot's companion — spec allowed it; the solve reads the same constants so there is one source of truth; moving them is churn.
  - `[false]` `[reject]` (blind) Line lacks ready-cell count and branch — derivable: ready = 48 - notObserved - notReady, and aboveDistance appears only in the 48-ready balanced branch, belowMargin only in the per-cell branch.
  - `[low]` `[reject]` (blind) Thresholds repeated per slot entry — cosmetic length; keeps each entry self-describing like onFrame's fields.
  - `[low]` `[patch]` (blind) assertOnlyUnread counted centre blanks — fixed: cellIndex 4 excluded.

## Design Notes

Record into preallocated per-slot primitive arrays (54 entries: kind code, qualitySum, count, bestD, margin/chosenD) filled inside the existing loops so the per-frame cost bound from #402 holds; `unreadSlots()` builds objects only when called (debug only). A slot is `Reopened` when it is in `conflictedSlots` after the solve; otherwise its recorded kind decides. The 6-face gate matches the reported stall ("6/6 sides, 6-8 unread") and keeps early-scan noise out of the log.

## Verification

**Commands:**
- `cd /mnt/e/Dynamic/IndFun/RubikXplorer-issue-405 && export JAVA_HOME=/usr/lib/jvm/java-25-openjdk-amd64 ANDROID_HOME=$HOME/Android/Sdk && ./gradlew spotlessCheck build` -- expected: BUILD SUCCESSFUL
- `cd /mnt/e/Dynamic/IndFun/RubikXplorer-issue-405 && export JAVA_HOME=/usr/lib/jvm/java-25-openjdk-amd64 ANDROID_HOME=$HOME/Android/Sdk && ./gradlew :core:domain:replayContinuousSession` -- expected: success with no snapshot/cue drift (never pass `--refresh-golden`)
- `cd /mnt/e/Dynamic/IndFun/RubikXplorer-issue-405 && git status --porcelain Fixtures` -- expected: empty (golden sessions untouched)

## Auto Run Result

Status: done

**Summary:** Debug-only diagnostics for GH #405 step 1 (PO scope cut). During its existing colour solve the continuous mapper records, per canonical non-centre slot, why it stayed unread (NotObserved = no robust reading, NotReady with qualitySum/count, BelowMargin with bestD/margin, AboveDistance with the balanced branch's chosenD, Reopened by the local-invariant loop). `ContinuousFaceletMapper.unreadSlots()` exposes it; the debug `ScanDiagnostics.onQuickScanUnread` logs a `quickScan unread …` line (per-kind counts plus per-slot numbers against thresholds) for an anchored scanning snapshot with all six sides mapped, at most once per 1000 ms and not when it repeats. The release twin is a no-op. Colour assignment and commit logic are unchanged (thresholds hoisted into named constants with identical values).

**Files changed:**
- `core/domain/src/main/kotlin/dev/cubentia/domain/recognition/continuous/UnreadSlot.kt` -- new sealed diagnostic type plus the solve's threshold constants.
- `core/domain/src/main/kotlin/dev/cubentia/domain/recognition/continuous/ContinuousFaceletMapper.kt` -- per-slot primitive record filled inside the solve; `unreadSlots()`; constants replace literals.
- `app/src/camera/continuous/kotlin/dev/cubentia/app/camera/continuous/ContinuousScanPipeline.kt` -- `unreadSlots()` pass-through.
- `app/src/camera/continuous/kotlin/dev/cubentia/app/camera/continuous/ContinuousScanEngineModule.kt` -- calls the hook for anchored six-face frames.
- `app/src/debug/kotlin/dev/cubentia/app/ScanDiagnostics.kt` -- `onQuickScanUnread` line, gating, rate limit and dedupe.
- `app/src/release/kotlin/dev/cubentia/app/ScanDiagnostics.kt` -- no-op twin.
- `core/domain/src/test/kotlin/dev/cubentia/domain/recognition/continuous/UnreadSlotDiagnosticsTest.kt` -- 9 JVM tests over the classification matrix.
- `app/src/testDebug/kotlin/dev/cubentia/app/ScanDiagnosticsUnreadLineTest.kt` -- 5 Robolectric tests for the log line.

**Review findings:** 21 findings; 5 patched (2 medium, 3 low: gate advance on repeats, NotObserved KDoc, twist test, centre-excluding assertion; one low shared the gate root cause), 0 deferred, 16 rejected with reasons in the Review Triage Log.

**Follow-up review recommendation:** true (patched by verdict: high 0, medium 2, low 3). Two medium patches were applied on this first pass; the named unverified risk is the debug rate-limit/dedupe timing of the `quickScan unread` line on a real analyzer thread, which only unit tests (fixed clock values) have checked.

**Verification:** `./gradlew spotlessCheck build` BUILD SUCCESSFUL (after patches); `./gradlew :core:domain:replayContinuousSession` 0 snapshot/cue diffs on synthetic-complete-v1 and synthetic-partial-v1; `git status --porcelain Fixtures` empty; S1-S7 stress, hand-off and replay tests pass unmodified.

**Residual risks:** the engine-to-log wiring is not covered by an automated test (debug only); the logcat line still needs a debug-build device session to confirm the #391 stall cause. Synthetic test colours for BelowMargin/AboveDistance were tuned against the current colour model and may need retuning if Story 10.36 changes it.
