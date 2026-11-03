---
title: 'SolveCube use case'
type: feature
created: '2026-11-02'
status: done
baseline_revision: 41fce351a79834f6eec806068b196e8efa8035a8
review_loop_iteration: 0
followup_review_recommended: false
context:
  - /mnt/e/Dynamic/IndFun/RubikXplorer/AGENTS.md
  - /mnt/e/Dynamic/IndFun/RubikXplorer/_bmad-output/implementation-artifacts/epic-3-context.md
warnings: []
deferred: []
---

<intent-contract>

## Intent

**Problem:** Search exists but no application use case enforces orientation mapping, independent verification and recoverable effect outcomes.

**Approach:** Add an injected SolveCube effect handler accepting only CubeState, passing cubies to CubeSolver on Default under a 3 s timeout, mapping and checking every solved sequence before returning revision-tagged results.

## Boundaries & Constraints

**Always:** AD-1/2/3/8/11/12 apply. Async effect and completion echo effectId and revision; no session mutation in the handler. Use sealed outcomes with Text recovery prompts backed by en.json. Propagate caller cancellation. Catch operational adapter exceptions as Failed. Preserve CubeValidator as the only sticker-input route, including restricting CubeState construction/copy outside domain. Existing SOLVED and legal apply operations remain supported.

**Never:** Change planning artifacts, generated skills, production loading/table delivery, calibration, startup, UI or lesson behavior. Production integration and hardware evidence remain mandatory Story 3.8 before production 3.7/Epic3/P0.S. No branch switching, push, PR or commits by implementation subagent. Do not add solve session/reducer UI integration belonging to 3.7.

## I/O & Edge-Case Matrix

| Scenario | Input / State | Expected Output / Behavior | Error Handling |
| --- | --- | --- | --- |
| Valid solution | Validated cube in any of 24 orientations | Cubies-only call off caller thread; correctly mapped Moves solve original input; metadata echoed | Verified solved outcome only |
| Solved input | Validated solved cube | Empty solution accepted after verification | No error |
| Dishonest answer | Solver returns incomplete/wrong solution | Failed with localized recovery prompt | No partial solution |
| Adapter outcomes | Invalid, TimedOut, Failed or operational exception | TimedOut preserved; all failures have Text recovery prompt | No invented solution |
| Deadline | Solver suspends beyond 3 s | TimedOut with recovery Text | Cancel work |
| Caller cancellation | Cancel effect while solver active | Cancellation propagates and no completion emitted | Never convert caller cancellation to failure |
| Invalid input type | Facelets or external CubeState construction/copy | Cannot invoke use case or construct unchecked state via public Kotlin API | Validator remains gate |

</intent-contract>

## Code Map

- `core/application/src/main/kotlin/dev/rubikxplorer/application/port/CubeSolver.kt` — existing suspend cubies-only port, SolveResult.Solved(List<FaceTurn>), Invalid/TimedOut/Failed; preserve adapter contract.
- `core/application/src/main/kotlin/dev/rubikxplorer/application/session/Effect.kt` — revision/effectId convention and ConfirmReset; add typed solve request without changing Lesson reducer semantics.
- `core/application/src/main/kotlin/dev/rubikxplorer/application/usecase/NewScramble.kt` — application package convention; new SolveCube.kt belongs here.
- `core/domain/src/main/kotlin/dev/rubikxplorer/domain/cube/CubeState.kt` — currently public constructor/copy; restrict both consistently. apply already maps learner faces through homeFaceAt; isSolved ignores orientation.
- `core/domain/src/main/kotlin/dev/rubikxplorer/domain/cube/Orientation.kt` — ALL and homeFaceAt; invert face lookup to map center-relative solver faces to learner faces, retain turn direction.
- `core/domain/src/main/kotlin/dev/rubikxplorer/domain/cube/CubeValidator.kt` — existing sole Facelets validation; validated state and legal apply build test fixtures.
- `core/domain/src/main/resources/content/strings/en.json` — msg.* recovery text; no new content fields.
- `core/application/build.gradle.kts`, `gradle/libs.versions.toml` — coroutines core alias already exists; application needs dependency. Avoid unpinned versions.
- `app/src/main/kotlin` — effect consumers may need exhaustive handling compatibility after request variant; no production solver wiring.

## Tasks & Acceptance

**Execution:**
- [x] `core/application/src/main/kotlin/dev/rubikxplorer/application/session/Effect.kt` and `usecase/SolveCube.kt` — introduce typed revisioned request/completion and handler with explicit sealed presentation results, off-main 3 s timeout, orientation mapping and verification.
- [x] `core/domain/src/main/kotlin/dev/rubikxplorer/domain/cube/CubeState.kt` — close external unchecked construction/copy without changing legal transformations.
- [x] `core/domain/src/main/resources/content/strings/en.json` and `core/application/build.gradle.kts` — add recovery keys and existing coroutine dependency.
- [x] `core/application/src/test/kotlin/dev/rubikxplorer/application/usecase/SolveCubeTest.kt` — exercise every matrix row, all orientations/faces/turn amounts, mismatch verification, metadata, off-caller execution, real 3 s timeout and cancellation. Add meaningful API compile-negative verification for input gate if feasible using installed compiler rather than extra dependency.

**Acceptance Criteria:**
- Given a validated CubeState in any orientation, when SolveCube executes as a 3 s Effect, then CubeSolver receives cubies only, mapped Moves solve the exact input before a successful outcome is returned, and completion preserves effectId/revision.
- Given timeout or failure, when the effect completes, then a sealed TimedOut or Failed outcome contains resolvable Text recovery and no partial Solution.
- Given unchecked Facelets, when callers try to reach SolveCube, then the API accepts only CubeState and unchecked public state construction/copy is inaccessible.

## Spec Change Log

## Review Triage Log

### 2026-11-02 — Review pass
- verdicts: 12 findings — high 0, medium 2, low 6, false 4, maybe-false 0
- findings:
  - `[low]` `[patch]` Blind: cancellation missing during mapping/verification — arbitrary injected adapter sequences could delay cancellation; production search bounds answers to 30 moves, limiting practical harm. Added incremental verification and per-move activity checks plus deterministic interruption test.
  - `[medium]` `[patch]` Blind: no successful noncommuting multi-move test — existing one-move fixtures would miss truncation/reversal. Added ordered four-move inverse across all 24 orientations.
  - `[low]` `[reject]` Blind: no automated external API compile-negative regression — separate installed Kotlin 2.4.21 probe verifies constructor/copy and Facelets rejection plus legal transformations; introducing compiler-test infrastructure for this visibility change is disproportionate. Manual evidence retained below.
  - `[low]` `[patch]` Blind: nested adapter timeout missing — added shorter nested withTimeout test proving adapter cancellation propagates rather than becoming handler timeout.
  - `[low]` `[reject]` Blind: public successful outcome and mutable list — handler already maps into a fresh list, now explicitly snapshots it with toList. Fabricating outcomes or casting read-only Kotlin lists requires deliberate caller misuse; a new restricted public success factory would add complexity without an actual divergent consumer.
  - `[low]` `[patch]` Edge: long sequence ignores cancellation during mapping/verification — same demonstrated injected-sequence root cause as first row; incremental checks stop consumption at cancellation.
  - `[medium]` `[patch]` Verification: truncating result.moves to one passes existing fixtures — accepted pre-verified regression gap, same root cause as Blind multi-move row; new complete ordered sequence test rejects truncation and reversal.
  - `[false]` `[reject]` Intent: handler tests do not exercise production app — accepted story explicitly permits injected contract before Story3.8 production loading; Story3.7 owns end-to-end flow. No production behavior claimed.
  - `[low]` `[reject]` Intent: runtime tests do not prove compile boundary — external installed-compiler probes do establish that boundary; automated regression remains a minor infrastructure suggestion rejected with Blind compile-test row.
  - `[false]` `[reject]` Intent: solver-wide gate rather than use-case gate — Cubies constructor/copy were already internal and public sources are validated states or legal SOLVED/apply transformations; unchanged CubeSolver cubies-only port matches AD-8. No unchecked sticker route added.
  - `[false]` `[reject]` Intent: cooperative fake does not establish universal hard preemption — coroutine cancellation is the existing solver contract and active search checks cancellation. Arbitrary blocking implementations are outside that contract; new handler and verification cooperate and never publish after cancellation.
  - `[false]` `[reject]` Intent: protected-scope documentation changes — only implementation artifacts changed; no protected planning artifacts, production policy or hardware acceptance changed.

## Design Notes

Story 3.3 provides the solver port and search; reuse them unchanged. Handler completion can be a typed value consumed by future production host/reducer integration; this story does not fabricate an unused SOLVE_PLAYBACK session. Timeout must belong to this use case independently of adapter behavior. Preserve cancellation identity; nested adapter cancellation must never become invented success.

## Verification

**Commands:**
- `export JAVA_HOME=/usr/lib/jvm/java-25-openjdk-amd64 ANDROID_HOME=$HOME/Android/Sdk; ./gradlew :core:application:test :core:domain:test` — matrix and existing pure tests pass.
- `export JAVA_HOME=/usr/lib/jvm/java-25-openjdk-amd64 ANDROID_HOME=$HOME/Android/Sdk; ./gradlew spotlessCheck build` — all CI gates pass, including Kover, privacy, boundaries and Android builds.

## Auto Run Result

Status: done. Implemented revisioned Effect.SolveCube and SolveCubeCompleted with cubies-only injected solver access on Dispatchers.Default, independent 3 s timeout, orientation mapping, incremental independent verification and cooperative cancellation. Invalid/Failed/exception results return Failed and timeout returns TimedOut with keyed recovery Text; no failure carries partial Moves. Closed external Kotlin CubeState constructor/copy while retaining validator and legal transformations.

Files changed:
- `core/application/.../session/Effect.kt` — typed solve request with effectId/revision.
- `core/application/.../usecase/SolveCube.kt` — handler, completion and sealed outcomes.
- `core/application/.../usecase/SolveCubeTest.kt` — eight executed regression tests.
- `core/application/build.gradle.kts` — existing coroutine dependency alias.
- `core/domain/.../cube/CubeState.kt` — internal constructor/consistent copy visibility.
- `core/domain/src/main/resources/content/strings/en.json` — timeout and failure recovery messages.
- Epic context and this execution spec — refreshed accepted scope and review record.

Four independent review layers completed at inherited capability, queued under the platform concurrency limit before triage. Twelve findings individually triaged above; three grouped root causes patched (high0, medium1, low2), no deferrals. Rejected findings retain individual evidence in Review Triage Log: manual compiler verification addresses type-gate evidence, public value misuse does not justify extra factories, production integration is explicitly later scope, cubies-only contract remains valid, cancellation is cooperative by contract, and protected planning scope was preserved. Follow-up review recommended:false; one medium patched entry and no high entry, with no named unresolved verification risk.

Matrix audit: all seven rows covered. Eight SolveCube tests executed with zero skips/errors/failures: 432 orientation/face/turn combinations; rotated solved and wrong empty answers; invalid/failed/exception/wrong-answer recovery; real 3 s deadline; caller cancellation; ordered noncommuting inverse across24 orientations; cancellation while consuming adapter moves; nested adapter timeout propagation. Type gate additionally verified using installed Kotlin2.4.21 compiler as an external non-friend module: construction/copy/Facelets probes exit1 with expected visibility/type errors; legal SOLVED.apply probe exits0. Commands and full diagnostic evidence retained at `/tmp/story34-api-probe.py` and `/tmp/story34-api-probe.log`.

Verification: `:core:application:test :core:domain:test spotlessCheck build` SUCCESSFUL before review in18s and after all patches in2m45s; final log `/tmp/story34-patched-final.log`. Final run executed47 tasks including full solver corpus and all applicable CI gates; Kover, privacy, boundaries, lint and debug/release/benchmark builds passed. Focused post-patch test log `/tmp/story34-focused.log`. No build-logic changes, so included-build tests are not required.

Residual boundary: this is the injected application contract. Mandatory Story3.8 retains production table delivery/loading, reference-device measurements and calibrated corpus acceptance before production3.7/Epic3/P0.S closure. No production app loading, UI, persistence or hardware evidence is claimed.
