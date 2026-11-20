---
title: 'GH #119: Lenovo minimum device — rotation harness, script line endings, limitations docs'
type: 'chore'
created: '2026-11-19'
status: 'done'
baseline_revision: 'd332d9100817c60f2a742cfcc95291a6f4587503'
review_loop_iteration: 0
followup_review_recommended: false
context:
  - '{project-root}/_bmad-output/planning-artifacts/sprint-change-proposal-2026-11-12-lenovo-minimum-reference.md'
warnings: ['oversized']
deferred:
  - summary: 'Re-run tools/run-keyflow-rotation.sh on the Lenovo TB128XU with default settings (wm get-ignore-orientation-request = true, no override) and record the result in docs/benchmarks.md § Story 12.2 AC3 on the minimum device.'
    evidence: 'No physical Lenovo is attached during this build (PO clarification); the harness change is compiled but unverified on the device it targets.'
    location: 'docs/benchmarks.md (Lenovo AC3 subsection); app/src/androidTest/kotlin/dev/cubentia/app/compat/KeyFlowHarness.kt'
    severity: medium
    kind: device-session-check
  - summary: >-
      Add a CI guard that the *.sh LF rule holds (e.g. git check-attr eol on tools/run-keyflow-rotation.sh).
    evidence: |-
      .gitattributes now forces *.sh to LF, but no CI step checks it; CI runs on ubuntu-latest where autocrlf is off, so a removed rule would only show on Windows/WSL checkouts.
    location: >-
      .gitattributes:9
    severity: low
  - summary: >-
      PR CI (ci.yml, spotlessCheck build) never compiles androidTest sources, so a KeyFlowHarness compile break surfaces only at release.
    evidence: |-
      Pre-existing: build --dry-run lists no compileDebugAndroidTestKotlin; the suite runs only in release.yml. Not caused by #119.
    location: >-
      .github/workflows/ci.yml
    severity: low
---

<intent-contract>

## Intent

**Problem:** The Lenovo Tab M10 Plus 3rd Gen (TB128XU) is now the minimum reference device (SCP 2026-11-12), but the Key Flow rotation suite cannot rotate the app on it with default settings (Android 13 large screens ignore `Activity.requestedOrientation`), `tools/*.sh` check out with CRLF under `core.autocrlf=true`, and the run's limitations (#119 items 2–7) and the device's new role are not recorded in the reference-device docs.

**Approach:** Make `KeyFlowHarness` rotate the display itself through UI Automator (`UiDevice.setOrientationPortrait/Landscape`, unfreeze on teardown), have the host script restore the device's exact rotation settings, add `*.sh text eol=lf` to `.gitattributes`, and update `docs/benchmarks.md`, `docs/ARCHITECTURE.md` and `docs/emulators.md` to name the Lenovo as minimum and record items 2–7.

## Boundaries & Constraints

**Always:** Keep every existing rotation check (session unchanged, visible cube/step unchanged, layout follows window class) and the per-surface log lines unchanged in format. The host script must leave `accelerometer_rotation` and `user_rotation` exactly as it found them. Tab S7 FE results stay as dated evidence (annotate, don't rewrite measurements). UI Automator is test-only (`androidTestImplementation`), version from `gradle/libs.versions.toml`.

**Never:** No app (`src/main`) code change. No `wm set-ignore-orientation-request` or other persistent device-setting changes left behind. No planning-artifact, `_bmad/`, `.claude/skills/` or spine edits. No new AVD, no Learner-facing copy change (the copy was done in #123). Do not mark the Lenovo default-settings run as passed — it is deferred.

## I/O & Edge-Case Matrix

| Scenario | Input / State | Expected Output / Behavior | Error Handling |
|----------|--------------|---------------------------|----------------|
| Large screen ignores orientation requests | Lenovo, `ignore-orientation-request=true`, auto-rotate off | Display rotates portrait → landscape → portrait; activity configuration follows | Times out with the existing "timed out waiting for rotation to …" message |
| Phone honors requests | S24 FE / AVDs | Same flows pass as before via display rotation | same |
| Natural-landscape tablet | Tab S7 FE (natural landscape) | `setOrientationPortrait` picks the right rotation from current display dims | same |
| Teardown | any device | Rotation unfrozen in `@After`; script restores the recorded `accelerometer_rotation`/`user_rotation` on exit (also on failure) | restore is best-effort per key |

</intent-contract>

## Code Map

- `app/src/androidTest/kotlin/dev/cubentia/app/compat/KeyFlowHarness.kt` -- `rotate(orientation)` (sets `requestedOrientation`, awaits config orientation + recreation), `portrait()`, `restoreOrientation()`, class KDoc ("never device settings"). Change point.
- `app/src/androidTest/kotlin/dev/cubentia/app/compat/KeyFlowRotationTest.kt` -- `@Before portraitFirst`, `@After restoreOrientation`; class KDoc mentions rotation method only indirectly. Reads harness API only.
- `app/build.gradle.kts:79-85` -- androidTest deps; add `androidTestImplementation(libs.androidx.test.uiautomator)` (alias exists, `gradle/libs.versions.toml:26,81`, used by `:macrobenchmark`).
- UI Automator 2.4.0 `UiDevice.setOrientationPortrait()/setOrientationLandscape()` freeze rotation via `UiAutomation.setRotation`, choosing 0/90 from current display dims and natural orientation; `unfreezeRotation()` thaws (sets auto-rotate on), hence the script restore.
- `tools/run-keyflow-rotation.sh` -- header comment claims "Touches no device setting"; `rotation()` prints settings before/after. Add save/restore trap and record `wm get-ignore-orientation-request`.
- `.gitattributes` -- only `gradlew` forced LF. `git ls-files --eol '*.sh'`: index LF, three working copies CRLF.
- `docs/benchmarks.md:474-658` -- Lenovo section: heading `:474`, "Not a reference device" `:476`, AC3 subsection `:585-617`, summary table `:636-648` (NFR-1/NFR-2 labels swapped: PRD NFR-1 = input ≤ 100 ms, NFR-2 = FPS), PO questions `:650-658`, Out of scope "~8 s" `:665`.
- `docs/ARCHITECTURE.md:201` -- open-questions row names Tab S7 FE / S24 FE as reference devices.
- `docs/emulators.md:3` -- intro says AVDs simulate "the project's two reference devices".
- `core/domain/src/main/resources/content/strings/en.json:67` -- `msg.solve.preparing` already reworded by #123 (read-only, cite in docs).

## Tasks & Acceptance

**Execution:**
- `app/build.gradle.kts` -- add UI Automator as `androidTestImplementation` -- display rotation API.
- `app/src/androidTest/kotlin/dev/cubentia/app/compat/KeyFlowHarness.kt` -- `rotate()` rotates the display with `UiDevice.setOrientationLandscape/Portrait` and leaves `requestedOrientation` UNSPECIFIED (the app follows the system, as a Learner rotates); `restoreOrientation()` resets `requestedOrientation` and calls `unfreezeRotation()`; update KDoc -- #119 item 1.
- `app/src/androidTest/kotlin/dev/cubentia/app/compat/KeyFlowRotationTest.kt` -- KDoc wording only if it describes the rotation method.
- `tools/run-keyflow-rotation.sh` -- record `wm get-ignore-orientation-request` in `device.txt`; save `accelerometer_rotation`/`user_rotation` before, restore on EXIT trap; fix header comment -- leaves the device as found.
- `.gitattributes` -- add `*.sh text eol=lf` with a short comment; re-checkout the CRLF `*.sh` working copies -- #119 item 8.
- `docs/benchmarks.md` -- heading/intro: Lenovo is the minimum reference device (SCP 2026-11-12), Tab S7 FE an additional compatibility device whose "minimum" results are dated evidence; AC3: note the harness now rotates the display and the line-ending fix, default-settings re-run pending; summary table: correct NFR-1/NFR-2 labels and the Tab column label; replace PO questions with the decisions; add "Known limitations on the minimum device (#119)" covering items 2–7 with disposition (accepted / tracked #104); fix Out of scope "~8 s".
- `docs/ARCHITECTURE.md:201` -- name Lenovo (minimum), S24 FE (mid), Tab S7 FE (compatibility).
- `docs/emulators.md:3` -- say the AVDs simulate the mid device and the compatibility tablet; the minimum Lenovo has no AVD and is measured physically.

**Acceptance Criteria:**
- Given the branch, when `./gradlew :app:compileDebugAndroidTestKotlin spotlessCheck build` runs, then it succeeds.
- Given `KeyFlowHarness.rotate`, when read, then it rotates the display via `UiDevice` and does not depend on `Activity.requestedOrientation` being honored.
- Given a fresh checkout with `core.autocrlf=true`, when `git ls-files --eol '*.sh'` runs, then every `*.sh` shows `attr/text eol=lf` and `w/lf`.
- Given `docs/benchmarks.md`, when read, then it no longer says the Lenovo is "Not a reference device", the summary labels FPS rows NFR-2 and latency rows NFR-1, and items 2–7 each appear with a disposition.
- Given `docs/ARCHITECTURE.md` and `docs/emulators.md`, when read, then neither calls the Tab S7 FE the current minimum device.

## Spec Change Log

## Review Triage Log

### 2026-11-19 — Review pass
- verdicts: 23 findings — high 0, medium 4, low 13, false 6, maybe-false 0
- findings:
  - `[false]` `[reject]` (intent) New `rotate()` path is never exercised on any device — Refuted: step-03 ran `tools/run-keyflow-rotation.sh` on the GalaxyTabS7FE_API34 AVD with `wm set-ignore-orientation-request true` (flows 1, 8, 9: 11+2+5 surfaces, all pass) and on GalaxyS24FE_API36 (flows 8, 9 pass); the physical Lenovo default-settings run stays deferred as the PO asked.
  - `[false]` `[reject]` (intent) Replacing `requestedOrientation` regresses other targets unverified — Refuted for the AVD targets by the runs above (natural-landscape tablet and phone both pass); physical Samsung re-runs are not required by the intent.
  - `[medium]` `[patch]` (intent) Script now writes device settings; runs outside the script keep auto-rotate on — Grouped with the blind/edge restore findings; fixed by the harness restoring the saved settings itself (see below).
  - `[low]` `[reject]` (intent) Docs edits reach beyond the cited lines — The intent asks to record the minimum-device change and items 2–7 in those files; the extra edits are what makes those sections consistent, and none is wrong.
  - `[low]` `[reject]` (intent) The item 8 re-checkout leaves no trace in the diff — Local working-copy step only; the `.gitattributes` rule is the shipped fix; existing-clone command added to the docs (blind finding 5).
  - `[medium]` `[patch]` (blind) `unfreezeRotation()` leaves auto-rotate on when the suite runs without the script — Real: `release.yml` and Studio runs bypass the trap. Patched: harness records `accelerometer_rotation`/`user_rotation` before the first freeze and writes them back in `restoreOrientation()`.
  - `[false]` `[reject]` (blind) No regression run on an AVD — Refuted by the step-03 AVD runs listed above.
  - `[low]` `[patch]` (blind) `docs/benchmarks.md:343` still describes the old `requestedOrientation` method unqualified — Patched with a note that this was the method at the time and the #119 harness now rotates the display.
  - `[low]` `[reject]` (blind) The deferred Lenovo re-run has no GitHub tracking issue — The spec's deferred list and the "Pending" line are the tracking the intent asked for; this build must not change issues.
  - `[low]` `[patch]` (blind) Existing CRLF clones need a re-checkout command — Patched: one sentence with the command in the Lenovo AC3 line-endings bullet.
  - `[low]` `[patch]` (blind) `ARCHITECTURE.md:201` p90 figures unlabelled — Patched: labelled face turns / View Rotation, Story 12.2 Macrobenchmark.
  - `[low]` `[reject]` (blind) `wm get-ignore-orientation-request` logs a shell error below API 31 — The captured error text in `device.txt` still tells the reader the command is unsupported there; guarding adds a branch for a cosmetic evidence line.
  - `[low]` `[reject]` (blind) Restore could write back junk from a flaky `settings get` — Not shown to happen (`settings get` returns the value or `null`); validation guards add branches for an unobserved case, and the new post-restore check now reports any mismatch.
  - `[low]` `[reject]` (blind) `rotate(orientation: Int)` takes `ActivityInfo` constants no longer used as requests — Naming only; the mapping is correct and the API is private.
  - `[low]` `[reject]` (blind) Spec logs empty and `oversized` unexplained — The workflow fills these logs; the fix would be to edit this build's spec.
  - `[medium]` `[patch]` (edge) Interrupted run: on-device `@After unfreezeRotation` runs after the script's restore and leaves auto-rotate on — Same root cause as the restore group; the harness restoring the saved values itself means a late `@After` puts the original settings back.
  - `[medium]` `[patch]` (edge) Outside the script, `accelerometer_rotation` stays at 1 — Duplicate of the blind restore finding; same patch.
  - `[false]` `[reject]` (edge) Multi-window/square display: `setOrientation*` freezes only, await times out — The suite runs the full-screen `MainActivity` via `ActivityScenarioRule`; if that ever happened, the 15 s await fails loudly with the existing message, which is correct behavior.
  - `[false]` `[reject]` (edge, claim) "Leaves the device as found" fails on interrupted or out-of-script runs — After the patch the harness restores the settings itself, and the script restores and now checks them; the claim no longer holds at the patched code.
  - `[false]` `[reject]` (verification-gap) No automated run where ignore-orientation-request = true — The step-03 Tab AVD run used `ignore-orientation-request=true` and passed; the physical Lenovo run is the deferred device-session check.
  - `[low]` `[patch]` (verification-gap) Nothing checks that the script restored the rotation settings — Patched: `restore_rotation` re-reads both keys and exits non-zero on a mismatch, so the release gate fails.
  - `[low]` `[defer]` (verification-gap) The `*.sh` LF rule has no CI check — Filed disposition defer; failure shows only on autocrlf checkouts.
  - `[low]` `[defer]` (verification-gap, other) PR CI never compiles androidTest — Pre-existing, not caused by #119; deferred.

## Design Notes

Display-only rotation (no `requestedOrientation`): setting both a fixed request and a frozen display rotation can pass through a transient letterboxed state on large screens (request honored per-activity while the display is still the other way), which the await could accept before the second configuration change. With the request UNSPECIFIED the app simply follows the display on every device, which is also how a Learner rotates. AD-17 is method-neutral (SCP 2026-11-12 §4.5).

## Verification

**Commands:**
- `./gradlew :app:compileDebugAndroidTestKotlin spotlessCheck build` -- expected: BUILD SUCCESSFUL
- `bash -n tools/run-keyflow-rotation.sh` -- expected: no syntax errors
- `git ls-files --eol '*.sh'` -- expected: all `w/lf`, `attr/text eol=lf`

## Auto Run Result

Status: done

**Summary.** `KeyFlowHarness` now rotates the display itself through UI Automator (`UiDevice.setOrientationPortrait/Landscape`) and leaves `requestedOrientation` unspecified, so the rotation suite no longer depends on orientation requests being honored (#119 item 1). The harness records and restores `accelerometer_rotation`/`user_rotation`, and `tools/run-keyflow-rotation.sh` restores and checks them as a backstop. `.gitattributes` forces `*.sh` to LF (item 8). The docs now name the Lenovo TB128XU as the minimum reference device, record items 2–7 with dispositions, and correct the NFR-1/NFR-2 labels.

**Files changed**
- `.gitattributes` — `*.sh text eol=lf`.
- `app/build.gradle.kts` — UI Automator as `androidTestImplementation`.
- `app/src/androidTest/kotlin/dev/cubentia/app/compat/KeyFlowHarness.kt` — display rotation via `UiDevice`; saves and restores the rotation settings; unfreezes on teardown.
- `tools/run-keyflow-rotation.sh` — records `wm get-ignore-orientation-request`; EXIT-trap restore of the rotation settings, with a check that fails the run on a mismatch.
- `docs/benchmarks.md` — Lenovo section becomes the minimum reference device (old anchor kept); AC3 harness changes, with the default-settings re-run pending; NFR labels corrected; PO decisions; "Known limitations on the minimum device (#119)" table for items 2–7; dated note on the earlier `requestedOrientation` method; re-checkout command for existing clones; Out of scope corrected.
- `docs/ARCHITECTURE.md:201` — Lenovo (minimum), S24 FE (mid), Tab S7 FE (compatibility); labelled p90 figures.
- `docs/emulators.md:3` — AVDs simulate the mid device and the compatibility tablet; the Lenovo has no AVD.

**Review findings.** 23 findings: 4 medium, 13 low, 6 false.
- Patches applied: 1 medium group (restore the rotation settings in the harness: 4 rows) and 5 low (benchmarks:343 note, re-checkout command, ARCHITECTURE labels, script restore check; the medium group's script side).
- Deferred: the CI LF guard (low) and PR CI not compiling androidTest (low, pre-existing), plus the planned medium device-session check (Lenovo default-settings run).
- Rejected: the docs reach beyond the cited lines (in scope); the re-checkout leaving no trace in the diff (local step only); no tracking issue (the spec's deferred list is the tracking, and this build must not change issues); the API <31 `wm` error text (cosmetic); junk-value validation (unobserved); `rotate(Int)` naming (private, cosmetic); the empty spec logs (the fix would edit this build's spec); and 6 false findings, refuted by the AVD runs or by the full-screen activity.

**Follow-up review recommendation:** false. Patched: 0 high, 1 medium entry, 5 low.

**Verification**
- `./gradlew :app:compileDebugAndroidTestKotlin spotlessCheck build :app:assembleDebugAndroidTest` passed (EXIT=0, after the patches).
- `bash -n tools/run-keyflow-rotation.sh` passed.
- `git ls-files --eol '*.sh'`: all 4 files show `w/lf attr/text eol=lf`.
- Emulator runs (evidence kept out of the repo):
  - GalaxyTabS7FE_API34 with `wm set-ignore-orientation-request true` (the Lenovo-like case, natural-landscape tablet): flow1 (11 surfaces), flow8 (2) and flow9 (5) passed. The script restored `user_rotation` 1 → 0.
  - GalaxyS24FE_API36 (phone): flow8 and flow9 passed. The first attempt on the freshly booted AVD failed with "welcome never appeared" (the camera app was in front after boot); the re-run passed.
  - After the patches, a direct `am instrument` of flow9 (no script) on the Tab AVD with ignore=true and auto-rotate off passed and left `accelerometer_rotation=0 user_rotation=1` as found.

**Residual risks.** Not yet run on the physical Lenovo TB128XU with default settings (deferred, medium). The physical S24 FE and Tab S7 FE were not re-run with the new rotation method; the AVDs stand in for them. The serial `HA1S28MM` (the Lenovo) was listed by `adb devices` during this build but was deliberately not used, per the PO's statement that no Lenovo is attached for this build.
