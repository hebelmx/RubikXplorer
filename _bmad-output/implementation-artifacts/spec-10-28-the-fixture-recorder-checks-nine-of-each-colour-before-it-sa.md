---
title: 'The fixture recorder checks nine of each colour before it saves a corner group'
type: 'feature'
created: '2026-11-24'
status: 'done'
baseline_revision: '6e8cf79cd2cbe84827e57a2a3c27b25d24924c8f'
review_loop_iteration: 0
followup_review_recommended: true
context:
  - '{project-root}/_bmad-output/implementation-artifacts/epic-10-context.md'
warnings: ['oversized']
deferred:
  - summary: >-
      The recorder screen's wiring of the count check, refusal, side-face lock and reviewed flags has no screen-level test.
    evidence: |-
      RecorderRulesTest and FixtureExportTest cover the pure rules only; no test drives confirmCornerFace, retakeHold or saveOverride. Needs a device session check or a pure post-write decision function.
    location: >-
      app/src/camera/debug/kotlin/dev/cubentia/app/camera/recorder/FixtureRecorderScreen.kt
    severity: medium
  - summary: >-
      Python tool tests (test_corner_groups, test_assemble) do not run in CI.
    evidence: |-
      .github/workflows/ci.yml runs Gradle only; test_assemble needs TensorFlow. Pre-existing.
    location: >-
      tools/sticker-model
    severity: low
  - summary: >-
      The recompiled epic-10-context.md may have dropped 10.24-10.26 rules (retake merge, corner naming, cube-rule fill).
    evidence: |-
      Blind review of the cache diff; the file is the workflow's compiled context, regenerated after epics.md changed. Compare with the planning docs at the next compile.
    location: >-
      _bmad-output/implementation-artifacts/epic-10-context.md
    severity: medium (unverified)
---

<intent-contract>

## Intent

**Problem:** The debug corner recorder (Story 10.20) pre-fills each face from the nearest calibrated centre and saves each hold as soon as its three faces are confirmed, so a group whose labels cannot be a real cube (not 9 of each colour) is saved; the 2026-11-23 review corrected 20 of 1,242 labels, 18 of them pre-filled, all 12 WHITE->BLUE on the side faces (#337).

**Approach:** Add a pure count check to `RecorderRules` that runs on the latest labels of both holds once both are complete, refuse to save a failing group (naming over/under colours, highlighting over-coloured pre-fills) unless the operator gives a non-empty override reason stored in a new optional manifest field; list overridden groups separately in the group index and skip them in 10.21 tuning and 10.23 scoring unless the PO names them; block confirming a side face (index 1 or 2) while an untouched pre-fill remains.

Refs (binding): FR-24 · recognition NFRs (Confidence calibration) · AD-9, AD-15 · Stories 10.12, 10.20, 10.21, 10.23, 10.27 · #337, #327, #338

## Boundaries & Constraints

**Always:** AD-9 and AD-15 hold: recorder code stays in the `app/src/camera/debug` developer-tool source set; the manifest field is optional and old manifests still load; the count covers both holds, centres included, always on the latest capture of each hold; the check runs only once both holds are complete.

**Never:** No change to `prefill`, `CLOSE_CALL_DELTA_E`, any classifier threshold, keep rule or agreement gate; no piece check (Story 10.27); no corner-placement work (#338, Story 10.27 AC2); no release-scope or feature-flag change.

## I/O & Edge-Case Matrix

| Scenario | Input / State | Expected Output / Behavior | Error Handling |
|----------|--------------|---------------------------|----------------|
| Balanced | both holds complete, 9 of each colour | group saved | none |
| 10 WHITE / 8 BLUE | both holds complete | refused; "White 10 (over)", "Blue 8 (under)" named; pre-filled WHITE stickers highlighted | not saved |
| Override, reason given | refused group, non-empty reason | saved; reason in group manifest | none |
| Override, empty/blank reason | refused group | override unavailable | not saved |
| Hold 1 only | hold 2 not complete | no count check yet | none |
| Retaken hold | hold N captured again | check uses the latest capture of each hold | none |
| Untouched side-face pre-fill | face index 1 or 2 | Confirm face disabled | none |
| Pre-correction replay | 10 corrected groups at `add0c79e` | all 10 refused | test fails otherwise |

</intent-contract>

## Code Map

- `app/src/camera/debug/kotlin/dev/cubentia/app/camera/recorder/RecorderRules.kt` -- pure recorder rules. `TruthEntry` (~L150-215: `select`, `set`, `withPrefill`, `confirmed`; default `selected = null` already fixes #327), `withHold` (~L584), `groupManifest`. Count check, refusal copy, highlight selection, override-reason rule, next-hold rule and side-face lock go here.
- `app/src/camera/debug/kotlin/dev/cubentia/app/camera/recorder/FixtureRecorderScreen.kt` -- `confirmCornerFace` (L389-450) writes each hold on its third confirm (`writeCornerHold`, `recordHold`) then `cornerHold++`; `newCondition` (L231) and the "Record both corners again" handler (L814-824) start a new group; `stepLine` (L523-545); net slot (L723-751: `Net` + `Palette`); corner action row (L798-844); `Net` composable (L1143-1183).
- `app/src/camera/debug/kotlin/dev/cubentia/app/camera/recorder/FixtureExport.kt` -- `recordHold` (L94) rewrites `group.json` via `withHold`; add a manifest writer for the check result.
- `core/domain/src/main/kotlin/dev/cubentia/domain/recognition/LiveFixture.kt` -- `CornerFixtureRecord` (L133), `CornerGroupManifest` (L227), `CornerGroupIndexEntry`/`CornerGroupIndex` (L251-274), `CornerFixtureJson.validIndex`/`decode` (L353, L381). JSON uses `encodeDefaults = true`; new fields need defaults so old files decode.
- `core/domain/src/test/kotlin/dev/cubentia/domain/recognition/CornerGroupIndexTest.kt` -- index check (`load` L28-77); `QuickScanGateTuningTest.kt` (`tuningGroups` L42-77, 10.21 tuning); `QuickScanSideFaceReplayTest.kt` (`groups` L143-185, 10.21 replay/baseline report); `LiveFixtureTest.kt` (manifest/index round-trips L215-360).
- `tools/sticker-model/assemble.py` -- `assemble_corner` (L214) reads every `Fixtures/corner` folder with no manifest check; this is what feeds 10.23 training and `evaluate.py` scoring; `excluded` list + final print (L360) are the report.
- `app/src/testDebug/kotlin/dev/cubentia/app/camera/recorder/RecorderRulesTest.kt`, `FixtureExportTest.kt` (L125-170, manifest after `recordHold`).
- `docs/evidence/10-27/label-corrections-2026-11-23.json` + `git show add0c79e:Fixtures/corner/<group>/corner-{1,2}.json` -- AC5 input. Verified during planning: the 20 corrections touch 10 groups; at `add0c79e` all 10 are off-count (e.g. `lenovo-tb128xu_stka_cool` WHITE 10 / BLUE 8), all 23 current groups count 9 of each.

## Tasks & Acceptance

**Execution:**
- `core/domain/src/main/kotlin/dev/cubentia/domain/recognition/LiveFixture.kt` -- add `@Serializable enum class CornerCountCheck { PENDING, REFUSED, PASSED, OVERRIDDEN }` (SerialNames lower-case); `CornerGroupManifest` gains `countCheck: CornerCountCheck? = null`, `overrideReason: String? = null` and a non-serialized `saved` (true when `countCheck` is null, PASSED or OVERRIDDEN); `CornerFixtureRecord` gains `reviewed: List<List<Boolean>>? = null` (decode: when non-null, one list of 9 per labeled sample); `CornerGroupIndex` gains `overridden: List<CornerOverriddenEntry> = emptyList()` (`group`, `split`, `reason`, `included: Boolean = false`, `pastel: Boolean = false`), `used()` = `groups` + included overrides as entries, `skippedOverrides()` = names not included, and `entry()` searches `used()`; `validIndex` also rejects blank reasons and a name repeated across both lists; add `CornerGroupUse.of(name, manifest, index)` -> USED / UNSAVED / OVERRIDE_SKIPPED (OVERRIDDEN manifest used only when the index lists it with `included = true`). Schema numbers stay. -- one shared rule for every consumer.
- `core/domain/src/test/kotlin/dev/cubentia/domain/recognition/LiveFixtureTest.kt` -- old manifest/index/record JSON still decodes; new fields round-trip; `CornerGroupUse` cases; blank reason / duplicate name rejected.
- `app/src/camera/debug/kotlin/dev/cubentia/app/camera/recorder/RecorderRules.kt` -- add `CornerHoldLabels(faces, colors, sources, reviewed)`; `colorCount(holds: Map<Int, CornerHoldLabels>): ColorCountCheck?` (null unless holds 1 and 2 are both present; counts all 54 labels, centres included; `over`/`under` maps; `passed`); `countRefusal(check)` copy; `overCountedPrefills(holds, over)` -> `RefusalFace(hold, faceIndex, face, colors, ringed: Set<Int>)` for every face with a PREFILLED sticker of an over colour (hold order, then face index); `overrideReason(text)` = trimmed text or null when blank; `nextCornerHold(complete: Set<Int>)` = first of 1, 2 not complete, else 3; `withHold` also sets `countCheck = PENDING`, `overrideReason = null`; `withCountCheck(manifest, check, reason)`; `cornerFaceLocks(faceIndex, entry)`; `TruthEntry` gains `reviewed` (9 booleans), `tap(index)` (select + reviewed), and `set(color, keepSamePrefill = false)` that marks the cell reviewed and, with `keepSamePrefill` and the same colour on a PREFILLED cell, keeps PREFILLED; `select`, `withPrefill`, `suggestAgain` never mark reviewed (fresh entries are all false).
- `app/src/camera/debug/kotlin/dev/cubentia/app/camera/recorder/FixtureExport.kt` -- add `recordCountCheck(directory, check, reason)` rewriting `group.json` like `recordHold`.
- `app/src/camera/debug/kotlin/dev/cubentia/app/camera/recorder/FixtureRecorderScreen.kt` -- implement the UI behaviour in Design Notes: keep `groupHolds` (latest labels per hold, cleared with the group), `refusal`, override dialog state; corner net taps use `tap`, corner palette uses `set(color, keepSamePrefill = true)`; write `reviewed` into each `CornerFixtureRecord`; Confirm face disabled by `cornerFaceLocks`; `Net` gains `ringed` and read-only use.
- `app/src/testDebug/kotlin/dev/cubentia/app/camera/recorder/RecorderRulesTest.kt` -- AC4 cases, I/O matrix rows (balanced, 10 WHITE / 8 BLUE with names and ringed pre-fills, hold 1 only -> null, retaken hold replaces, blank/space reason -> null, override writes reason to the manifest), side-face lock (face 1/2 locked until each PREFILLED cell is tapped or colour-picked, face 0 never locked, auto-selection does not count, same-colour pick keeps PREFILLED and reviewed), `nextCornerHold`, `withHold` resets to PENDING.
- `app/src/testDebug/resources/recorder/pre-correction-labels-add0c79e.json` + a test in `RecorderRulesTest.kt` -- snapshot of the truth labels of hold 1 and 2 of the 10 corrected groups, extracted once with `git show add0c79e:...` (provenance in the file: commit, source paths, corrections file); the test asserts every group is refused by `colorCount` and that the snapshot covers exactly the groups in the corrections file (read via `../docs/evidence/10-27/label-corrections-2026-11-23.json`).
- `app/src/testDebug/kotlin/dev/cubentia/app/camera/recorder/FixtureExportTest.kt` -- update the manifest expectations (PENDING after `recordHold`) and cover `recordCountCheck`.
- `core/domain/src/test/kotlin/dev/cubentia/domain/recognition/CornerGroupIndexTest.kt` -- saved non-overridden 10.22 groups must be in `groups`; OVERRIDDEN groups must be in `overridden` with the manifest's reason (problem otherwise); UNSAVED groups are neither required nor allowed in the index; coverage uses `used()`; add temp-dir cases.
- `core/domain/src/test/kotlin/dev/cubentia/domain/recognition/QuickScanGateTuningTest.kt`, `QuickScanSideFaceReplayTest.kt` -- skip groups `CornerGroupUse` does not mark USED; print and write to their reports `Skipped: N overridden (not included by the PO), M unsaved`.
- `tools/sticker-model/corner_groups.py` (new, stdlib only) + `assemble.py` + `test_corner_groups.py` -- same rule as `CornerGroupUse` from `group.json` and `Fixtures/corner/groups.json`; `assemble_corner` puts a skipped group's faces in `excluded` with reason `count check: unsaved` / `count check: override not included by the PO` and the final print adds the skipped group count.

**Acceptance Criteria:**
- Given both holds complete, when the last face of the second hold is confirmed (the finalize), then `RecorderRules` counts both holds' latest labels and the group is marked PASSED, or REFUSED with each off colour named and its count, and the faces with over-coloured pre-fills shown with rings.
- Given a refused group, when the operator overrides with a reason that is non-empty after trimming, then `group.json` holds `countCheck: overridden` and the trimmed reason; a blank reason keeps the save button disabled.
- Given an overridden group, when the group index, 10.21 tuning/replay and 10.23 assembly read it, then it is listed under `overridden` with its reason and skipped unless `included`, and the skipped count is reported; an unsaved (PENDING or REFUSED) group is ignored by all of them.
- Given face index 1 or 2 of a hold with an untouched PREFILLED sticker, when the operator looks at the action row, then Confirm face is disabled and the lock line says how many remain; a rule test pins it.
- Given the pre-correction labels of the 10 corrected groups, when replayed through `colorCount`, then all 10 are refused.
- Given old manifests, records and `groups.json` without the new fields, when decoded, then they load and count as saved.

## Spec Change Log

## Review Triage Log

### 2026-11-24 — Review pass
- verdicts: 31 findings — high 2, medium 5, low 11, false 13, maybe-false 0
- findings:
  - `[false]` `[reject]` (intent) A2 "status flag" reading instead of not writing — PO clarification (1) "Write, mark unsaved" selects A2.
  - `[false]` `[reject]` (intent) B1 tap counts as review — PO clarification (4) says tap confirms.
  - `[false]` `[reject]` (intent) index `overridden` list is hand-written, no producer — groups.json is written by hand after the session by design (10.20).
  - `[false]` `[reject]` (intent) 10.23 scoring filtered one step removed — evaluate.py reads only assembled items, so assemble's skip covers it.
  - `[false]` `[reject]` (intent) pre-correction replay uses a snapshot — verification-gap layer confirmed all 20 holds match `git show add0c79e`.
  - `[false]` `[reject]` (intent) domain change outside the debug set — the manifest type already lived in LiveFixture.kt; fields optional; AD-9/AD-15 hold.
  - `[medium]` `[defer]` (intent) screen wiring tested only through pure rules — same root as the verification-gap screen finding; deferred.
  - `[low]` `[reject]` (intent) "latest capture" kept in memory only — lost state leaves the group PENDING (unsaved, the safe side); matches the existing remember-based recorder state.
  - `[medium]` `[reject]` (edge) count-check write failure clears state while group stays pending — rare disk-IO path, status says "Count check not written"; fix needs retry state (complexity). Logged as low-impact.
  - `[low]` `[reject]` (edge) recordHold failure skips the check — same rare IO path, status reports the failure.
  - `[false]` `[reject]` (edge) corner-model trains/evaluates unsaved groups — `training_allowed` refuses unlisted 10.22 groups and evaluation defaults to the held-out split; unsaved groups are never listed.
  - `[low]` `[patch]` (edge) ColorCalibrationTest uses `groups` so an included pastel override is skipped — now `.used()`.
  - `[false]` `[reject]` (edge) skipped count undercounts groups with no labelled faces — a count check is only written with a hold that always has three labelled faces.
  - `[low]` `[reject]` (edge) Python treats unknown countCheck/non-bool included differently from Kotlin — hand-edit only; Kotlin decode throws first in CI.
  - `[low]` `[reject]` (edge) Python index does not validate blank reason/duplicate names — Kotlin CornerGroupIndexTest enforces it in CI.
  - `[false]` `[reject]` (edge, claim) skipped count understated — same refutation as above.
  - `[medium]` `[reject]` (blind) failed count-check write still looks saved — duplicate of the edge IO finding; rare, reported in status.
  - `[low]` `[reject]` (blind) refusal not resumable after process death — group stays unsaved (safe); recorder state was already in-memory.
  - `[low]` `[reject]` (blind) no way back from Retake to the refusal choices — the operator can capture or Record both again; adds state.
  - `[low]` `[reject]` (blind) override does not store the counts — not required by the intent; PO can recount from labels; adds a field.
  - `[false]` `[reject]` (blind) tools/corner-model not updated — see corner-model refutation above.
  - `[low]` `[defer]` (blind) Python tests not run in CI — pre-existing: CI runs Gradle only.
  - `[medium]` `[patch]` (blind) test_every_committed_corner_group_is_used breaks on the first committed override — now forbids only UNSAVED.
  - `[low]` `[reject]` (blind) skippedLine duplicated / KDoc indentation / flat walk — spotlessCheck passes; Fixtures/corner is flat.
  - `[medium]` `[defer]` (blind) screen flow untested — same as verification-gap screen finding.
  - `[false]` `[reject]` (blind) "1 pre-filled stickers" copy and bare tap unlock — copy is the spec's exact copy; tap counts per PO clarification (4).
  - `[medium]` `[defer]` (blind) epic-10-context.md recompile drops 10.24–10.26 rules — workflow-generated cache recompiled after epics.md changed; not this story's code; deferred for the next compile.
  - `[high]` `[patch]` (vgap) assemble_corner skip crashes on overridden groups (`index` shadowed) — renamed to `group_index`.
  - `[medium]` `[patch]` (vgap) 10.21 tuning `used()`/CornerGroupUse filter untested — added a @TempDir test in QuickScanGateTuningTest (included override tuned, others skipped, skipped line asserted).
  - `[medium]` `[defer]` (vgap) recorder screen wiring untested — debug tool without Compose tests; device session covers it.
  - `[high]` `[patch]` (vgap other) assemble.py `index` variable bug — same fix as above.

## Design Notes

PO clarifications 2026-11-24 (binding): (1) write each hold at once; a refused group stays on disk marked unsaved and the index and consumers ignore it. (2) after refusal: retake one hold, or Override with a reason dialog. (3) per-face outline: list the faces holding pre-filled stickers of over colours; each face's net rings those stickers, never tints them. (4) tap or colour pick confirms a pre-fill; an unchanged sticker keeps PREFILLED and gets a `reviewed` flag; automatic selection does not count. (5) Override dialog with a reason field; spaces only counts as empty.

AD-9 and AD-15 hold: all recorder code stays in `app/src/camera/debug`; domain changes are plain serializable models, no camera types. No known deviation.

Settled UI behaviour (copy is exact):
- Manifest lifecycle: every hold write sets `countCheck = pending` (a group with only keypoint/negative captures keeps `null`, so supplemental-only folders stay usable). When both holds of the current group are in `groupHolds`, the check runs right after the hold write: PASSED -> status `Saved corner group <folder> · 9 of each colour.`; REFUSED -> refusal shown. Next hold after a write = `nextCornerHold` (a retaken hold 1 with hold 2 present goes straight to the check). No animation, no auto-dismiss.
- Refusal: step line `Step 3/3 · Group not saved: colour counts are off`; a non-truncated error-coloured line `Not saved: White 10 (over) · Blue 8 (under)` (off colours in `Color.entries` order); the net slot (net and palette) shows a horizontally scrollable row of read-only nets, each captioned `Corner <hold> · <Centre colour> face`, with each ringed sticker drawn with a 3.dp Magenta border (fill unchanged); with no such face the slot shows `No pre-filled stickers of the over-counted colours.`. Actions: `Retake corner 1`, `Retake corner 2`, `Override…`, `Record both corners again`; Keypoints-only / negative row unchanged.
- Retake corner N: clears the refusal from the screen (`group.json` stays refused), sets the hold to N, same group folder, status `Retake corner N: capture it again.`; the check reruns when it is written, on the latest labels of both holds.
- Override dialog: title `Override the count check`; text = the refusal line + ` Tuning and scoring skip this group unless the PO includes it.`; field label `Reason` (empty each time it opens); buttons `Save with reason` (enabled only when `overrideReason` is non-null) and `Cancel`. Save -> `countCheck: overridden` + trimmed reason, refusal cleared, status `Saved corner group <folder> with an override.`.
- Refusal and `groupHolds` clear on Record both corners again, `newCondition` and a successful override/pass.
- Side-face lock line (corner labelling, face index 1 or 2): `Confirm locked: tap the N pre-filled stickers not checked yet` (error colour). Net: unreviewed pre-fill italic (as today), reviewed pre-fill upright normal weight, entered bold.

## Verification

**Commands:**
- `cd /mnt/e/Dynamic/IndFun/RubikXplorer-10-28 && export JAVA_HOME=/usr/lib/jvm/java-25-openjdk-amd64 ANDROID_HOME=$HOME/Android/Sdk && ./gradlew spotlessApply && ./gradlew :core:domain:test :app:testDebugUnitTest --tests '*recorder*'` -- expected: pass.
- `cd /mnt/e/Dynamic/IndFun/RubikXplorer-10-28/tools/sticker-model && python3 -m unittest test_corner_groups` -- expected: pass.
- `cd /mnt/e/Dynamic/IndFun/RubikXplorer-10-28 && ./gradlew spotlessCheck build` -- expected: BUILD SUCCESSFUL (CI gate).

## Auto Run Result

Status: done

**Summary:** The debug corner recorder now counts both holds' latest labels (centres included) once both are written, marks `group.json` `countCheck` pending/passed/refused/overridden, shows a refusal with off colours and ringed pre-fills per face, offers Retake corner 1/2, Override… (trimmed non-blank reason) and Record both again, and locks Confirm face on side faces until each pre-fill is tapped or colour-picked (`reviewed` flags persisted). The group index gains an `overridden` list; `CornerGroupUse` (Kotlin) and `corner_groups.py` make the index check, 10.21 tuning/replay and 10.23 assembly skip unsaved groups and non-included overrides and report the skipped counts.

**Files changed:**
- `core/domain/.../recognition/LiveFixture.kt` — CornerCountCheck, manifest countCheck/overrideReason/saved, record reviewed, index overridden/used()/skippedOverrides(), CornerGroupUse.
- `app/src/camera/debug/.../recorder/RecorderRules.kt` — colorCount, countRefusal, overCountedPrefills, overrideReason, nextCornerHold, withCountCheck, cornerFaceLocks, TruthEntry reviewed/tap/set(keepSamePrefill).
- `app/src/camera/debug/.../recorder/FixtureExport.kt` — recordCountCheck.
- `app/src/camera/debug/.../recorder/FixtureRecorderScreen.kt` — finalize/refusal/retake/override UI, side-face lock, ringed read-only nets.
- `app/src/testDebug/.../recorder/RecorderRulesTest.kt`, `FixtureExportTest.kt`, `app/src/testDebug/resources/recorder/pre-correction-labels-add0c79e.json` — rule tests and the AC5 snapshot.
- `core/domain/src/test/.../CornerGroupIndexTest.kt`, `LiveFixtureTest.kt`, `QuickScanGateTuningTest.kt`, `QuickScanSideFaceReplayTest.kt`, `ColorCalibrationTest.kt` — consumer skip rules and reports.
- `tools/sticker-model/corner_groups.py`, `test_corner_groups.py`, `assemble.py` — 10.23 skip rule.
- `_bmad-output/implementation-artifacts/epic-10-context.md` — recompiled epic context (workflow cache).

**Review:** 31 findings; patched 4 (high 1 — counted once as the assemble `index` shadowing reported by two layers, medium 2, low 1); deferred 3 (screen wiring untested, Python tests outside CI, epic-context content check); 13 false and the rest rejected as recorded in the triage log.

**Follow-up review recommended:** true — a high was patched: the `assemble_corner` skip path (renamed `group_index`) is only parse-checked, never executed, because the assembly needs TensorFlow.

**Verification:** `./gradlew spotlessCheck build` BUILD SUCCESSFUL (after patches); `RecorderRulesTest` 51/51, `LiveFixtureTest` 12, `CornerGroupIndexTest` 4, `QuickScanSideFaceReplayTest` 11, `QuickScanGateTuningTest` 2, 0 skipped; `python3 -m unittest test_corner_groups` 5 OK; all I/O-matrix rows covered by passing tests.

**Residual risks:** the recorder UI (refusal row, dialog, lock line) has not been tried on a device; new manifests/records serialize `countCheck: null`/`reviewed: null` explicitly (encodeDefaults).
