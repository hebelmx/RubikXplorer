---
title: 'Fixture recorder: session A2 operator findings (face guidance, prefill, capture feedback, layout)'
type: 'feature'
created: '2026-11-15'
status: 'done'
baseline_revision: 'f5f51ac6896df418a3fa0ff6ab76c9f4fb628805'
review_loop_iteration: 0
followup_review_recommended: false
context:
  - '{project-root}/AGENTS.md'
  - '{project-root}/_bmad-output/implementation-artifacts/spec-10-12-fixture-recorder-a-phone-operator-can-finish-quickly.md'
warnings: ['oversized']
deferred:
  - summary: >-
      Immediate prefill refresh after the capture hold may still read the previous face if the operator has not turned the cube, so the A2 lag may persist.
    evidence: |-
      maybe-false: needs the next device session to observe whether the hold plus immediate refresh removes the lag; Suggest again is the recovery path.
    location: >-
      app/src/camera/debug/kotlin/dev/cubentia/app/camera/recorder/FixtureRecorderScreen.kt capture()
    severity: medium (unverified)
---

<intent-contract>

## Intent

**Problem:** Device session A2 (GitHub issue #153) showed the debug-only Story 10.12 fixture recorder confuses its operator: face chips show letters only (B read as Blue), the center-mismatch message reads backwards, the active step and the Capture lock reason are unclear, prefill lags after a capture or face change and stays frozen after entries made on the wrong face, nothing confirms what a Capture saved, and the layout pushes status/Capture to the bottom, has no net/palette separator and truncates the cube/light header.

**Approach:** Operator-experience fixes inside `app/src/camera/debug/.../recorder/`: pure guidance/summary helpers and a `TruthEntry.suggestAgain` rule in `RecorderRules.kt` (unit-tested), and screen changes in `FixtureRecorderScreen.kt` for chips, messages, step/lock lines, immediate prefill refresh, a "Suggest again" action, a post-capture saved-truth hold, a matching post-calibration hold, and the layout tweaks.

## Boundaries & Constraints

**Always:**
- Face-to-center table stays blue-front: U WHITE, R ORANGE, F BLUE, D YELLOW, L RED, B GREEN. Move it to `RecorderRules.kt` as `internal val FACE_CENTER` with a code comment: session A/A2 fixtures are named under this table, and the evaluator (`LiveBaselineEvaluator` / `NearestCenterClassifier`) classifies by calibration color, not by this table; the AGENTS.md home frame (green F) is not used here on purpose.
- Face chip label = `"<letter> · <Color>"`, e.g. `B · Green` (color capitalized, as `label()` does today).
- Center-mismatch lock message = `"Face B needs the Green center — this one is Blue"` (em dash). It replaces `"center is …, face … is …"`.
- On-screen state: a step line naming the active step — `Step 1/3 · Enter cube and light`, `Step 2/3 · Calibrate <Color> (<n>/6)`, `Step 3/3 · Face <X> · <Color> center (<n>/6 saved)` — and, whenever Capture (or Sample) is disabled, a line `Capture locked: <reason(s)>` (or `Sample locked: …`) stating why. Reasons are the existing blockers, worded for the operator (e.g. `4/9 stickers set`, the mismatch message, `saving…`, `hold`).
- `TruthEntry.suggestAgain(prefilled: List<Color?>)`: for every index whose source is not `ENTERED`, take the new prefill color and source `PREFILLED` (or null/null when the prefill is blank); `ENTERED` cells never change. Selection kept when it points at a still-blank cell, otherwise first blank or null. Works even when `anyEntered` is true (that is how a frozen face recovers). `withPrefill` behavior is unchanged.
- "Suggest again" is a button in the net/palette area's action row (enabled when calibrated, not saving/holding, and the latest frame is ACCEPTABLE with 9 samples); it calls `suggestAgain(prefill(latest.lab, calibration))`.
- Prefill refreshes immediately (not on the next 300 ms tick) on a face change (chip tap or auto-advance) and when the post-capture hold ends; the 300 ms loop keeps running as before.
- Post-capture hold: after a successful save the net keeps showing the saved truth (colors and entered/prefilled marks) for `CAPTURE_HOLD_MS = 1500` with the status `Saved L · 1 entered by you` plus `· override MISALIGNED` when an override was used; Capture stays disabled during the hold, prefill does not touch the shown truth, then the next face is selected (existing auto-advance rule) and prefilled. A face picked during the save/hold still wins over auto-advance (existing rule). Summary text built by a pure `savedSummary(face, sources, override)`: `Saved <F> · <n> entered by you` (n may be 0), then ` · override <QUALITY>` if any.
- Post-calibration hold: after *Save*/*Save anyway* of a swatch, *Sample* stays disabled for `CALIBRATION_HOLD_MS = 1500` (a plain timed hold, no detection), with the lock line saying so.
- Layout (compact and wide): a thin vertical divider (≈1–2 dp) between net (or calibration panel) and palette; the status/step/lock lines and the primary action sit directly under the net/palette row (leftover space goes below them, not above); the header shows the full cube and light text (wraps, never ellipsized; device model may ellipsize). Still one screen, no `verticalScroll`.

**Never:**
- No change to fixture file format, `FixtureExport.kt`, `FixtureRecorderActivity.kt` measurement/metering, `core/`, `app/src/main`, flags, manifests, planning artifacts, `_bmad/`, `.claude/skills/`. No renaming of existing fixtures. Nothing from the issue's "Related (OQ-7 input)" section.

## I/O & Edge-Case Matrix

| Scenario | Input / State | Expected Output / Behavior | Error Handling |
|----------|--------------|---------------------------|----------------|
| Suggest again keeps entered | cells 0,4 ENTERED (RED, GREEN), rest PREFILLED WHITE; prefill all BLUE | 0,4 unchanged ENTERED; other 7 BLUE PREFILLED | none |
| Suggest again blanks | prefilled cell, new prefill null | cell null, source null; selection → first blank if previous selection not blank | none |
| Frozen face recovery | 2 ENTERED from wrong face, 7 stale prefilled | 7 refreshed from current frame; 2 entered kept | none |
| Chip label | Face.B | `B · Green` | none |
| Mismatch | face B, center entered BLUE | `Face B needs the Green center — this one is Blue` | none |
| Saved summary | face L, 1 ENTERED, override null / MISALIGNED | `Saved L · 1 entered by you` / `… · override MISALIGNED` | none |

</intent-contract>

## Code Map

- `app/src/camera/debug/kotlin/dev/cubentia/app/camera/recorder/RecorderRules.kt` -- pure rules; `TruthEntry` (`set`, `withPrefill` L109–114, `confirmed`). Add `FACE_CENTER` (moved from screen L84–92), `faceChipLabel`, `centerMismatch`, `savedSummary`, `suggestAgain`, hold constants. `label()` currently private in screen L832 — move a shared `colorLabel` here.
- `app/src/camera/debug/kotlin/dev/cubentia/app/camera/recorder/FixtureRecorderScreen.kt` -- `FACE_CENTER` L84; prefill loop L172–181 (skips while `saving`); blockers L184–195; `saveSwatch` L224; `capture()` L232–289 (auto-advance L282); entry panel L309–379 (`BoxWithConstraints(Modifier.weight(1f))` L320 pushes status/button to bottom; status `Text` L344 with `Need: …`); chips L477–507 (36 dp boxes, letter only); `HeaderRow` L526 (cube/light ellipsized L540).
- `app/src/testDebug/kotlin/dev/cubentia/app/camera/recorder/RecorderRulesTest.kt` -- JUnit 5 tests to extend.
- `core/domain/src/main/kotlin/dev/cubentia/domain/recognition/FaceSample.kt` L76 `NearestCenterClassifier` -- read-only evidence: classifies against calibration centers, no face table.
- `docs/spikes/oq-4-guided-scan.md` L99–101 -- recorder how-to; add one sentence each for chips, Suggest again and the holds.

## Tasks & Acceptance

**Execution:**
- `RecorderRules.kt` -- add the helpers, constants, `FACE_CENTER` with the why-comment, and `suggestAgain` per Boundaries.
- `RecorderRulesTest.kt` -- cover every I/O matrix row, plus `withPrefill` still frozen after entry.
- `FixtureRecorderScreen.kt` -- use the helpers; step and lock lines; immediate prefill on face change and hold end; Suggest again button; capture hold with saved summary; calibration hold; divider; status+action right under the net/palette; untruncated header; chips laid out across the strip (weight) so `B · Green` fits (may wrap to two lines).
- `docs/spikes/oq-4-guided-scan.md` -- how-to sentences only (no § Live baseline change).

**Acceptance Criteria:**
- Given the recorder source, when inspected, then chips render `faceChipLabel`, the lock line uses `centerMismatch`, no `"center is "` / `"Need: "` strings remain, there is no `verticalScroll`, and the header cube/light `Text` has no `TextOverflow.Ellipsis`.
- Given `./gradlew :app:testDebugUnitTest`, when run, then the new `RecorderRulesTest` cases pass.
- Given `./gradlew spotlessCheck build`, when run, then it succeeds and `docs/privacy-facts.json` and `Fixtures/` are unchanged.

## Design Notes

Likely cause of the A2 "lag": after a capture the next face was prefilled from a frame still showing the old face, and the first palette tap froze it. The hold (showing saved truth, no prefill) plus an immediate refresh at hold end and Suggest again address this without detection logic. The hold value 1500 ms is a named constant for retuning.

## Verification

**Commands:**
- `./gradlew :app:testDebugUnitTest --tests '*RecorderRulesTest*'` -- expected: pass
- `./gradlew spotlessCheck build` -- expected: BUILD SUCCESSFUL
- `git diff --stat -- Fixtures docs/privacy-facts.json core` -- expected: empty

**Manual checks:**
- Next device session: chips, messages, holds, Suggest again and layout on S24 FE and Lenovo.

## Auto Run Result

**Summary:** GitHub issue #153 recorder fixes (debug-only). Face chips read `B · Green`; the lock line reads `Face B needs the Green center — this one is Blue`; a step line (`Step 1/3`…`Step 3/3`) and `Capture locked: …` / `Sample locked: …` lines state the active step and why the action is disabled. The blue-front `FACE_CENTER` table moved to `RecorderRules.kt` unchanged with a comment explaining why (session A/A2 fixtures named under it; the evaluator classifies by calibration color). Prefill refreshes at once on face change and at hold end; *Suggest again* (`TruthEntry.suggestAgain`) refills only non-entered stickers. After Capture the net keeps the saved truth for 1.5 s (`Saved L · 1 entered by you · override …`), then advances; Sample is held 1.5 s after a swatch is saved. Layout: thin divider between net and palette, status/step/lock and actions directly under them, header shows full cube/light (model on its own line).

**Files changed:**
- `app/src/camera/debug/kotlin/dev/cubentia/app/camera/recorder/RecorderRules.kt` — `FACE_CENTER` + comment, `colorLabel`, `faceChipLabel`, `centerMismatch`, `savedSummary`, `captureLocks`, `nextFace`, hold constants, `TruthEntry.suggestAgain`.
- `app/src/camera/debug/kotlin/dev/cubentia/app/camera/recorder/FixtureRecorderScreen.kt` — chips, step/lock lines, immediate prefill, Suggest again, capture and calibration holds, divider, layout, header.
- `app/src/testDebug/kotlin/dev/cubentia/app/camera/recorder/RecorderRulesTest.kt` — 7 new tests (18 total).
- `docs/spikes/oq-4-guided-scan.md` — recorder how-to (chips, lock/step lines, Suggest again, holds, blue-front convention).

**Review findings:** 27 (medium 2, low 18, false 6, maybe-false 1). Patched 11 rows in 9 fixes (medium: fixed 150 dp reserve → measured layout; low: Suggest again feedback, chip min height, hold cleared on Reset/Recalibrate, null-frame refresh, `+ frame` wording, `captureLocks`/`nextFace` extracted and tested, blue-front doc sentence). Deferred 1 (maybe-false: lag may persist if the cube is not turned within the hold — device session). Rejected 9 low (hold-timer race ×2, header wrap cap ×2, red lock color, sixth-face clear, Suggest again selection jump) and 6 false (spec not in diff, pause is new timer, camera not frozen, Suggest again blanks, additions beyond intent, one-screen unverified) — reasons in the triage log.

**Follow-up review recommended:** false. Patched: high 0, medium 1 (entry), low 8 (entries).

**Verification:** `./gradlew :app:testDebugUnitTest --tests '*RecorderRulesTest*'` 18/18 pass; `./gradlew spotlessCheck build` BUILD SUCCESSFUL (after patches); `git diff --stat -- Fixtures docs/privacy-facts.json core` empty; no `verticalScroll`, `"Need: "`, `"center is "` in `app/src/camera/debug`.

**Residual risks:** on-device fit (chips, wrapped header, status lines on the S24 FE/Lenovo), hold timing feel and whether the lag is gone are only checked in the next device session; Compose timing paths have no automated test.
