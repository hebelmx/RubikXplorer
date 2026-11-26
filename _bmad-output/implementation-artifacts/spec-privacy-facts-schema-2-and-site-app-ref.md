---
title: 'Privacy facts schema 2 for camera releases and the site APP_REF release step'
type: 'chore'
created: '2026-11-14'
status: 'done'
baseline_revision: 'f59afc82f18296f86ebbec2063fe57af338a2b1d'
review_loop_iteration: 0
followup_review_recommended: true
context: []
warnings: []
deferred: []  # pruned 2026-11-25 (Epic 13 retro item 111): settled. The site check accepts facts schema 2 (site PR #6, scripts/fixtures/privacy-facts-v2-camera.json).
---

<intent-contract>

## Intent

**Problem:** The PO decided (SCP 2026-11-14 Q8, spine AD-13 privacy-facts bullet) that a release declaring `CAMERA` must export a schema the website check can tell apart, so the public policy is forced to state the on-device camera sentence; today the facts only list the permission. The release runbook also does not tell the operator that the site repo checks the facts at the release tag named by its `APP_REF` variable.

**Approach:** `PrivacyFacts.toJson()` emits `"schemaVersion": 2` plus the fixed fact `"cameraFramesLeaveDevice": false` when the derived runtime permissions contain `android.permission.CAMERA`, and the unchanged schema 1 output otherwise. `docs/release.md` gains a short post-tag step for `APP_REF` and the schema-2 camera note.

## Boundaries & Constraints

**Always:** Without `CAMERA` the JSON is byte-identical to today (`docs/privacy-facts.json` unchanged). The camera fact is a fixed policy constant (`PrivacyFactsPolicy.CAMERA_FRAMES_LEAVE_DEVICE = false`), not derived. In schema 2 the new key is placed directly after `"dataLeavesDevice"`; all other keys keep their order. `:app:checkPrivacyFacts` keeps comparing the full generated JSON, so stale facts still fail. Use `ManifestPolicy.CAMERA` for the permission name.

**Never:** Do not edit `docs/privacy-facts.json`, planning artifacts, `_bmad/`, or `.claude/skills/`. Do not raise any feature flag in `FeatureFlagLevels.kt`. Do not touch `docs/play-first-publish.md` (it does not describe the site check). Do not add the site repo's workflow or any cross-repo automation.

## I/O & Edge-Case Matrix

| Scenario | Input / State | Expected Output / Behavior | Error Handling |
|----------|--------------|---------------------------|----------------|
| Today's release | merged manifest without `CAMERA` | `schemaVersion` 1, no `cameraFramesLeaveDevice` key, bytes equal `docs/privacy-facts.json` | none |
| Camera release | manifest declares `CAMERA` (alone or with other runtime permissions) | `"schemaVersion": 2`, `"cameraFramesLeaveDevice": false` after `dataLeavesDevice`, `runtimePermissions` lists CAMERA | none |
| Stale committed facts | committed v1 file, release now has `CAMERA` | `checkPrivacyFacts` fails with existing guidance | existing failure message |

</intent-contract>

## Code Map

- `build-logic/src/main/kotlin/dev/cubentia/buildlogic/policy/PrivacyFactsPolicy.kt` -- `PrivacyFacts.toJson()` hard-codes `"schemaVersion": 1`; `PrivacyFactsPolicy` holds fixed facts (`DATA_LEAVES_DEVICE`, ...). `derive()` already computes sorted `runtimePermissions`.
- `build-logic/src/main/kotlin/dev/cubentia/buildlogic/policy/ManifestPolicy.kt:22` -- `const val CAMERA = "android.permission.CAMERA"`, reuse.
- `build-logic/src/main/kotlin/dev/cubentia/buildlogic/tasks/WritePrivacyFactsTask.kt`, `CheckPrivacyFactsTask.kt` -- both call `derive(...).toJson()`; no change needed.
- `build-logic/src/test/kotlin/dev/cubentia/buildlogic/policy/PrivacyFactsPolicyTest.kt` -- unit tests; `v1 facts have exact committed shape` compares to `../docs/privacy-facts.json`; `permissions ... sorted` derives CAMERA + RECORD_AUDIO.
- `build-logic/src/test/kotlin/dev/cubentia/buildlogic/functional/CameraCompositionFunctionalTest.kt` -- Story 10.9 TestKit: `today composes debug only` (no facts written yet) and `raised release composes camera and privacy facts` (CAMERA_SCAN=BETA, regex on runtimePermissions, line ~77).
- `build-logic/src/test/kotlin/dev/cubentia/buildlogic/functional/PrivacyFactsFunctionalTest.kt` -- `manifest changes fail stale check` covers the camera stale case already.
- `docs/release.md` -- "Build and first upload" section describes tag push; add the site step after it.

## Tasks & Acceptance

**Execution:**
- `build-logic/src/main/kotlin/dev/cubentia/buildlogic/policy/PrivacyFactsPolicy.kt` -- add `CAMERA_FRAMES_LEAVE_DEVICE = false` constant; in `toJson()` emit schema 2 and the camera key after `dataLeavesDevice` iff `ManifestPolicy.CAMERA in runtimePermissions`; update KDoc -- AD-13 Q8.
- `build-logic/src/test/kotlin/dev/cubentia/buildlogic/policy/PrivacyFactsPolicyTest.kt` -- add an exact-shape schema 2 test for a CAMERA manifest; assert v1 output contains no `cameraFramesLeaveDevice` -- lock both shapes.
- `build-logic/src/test/kotlin/dev/cubentia/buildlogic/functional/CameraCompositionFunctionalTest.kt` -- raised case: assert `"schemaVersion": 2` and `"cameraFramesLeaveDevice": false`; today case: also run `writePrivacyFacts` and assert `"schemaVersion": 1` and no `cameraFramesLeaveDevice` -- extend Story 10.9 coverage.
- `docs/release.md` -- add a "Point the website at the release" step: after pushing the tag, `gh variable set APP_REF --body <tag> -R hebelmx/cubentia-site` then re-run the site's Pages workflow; `APP_REF` stays `main` until the first tag containing `docs/privacy-facts.json` (v0.9.0 predates it); a camera release makes the facts schema 2, so the site policy must state the on-device camera sentence before `APP_REF` moves to that tag.

**Acceptance Criteria:**
- Given today's flag levels, when `./gradlew spotlessCheck build` runs, then `:app:checkPrivacyFacts` passes and `git diff docs/privacy-facts.json` is empty.
- Given the TestKit fixture with release `CAMERA_SCAN=BETA`, when `writePrivacyFacts` runs, then the facts contain `"schemaVersion": 2`, `"cameraFramesLeaveDevice": false` and CAMERA in `runtimePermissions`.
- Given the TestKit fixture at today's levels, when `writePrivacyFacts` runs, then the facts contain `"schemaVersion": 1` and no `cameraFramesLeaveDevice` key.
- Given an operator reading `docs/release.md`, when they finish pushing a tag, then the runbook tells them the exact `gh variable set` command, the Pages re-run, the `main`-until-first-facts-tag rule and the schema-2 camera prerequisite.

## Spec Change Log

## Review Triage Log

### 2026-11-14 — Review pass
- verdicts: 16 findings — high 0, medium 2, low 9, false 4, maybe-false 1
- findings:
  - `[low]` `[patch]` Edge: runbook moves APP_REF right after the tag push, even if the release workflow then fails — patched: docs/release.md now says to move APP_REF once the tag's release workflow run has passed.
  - `[low]` `[reject]` Edge: no rollback step if the site check fails after APP_REF moves — the operator can re-run the same documented `gh variable set` with the previous tag; adding a rollback procedure is new content for a rare case.
  - `[low]` `[reject]` Edge: schema could drop from 2 back to 1 if a later release removes CAMERA — no planned release removes camera after shipping it; documenting it adds content for an unlikely case.
  - `[medium]` `[patch]` Edge: while APP_REF is main, merging a release CAMERA raise makes main schema 2 and breaks the site check before any tag — patched: docs/release.md states the camera-sentence prerequisite also applies before merging a release CAMERA_SCAN raise while APP_REF is main.
  - `[low]` `[reject]` Blind: no schema reference doc for the site repo — the shape is pinned by exact-shape unit tests and the KDoc; a new doc is beyond the intent and not needed for daily work.
  - `[medium]` `[patch]` Blind: runbook gave the APP_REF command before the camera prerequisite — patched: prerequisite paragraph now precedes the command.
  - `[maybe-false]` `[defer]` Blind: site checker may not understand schema 2 — cross-repo; settle by reading the site check script (deferred, medium unverified).
  - `[low]` `[reject]` Blind: Pages re-run has no exact command and "contains the facts file" has no check command — the intent gives no workflow name; the site workflow lives in another repo and is not verifiable here.
  - `[false]` `[reject]` Blind: CAMERA_FRAMES_LEAVE_DEVICE is an unchecked promise if INTERNET were added — ManifestPolicy already fails every release that declares INTERNET (checkReleaseMergedManifest), and denylisted SDKs fail the SDK denylist check, so that combination cannot ship.
  - `[low]` `[reject]` Blind: schema version keyed on a data value complicates future conditional keys — speculative; the PO decision in AD-13 defines it this way.
  - `[false]` `[reject]` Blind: stale-check message not checked for schema 2 — PrivacyFactsFunctionalTest camera case writes the v1 baseline, a CAMERA manifest, and asserts the guidance (assertGuidance) now that the expected facts are schema 2.
  - `[low]` `[reject]` Blind: no RECORD_AUDIO-only schema 1 test, and multi-permission case uses contains — ManifestPolicy rejects any non-CAMERA runtime permission in release, so that input never reaches the facts; key order is pinned by the exact CAMERA-only test.
  - `[false]` `[reject]` Blind: spec logs stale and work on the 10.10 branch — this worktree is on chore/privacy-facts-v2-app-ref and the logs are filled by this review pass.
  - `[low]` `[reject]` Intent: the real :app build at real flag levels is not exercised by a new test — covered by the unchanged committed facts plus :app:checkPrivacyFacts in `build`, which passed.
  - `[low]` `[reject]` Intent: the website behaviour is untested here — cross-repo; the intent asks only for runbook text.
  - `[false]` `[reject]` Intent: diff adds an unrequested spec file — the spec is this workflow's required artifact.

## Verification

**Commands:**
- `./gradlew -p build-logic test` -- expected: BUILD SUCCESSFUL
- `./gradlew spotlessCheck build` -- expected: BUILD SUCCESSFUL
- `git diff --exit-code origin/main -- docs/privacy-facts.json` -- expected: no diff

## Auto Run Result

**Summary:** Privacy facts now export schema 2 (`"schemaVersion": 2` plus the fixed fact `"cameraFramesLeaveDevice": false`, placed after `dataLeavesDevice`) when the release merged manifest declares `android.permission.CAMERA`; without it the output stays schema 1 and byte-identical, so `docs/privacy-facts.json` is unchanged. `docs/release.md` gains "Point the website at the release" with the `APP_REF` step and the schema-2 camera prerequisite.

**Files changed:**
- `build-logic/src/main/kotlin/dev/cubentia/buildlogic/policy/PrivacyFactsPolicy.kt` -- schema switch on `ManifestPolicy.CAMERA`, `CAMERA_FRAMES_LEAVE_DEVICE` constant, KDoc.
- `build-logic/src/test/kotlin/dev/cubentia/buildlogic/policy/PrivacyFactsPolicyTest.kt` -- exact schema 2 shape test; schema 1 has no camera key; key position with multiple permissions.
- `build-logic/src/test/kotlin/dev/cubentia/buildlogic/functional/CameraCompositionFunctionalTest.kt` -- today case writes facts and asserts schema 1 without the key; raised `CAMERA_SCAN=BETA` case asserts schema 2 and `cameraFramesLeaveDevice: false`.
- `docs/release.md` -- site `APP_REF` release step, `main` until the first tag with the facts file, camera prerequisite (also before merging a release `CAMERA_SCAN` raise while `APP_REF` is `main`).

**Review findings:** 16 findings. Patched 3 (2 medium, 1 low; all docs/release.md: ordering of the camera prerequisite, the `APP_REF`=`main` camera-merge case, moving `APP_REF` only after the release workflow passes). Deferred 1 (site checker schema 2 support, cross-repo, medium unverified). Rejected 12 with reasons in the triage log (8 low not worth the added content or unreachable inputs, 4 false).

**Follow-up review recommendation:** true -- two medium entries were patched in docs/release.md after the reviewers ran; the reworded "Point the website at the release" section has not been re-reviewed. Patched counts: high 0, medium 2, low 1.

**Verification:**
- `./gradlew -p build-logic test`: 141 tests, all pass after converting this worktree's CRLF shell scripts to LF (the checkout has `core.autocrlf=true`; the index copy is LF, so `ValidateReleaseInputsScriptTest` failing on `set -o pipefail\r` was environmental, not part of this change).
- `./gradlew spotlessCheck build`: BUILD SUCCESSFUL. Earlier `:core:solver:test` failures (worker EOFException / missing in-progress results file) came from two Gradle runs colliding in the same worktree; run alone, `:core:solver:test` passed.
- `git diff --exit-code f59afc82f18296f86ebbec2063fe57af338a2b1d -- docs/privacy-facts.json`: no diff.

**Residual risks:** the site repo's check must accept schema 2 (deferred, not verifiable here); the Pages re-run is described in words because the site workflow name lives in the other repo.
