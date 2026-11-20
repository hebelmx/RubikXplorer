---
title: 'Stability, size and privacy hardening'
type: 'chore'
created: '2026-11-20'
status: 'done'
baseline_revision: 'ae86a56ea12c6cfe9ab97b98fd4e6c62b72c2dee'
review_loop_iteration: 0
followup_review_recommended: true
context:
  - '{project-root}/_bmad-output/implementation-artifacts/epic-12-context.md'
warnings: ['oversized']
deferred:
  - summary: 'PO item (AC2, physical): on a reference device, install the release build fresh (from the Play internal or closed track), switch on airplane mode, and run the Course, Library, cube entry and solve; record the result in docs/evidence/12-3/README.md.'
    evidence: 'PO clarification 2026-11-20: physical-device checks go to the combined human-verification session. Automated emulator run covers the debug build only.'
    severity: 'medium'
  - summary: 'PO item (AC3): in Play Console, review the closed-testing build''s Android vitals before production: user-perceived crash-free sessions >= 99.5% and user-perceived ANR rate below Play''s bad-behavior threshold; record the numbers, date and versionCode in docs/evidence/12-3/README.md.'
    evidence: 'PO clarification 2026-11-20: Play Console vitals review is human-only. Runbook: docs/release.md "Before production".'
    severity: 'high'
  - summary: 'PO item (AC3): confirm the Play data-safety form declares "no data collected" (answer No to collecting or sharing any required user data type) and is saved against the registered privacy-policy URL (Story 13.6); record the date in docs/evidence/12-3/README.md.'
    evidence: 'PO clarification 2026-11-20: filling the Play data-safety form is human-only. Answers: docs/play-first-publish.md section 2.'
    severity: 'high'
  - summary: >-
      Release-build offline run is not automated: the emulator offline Key Flow run uses the debug build (debug raises wired flags), so the release artifact offline behavior rests on the PO physical item.
    evidence: |-
      Intent-alignment review. Unverified whether any release-only difference affects offline use; settle by running the benchmark build (release code, debug-signed) offline on an emulator, or by the PO physical release check.
    location: >-
      tools/run-keyflow-rotation.sh
    severity: medium (unverified)
  - summary: >-
      PO decision: sprint-change-proposal-2026-11-17-playback-view-and-move-glyphs.md says Story 12.3 absorbs epic-6-retro-item-1 (PreferenceStore must not overwrite saved prefs with defaults after a read error), but the 12.3 ACs in epics.md do not include it and this run implemented exactly the ACs.
    evidence: |-
      SCP 2026-11-17 Interactions row; sprint-status.yaml item still open; epics.md Story 12.3 has no such AC. Needs the PO to keep it in 12.3 (follow-up) or move it to another story.
    location: >-
      app/src/main/kotlin/dev/cubentia/app/preferences/PreferenceStore.kt
    severity: medium
---

<intent-contract>

## Intent

**Problem:** Story 12.3 (Refs: NFR-5, NFR-8, NFR-9, NFR-11 · AD-13, AD-15, AD-16 · AR-4, AR-5) needs evidence that the release is small, private, offline and stable. The AD-13 and AD-14/AD-15 gates already exist (`:app:checkReleaseSize` on the release AAB and its universal APK, `:app:checkReleaseMergedManifest`, `:app:checkReleaseSdkDenylist`, `:app:checkPrivacyFacts`), but nothing runs the Key Flows with the network off, so AC2 (NFR-5 airplane mode) has no automated evidence, and the vitals and data-safety steps have no pre-production runbook.

**Approach:** Add an offline mode to the Key Flow host script that turns on airplane mode, has the instrumented harness assert the device is offline before each flow, and restores the setting; run it on an emulator, and make the release-tag CI run the Key Flows offline. Record AC1 gate results and AC2 emulator results as evidence, and write the AC3 "Before production" runbook; the human steps are PO deferred items.

## Boundaries & Constraints

**Always:** AD-13, AD-15 and AD-16 hold as written (no `INTERNET`, no runtime permission with camera flags OFF, `allowBackup="false"` with exclude-all `dataExtractionRules`, denylist enforced, AAB-only release with R8 mapping, StrictMode in debug, Android vitals as the only field signal). Instrumented runs target an emulator serial explicitly, never a connected physical device. The host script leaves the device as it found it (airplane mode restored on exit, also on failure). Offline mode is opt-in; without it the script behaves exactly as today.

**Never:** no `INTERNET`, `ACCESS_NETWORK_STATE` or any new permission in any manifest (main, debug or androidTest); no new dependency; no change to app UI, Learner text, or session code (this story has no UI behavior to settle); no change to the gate thresholds (30,000,000 bytes, manifest and denylist rules); no work on `epic-6-retro-item-1` (PreferenceStore), which is not in this story's ACs; never edit planning artifacts, `_bmad/` or `.claude/`.

## I/O & Edge-Case Matrix

| Scenario | Input / State | Expected Output / Behavior | Error Handling |
|----------|--------------|---------------------------|----------------|
| Offline run | `OFFLINE=1 tools/run-keyflow-rotation.sh emulator-5554 <label>` | airplane mode on before the first method; each method's flow starts only after the harness sees `airplane_mode_on=1`; `device.txt` records airplane mode before, during and after | — |
| Not actually offline | instrumentation arg `offline=true` but `airplane_mode_on` is not 1 | the flow fails with "<flow>: device is not offline (airplane_mode_on=<v>)" | test fails, script marks method `fail` |
| Restore | script exits (pass or fail) | `airplane_mode_on` back to its saved value; checked, error if not | exit 1 on mismatch |
| Online (default) | no `OFFLINE` | no airplane change, no `offline` argument, unchanged behavior | — |

</intent-contract>

## Code Map

- `tools/run-keyflow-rotation.sh` -- host script (Story 12.2): installs debug + androidTest APKs, `pm clear` per method, `am instrument -e class ...`, writes `docs/evidence/12-2/keyflow/<label>/`; rotation save/restore trap pattern at L40-70 to mirror for airplane mode. Evidence root is hard-coded L29.
- `app/src/androidTest/kotlin/dev/cubentia/app/compat/KeyFlowHarness.kt` -- per-flow harness; `device()` (UiDevice, `executeShellCommand` runs as shell), `Log.i(TAG, ...)` evidence lines, `TAG = "KeyFlowRotation"`. Constructed once per flow by `KeyFlowRotationTest.flow(name)` (L42) -- the place to assert offline.
- `app/src/androidTest/kotlin/dev/cubentia/app/compat/KeyFlowRotationTest.kt` -- flows 1 (Course), 2, 3 (cube entry + solve), 6 (Library OLL + Enter colors + solve), 7, 8, 9; read-only except the KDoc.
- `.github/scripts/run-instrumentation.sh` -- release-tag CI (API 34 emulator); calls the host script with label `ci` and checks `docs/evidence/12-2/keyflow/ci/summary.txt`.
- `build-logic/.../ReleaseConventionPlugin.kt` (`checkReleaseSize`: `APK_FROM_BUNDLE` + `BUNDLE`), `policy/AppSizePolicy.kt`, `policy/ManifestPolicy.kt`, `policy/SdkDenylistPolicy.kt`, `docs/privacy-facts.json` -- AC1 gates; read-only, already in CI (`.github/workflows/ci.yml`).
- `docs/release.md` (Closed-testing pilot / Local checks), `docs/play-first-publish.md` section 2 (data-safety answers), `docs/release-gates-v1.0.md` gate 5 row ("No recorded offline device run"), `docs/emulators.md` (AVDs: `GalaxyS24FE_API36` etc., headless launch).

## Tasks & Acceptance

**Execution:**
- `tools/run-keyflow-rotation.sh` -- add `OFFLINE=1` mode: save `settings get global airplane_mode_on`, run `cmd connectivity airplane-mode enable`, wait until it reads 1, pass `-e offline true` to `am instrument`, record airplane state in `device.txt`, restore and verify in the EXIT trap; add `EVIDENCE_DIR` override (default unchanged) -- AC2 automation.
- `app/src/androidTest/kotlin/dev/cubentia/app/compat/KeyFlowHarness.kt` -- when `InstrumentationRegistry.getArguments().getString("offline") == "true"`, the harness's init asserts `settings get global airplane_mode_on` is `1` (via shell) and logs `offline,flow=<flow>,airplane=1` -- surface proof the flow ran offline.
- `app/src/androidTest/kotlin/dev/cubentia/app/compat/KeyFlowRotationTest.kt` -- KDoc line on the offline argument.
- `.github/scripts/run-instrumentation.sh` -- run the Key Flow suite with `OFFLINE=1` so every release tag proves NFR-5 on the emulator.
- `docs/evidence/12-3/README.md` (new) + run output -- AC1: local results of `:app:checkReleaseSize` (report copied), `checkReleaseMergedManifest`, `checkReleaseSdkDenylist`, `checkPrivacyFacts`, with commit; AC2: offline emulator run (`docs/evidence/12-3/offline/<avd>/`); AC3 and physical AC2: PO table with blank rows for the deferred items.
- `docs/release.md` -- "Before production" section: vitals review (crash-free sessions >= 99.5%, user-perceived ANR below Play's bad-behavior threshold, read for the closed-testing versionCode), data-safety form check, record in the evidence README; offline Key Flow note in the release-flow paragraph.
- `docs/release-gates-v1.0.md` -- gate 5 offline evidence: point at the offline Key Flow run instead of "No recorded offline device run" (device run still open).

**Acceptance Criteria:**
- Given the release AAB, when `./gradlew spotlessCheck build :app:checkReleaseSize` runs, then the size estimate is < 30,000,000 bytes, `checkReleaseMergedManifest` reports no `INTERNET` and no runtime permission, `checkReleaseSdkDenylist` finds no denylisted SDK, and the results are recorded in `docs/evidence/12-3/README.md`.
- Given a cleared install on an emulator with airplane mode on, when the Key Flow suite runs in offline mode, then flows 1, 2, 3 and 6 (Course, Library, cube entry and solve) and the rest pass, and each flow's log shows it saw airplane mode on.
- Given the release-tag workflow, when its instrumentation job runs, then the Key Flow suite runs offline.
- Given the AC3 human steps, when this story completes, then `docs/release.md` holds the runbook and each human step is a PO deferred item.

## Spec Change Log

## Review Triage Log

### 2026-11-20 — Review pass
- verdicts: 27 findings — high 0, medium 10, low 9, false 7, maybe-false 1
- findings:
  - `[medium]` `[patch]` (blind) Offline check reads the airplane setting, not the network — harness now also requires `dumpsys connectivity` "Active default network: none"; logs `network=none`; Wi-Fi-on negative case fails as expected.
  - `[low]` `[patch]` (blind) Offline proof overstated given no INTERNET permission — README AC2 sentence added on what the run proves.
  - `[medium]` `[patch]` (blind) Headless emulator screen wake done by hand, not in scripts — host script sends KEYCODE_WAKEUP and `wm dismiss-keyguard` before the methods.
  - `[false]` `[reject]` (blind) Restore does not wait after disable — `cmd connectivity airplane-mode disable` writes the setting synchronously; every restore read back 0 immediately in three runs.
  - `[low]` `[reject]` (blind) No guard against OFFLINE=1 on a physical device — the caller picks the serial, header and docs/release.md say emulator only; a guard adds a branch for an unlikely misuse.
  - `[low]` `[reject]` (blind) Failure-path checks only in prose — re-verified by the build runner in this pass (not-offline failure, restore after failed run); committing extra logs adds nothing.
  - `[low]` `[reject]` (blind) Evidence recorded on a dirty tree — the tree is this story's commit; same pattern as 12.2 evidence.
  - `[false]` `[reject]` (blind) NFR-11 rests on an estimate — AC1 is "when CI checks run" and the CI size check is the Story 3.8 estimate by design (AD-14 AAB size check).
  - `[medium]` `[patch]` (blind) Vitals runbook metric names and thresholds — release.md step 1 maps 99.5% crash-free to <= 0.5% user-perceived crash rate and states 1.09% / 0.47% thresholds to confirm in Console.
  - `[false]` `[reject]` (blind) No evidence for "release logs never contain Cube States" — `git grep` finds no Log/println in app/src/main or core main sources; not a 12.3 AC.
  - `[medium]` `[patch]` (blind) Epic-context rewrite dropped the epic-6-retro-item-1 note — note restored with the SCP reference and the AC mismatch; the item itself deferred as a PO decision.
  - `[low]` `[reject]` (blind) CI offline output lands under the 12-2 folder — cosmetic; path unchanged keeps the existing summary/upload wiring.
  - `[medium]` `[patch]` (blind) CI does not check per-flow offline lines — grouped with the gap finding; fixed there.
  - `[low]` `[patch]` (blind) Physical item omits cold start and resume from gate 5 — added to release.md step 3 and README PO row.
  - `[low]` `[patch]` (edge) Empty airplane read-back treated as 0 — empty read is now an error (return 1); only "null" maps to 0.
  - `[medium]` `[patch]` (edge) Network may stay up in airplane mode — same root cause as blind finding 1; same fix.
  - `[low]` `[reject]` (edge) Airplane turned off mid-run not checked — each flow checks at its start; mid-flow toggling is unlikely and a fix adds a guard.
  - `[low]` `[patch]` (edge) Inherited EVIDENCE_DIR could move the CI summary — CI line passes EVIDENCE_DIR=docs/evidence/12-2/keyflow explicitly.
  - `[medium]` `[patch]` (edge, claim) Harness "asserts offline" only reads a setting — same fix as blind finding 1.
  - `[medium]` `[patch]` (gap) Release gate never checks each flow's offline assertion ran — run-instrumentation.sh requires `offline,flow=.*airplane=1` in every method's logcat, else ::error:: exit 1; tested against fake files by the implementer.
  - `[false]` `[reject]` (intent) AC1 evidence is local, not CI — ci.yml runs checkReleaseSize, merged-manifest, denylist and privacy-facts gates on every PR, including this one.
  - `[false]` `[reject]` (intent) Installed size is an estimate — same refutation as blind NFR-11 finding.
  - `[maybe-false]` `[defer]` (intent) Release build never run offline (debug only) — deferred, medium unverified; settle with a benchmark-build offline run or the PO physical release check.
  - `[medium]` `[patch]` (intent) Airplane setting vs actual connectivity — same fix as blind finding 1.
  - `[false]` `[reject]` (intent) No release-tag run of offline mode yet — needs a release tag, an operator action outside this story; documented.
  - `[medium]` `[patch]` (intent) epic-6-retro-item-1 excluded and context line removed — context note restored; implementing the item is excluded by the intent ("exactly this story's acceptance criteria"), so it is deferred as a PO decision.
  - `[false]` `[reject]` (intent) AD-15/AD-16 re-stated, not exercised — the diff touches neither; DebugStrictMode.kt and release.yml mapping upload exist unchanged, no deviation.

## Verification

**Commands:**
- `./gradlew spotlessCheck build` -- expected: BUILD SUCCESSFUL
- `./gradlew :app:checkReleaseSize` -- expected: estimate below budget
- `bash -n tools/run-keyflow-rotation.sh .github/scripts/run-instrumentation.sh` -- expected: no syntax error
- emulator `GalaxyS24FE_API36` headless, then `./gradlew :app:assembleDebug :app:assembleDebugAndroidTest && OFFLINE=1 EVIDENCE_DIR=docs/evidence/12-3/offline tools/run-keyflow-rotation.sh emulator-5554 avd-GalaxyS24FE_API36` -- expected: every method `pass`, airplane restored

## Auto Run Result

Status: done

**Summary:** Story 12.3's automatable parts. AC1 gates (size, manifest, SDK denylist, privacy facts) already existed and were run and recorded. AC2 gained an opt-in offline mode for the Key Flow suite (airplane mode on, per-flow check of airplane mode and no default network, restore on exit), run on the GalaxyS24FE_API36 emulator (7/7 pass) and wired into release-tag CI with a per-flow log check. AC3 got a "Before production" runbook; the human steps are PO deferred items.

**Files changed:**
- `tools/run-keyflow-rotation.sh` -- OFFLINE=1 mode, EVIDENCE_DIR override, screen wake, airplane restore + verify in the EXIT trap.
- `app/src/androidTest/kotlin/dev/cubentia/app/compat/KeyFlowHarness.kt` -- offline check (airplane_mode_on=1 and "Active default network: none") with evidence log line.
- `app/src/androidTest/kotlin/dev/cubentia/app/compat/KeyFlowRotationTest.kt` -- KDoc for offline mode.
- `.github/scripts/run-instrumentation.sh` -- release-tag Key Flow suite runs offline; every method must log its offline check.
- `docs/evidence/12-3/` -- README (AC1 results, AC2 table, PO items), release-size.txt, offline run output.
- `docs/release.md` -- "Before production" (vitals, data safety, physical offline check) and offline note.
- `docs/release-gates-v1.0.md` -- gate 5 offline evidence.
- `_bmad-output/implementation-artifacts/epic-12-context.md` -- recompiled (stale), with the SCP note on epic-6-retro-item-1.

**Review findings:** 27 findings; 10 patch entries applied (medium: network check, screen wake, vitals runbook, epic-context note, CI per-flow check; low: proof wording, cold start/resume, empty read-back, explicit EVIDENCE_DIR); 2 deferred (release-build offline run, medium unverified; epic-6-retro-item-1 PO decision); 15 rejected with reasons in the triage log.

**Follow-up review recommended:** true (patched: 0 high, 5 medium entries, 4 low entries — counting grouped entries once). Unverified risk: the new offline mode (airplane toggle, `dumpsys connectivity` parsing, CI logcat check) has run only on the local API 36 emulator, not on the API 34 CI emulator of the release workflow.

**Verification:** `./gradlew spotlessCheck build :app:checkReleaseSize :app:assembleDebugAndroidTest` BUILD SUCCESSFUL (estimate 12,132,501 < 30,000,000 bytes); `bash -n` on both scripts ok; offline Key Flow suite on GalaxyS24FE_API36 7/7 pass with `airplane=1,network=none` per flow, airplane restored to 0; default (online) mode run of flow8 passes with no airplane change; `-e offline true` with airplane off fails "flow8: device is not offline (airplane_mode_on=0)"; offline run with a failing method restores airplane to 0; Wi-Fi left on in airplane mode fails "network still active in airplane mode".

**Residual risks:** offline evidence is the debug build on an emulator; release-build offline, vitals and data safety are PO items. Release-tag CI run of offline mode not yet observed.
