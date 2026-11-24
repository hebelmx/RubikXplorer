---
title: 'GH-329: Recorder says the faces are too dark (try EV+) instead of only "Detector missed"'
type: 'bugfix'
created: '2026-11-24'
status: 'done'
baseline_revision: '6e4abe027a21dae9fad1af3e41a8cd8e52b78da6'
review_loop_iteration: 0
followup_review_recommended: false
context:
  - '{project-root}/AGENTS.md'
warnings: [oversized]
deferred: []
---

<intent-contract>

## Intent

**Problem:** In the debug fixture recorder's corner mode, a torch-off glossy or backlit cube is often located by the detector but its faces fall below the sticker-evidence floors (`FrameQuality.STICKER_MIN_CHROMA` / `WHITE_STICKER_MIN_L`), so `CornerReader.read` returns `detected = false` (or a detected frame whose face is dropped, so `cornerSamples` is null). The status line then says only "Detector missed: place the 7 corners (colors are not saved)." and, after *Use corners*, "Saved positive 000N (keypoints only).", never telling the operator that EV+ would recover the colours.

**Approach:** Add a pure recorder rule `cornerMiss(reading)` in `RecorderRules.kt` that tells a "too dark" miss (the cube was located, but a face is missing or below the evidence floor) apart from a "no cube" miss (nothing located), using only what `CornerFrame` already carries. `FixtureRecorderScreen` uses it for the capture status and for the keypoints-only save status. Optional item (3), "capture the most recent detected frame within a short window", is left out: it touches the Activity's capture path (`FixtureRecorderActivity.analyze` / `captureRequest`), which other sessions (#317, #328) are editing, and is not small.

## Boundaries & Constraints

**Always:** Change only `app/src/camera/debug/kotlin/dev/cubentia/app/camera/recorder/` and its JVM test `app/src/testDebug/kotlin/dev/cubentia/app/camera/recorder/RecorderRulesTest.kt`. Keep the existing "Detector missed: place the 7 corners (colors are not saved)." text for a genuine no-cube miss. Keep diffs minimal (other worktrees edit the recorder for #317 / #328).

**Never:** Do not change `CornerReader`, `CornerLocator`, `FrameQuality`, any `app/src/camera/kotlin` or `core/` production file, thresholds, fixture JSON formats, or the Quick Scan path (Story 10.21 owns it). Do not change detection or capture behaviour; this is status text only. Do not implement optional item (3).

## I/O & Edge-Case Matrix

| Scenario | Input / State | Expected Output / Behavior | Error Handling |
|----------|--------------|---------------------------|----------------|
| No cube | `reading` null, or `detected = false`, `score = 0.0` and all three faces null (what `CornerReader.read` returns when the detector located nothing) | `CornerMiss.NO_CUBE`; status stays "Detector missed: place the 7 corners (colors are not saved)." | none |
| Located, too dark | `detected = false`, `score > 0` (detector located a cube; fewer than 2 faces had ≥ 6 sticker-evidence cells) | `CornerMiss.TOO_DARK`; capture status names the faces as too dark and suggests EV+, still says colors are not saved | none |
| Detected, one face dark | `detected = true`, a face is null or has fewer than 6 sticker-evidence cells, so `cornerSamples` is null | `CornerMiss.TOO_DARK` | none |
| Detected, unread cell only | `detected = true`, every face non-null with ≥ 6 evidence cells but one face has a null cell | `CornerMiss.UNREAD_CELL`; status says a face had an unread cell (colors are not saved) | none |
| Usable | `cornerSamples(hold, reading)` non-null | `cornerMiss` returns null; existing "Drag the 7 corners…" status | none |
| Keypoints only after a dark miss | operator taps *Use corners* on a CORNER placement whose miss is TOO_DARK | "Saved positive 000N (keypoints only)." followed by a short EV+ hint | none |

</intent-contract>

## Code Map

- `app/src/camera/kotlin/dev/cubentia/app/camera/CornerLocator.kt` (read-only) -- `CornerReader.read` (~line 437): not located → `CornerFrame(false, List(3) { null })` with default `score = 0.0`; located → `score = located.score` (> 0 for both CornerLocator, whose polish gates keep outline/seams positive, and the LiteRT presence score), `detected = plausible >= 2` where plausible counts faces with ≥ 6 cells passing `FrameQuality.isStickerEvidence`; a face without evidence is kept only when `assessShutter != ACCEPTABLE`, else nulled. `frameQuads` is empty when not detected.
- `core/domain/src/main/kotlin/dev/cubentia/domain/recognition/GuidedScan.kt` (read-only) -- `FrameQuality.isStickerEvidence(CellReading)`, `FrameStats(cells, meanLuma, glareFraction, sharpness, seams)`.
- `app/src/camera/debug/kotlin/dev/cubentia/app/camera/recorder/RecorderRules.kt` -- `cornerSamples` (~line 332) is the rule the new `cornerMiss` sits beside; add a named constant mirroring CornerReader's 6-cell evidence count.
- `app/src/camera/debug/kotlin/dev/cubentia/app/camera/recorder/FixtureRecorderScreen.kt` -- private `Placement` (~line 137), `captureCorner` status `when` (~line 350), `usePlacement` "Saved positive %04d (keypoints only)." (~line 381). EV+ button exists (~line 1122, `Text("EV+")`).
- `app/src/testDebug/kotlin/dev/cubentia/app/camera/recorder/RecorderRulesTest.kt` -- JUnit 5 tests of recorder rules; build `CornerFrame` / `FrameStats` / `CellReading` directly (see `RecorderBurstTest.kt:29` for a `FrameStats` literal).

## Tasks & Acceptance

**Execution:**
- `app/src/camera/debug/kotlin/dev/cubentia/app/camera/recorder/RecorderRules.kt` -- add `internal enum class CornerMiss { NO_CUBE, TOO_DARK, UNREAD_CELL }`, `internal fun cornerMiss(hold: Int, reading: CornerFrame?): CornerMiss?` (null when `cornerSamples` succeeds) and `internal fun cornerMissStatus(miss: CornerMiss): String` plus a keypoints-only hint (e.g. `keypointsOnlySaved(index, miss)`) -- pure, testable wording.
- `app/src/camera/debug/kotlin/dev/cubentia/app/camera/recorder/FixtureRecorderScreen.kt` -- add a `miss: CornerMiss?` field to `Placement`; set it in `captureCorner` for CORNER captures; use `cornerMissStatus` in place of the hard-coded "Detector missed" line and the keypoints-only helper in `usePlacement` -- surface the hint where the operator looks.
- `app/src/testDebug/kotlin/dev/cubentia/app/camera/recorder/RecorderRulesTest.kt` -- tests for every I/O-matrix row, including one that asserts a too-dark miss and a no-cube miss give different results and different status text, and that the too-dark text mentions EV+.

**Acceptance Criteria:**
- Given a corner capture where the detector located the cube but the faces fell below the sticker-evidence floors, when the capture completes, then the recorder status says the faces are too dark and suggests EV+ (not only "Detector missed").
- Given such a capture saved via *Use corners* as keypoints only, when the save completes, then the status reads "Saved positive 000N (keypoints only)." plus the EV+ hint.
- Given a capture with no cube located, when the capture completes, then the status is unchanged: "Detector missed: place the 7 corners (colors are not saved)."
- Given the change, when `git diff --stat` against the baseline is inspected, then only the three files above changed.

## Spec Change Log

## Review Triage Log

### 2026-11-24 — Review pass
- verdicts: 28 findings — high 0, medium 1, low 14, false 10, maybe-false 0 (3 rejected as spec edits counted under false)
- findings:
  - `[false]` `[reject]` IA: frames 0004–0006 (detected, unread cell) should also get the EV+ hint — a null cell comes from `CellReader` NO_PIXELS/INVALID_PIXEL, not from the sticker-evidence floors; faces below the floors already yield TOO_DARK.
  - `[medium]` `[patch]` IA: tests only hand-built frames, never `CornerReader.read` output (grouped with VG2) — added `CornerReader output on a dull gray frame is too dark when located and no cube when not`.
  - `[false]` `[reject]` IA: "located" rests on score > 0 unverified (grouped with EC2, EC7) — CornerLocator's polish gates (outline min ≥ 0.23, seams min ≥ 0.19) bound the score ≥ 0.10 after the ≤ 1.2 exterior penalty, StickerBlob output passes the same polish, LiteRT score is presence ≥ 0.55.
  - `[false]` `[reject]` IA: AC "only three files changed" contradicted by the spec file — fix would edit this build's spec; the spec is the build's own artifact.
  - `[false]` `[reject]` IA: "say so" for item (3) only in the spec — the HALT report states it too.
  - `[low]` `[reject]` EC: jpeg == null with a usable reading shows "Detector missed" (grouped with BH1, VG other) — same text as before the change (pre-existing), encodeFrame failure is rare, fix needs a new enum value and branch.
  - `[false]` `[reject]` EC: located with score ≤ 0 reported as NO_CUBE — see score bound above.
  - `[low]` `[reject]` EC: glare/blur faces get the EV+ hint (grouped with BH2) — the intent maps "below the sticker-evidence floors" to "too dark, suggest EV+"; glare-driven floor misses are uncommon and splitting them adds branches beyond the intent.
  - `[low]` `[reject]` EC: false-positive locate on background gives TOO_DARK — debug-only operator tool where the operator can see whether a cube is present; the proposed guard (require non-null faces) would break the main torch-off case where all faces are nulled.
  - `[low]` `[reject]` EC: CORNER_FACE_MIN_EVIDENCE_CELLS duplicates CornerReader's literal 6 (grouped with BH11) — tying them needs a production change the intent forbids; the KDoc names the source.
  - `[false]` `[reject]` EC: spec claim of three files — spec edit.
  - `[false]` `[reject]` EC: score not bounded above 0 — bound shown above.
  - `[low]` `[reject]` EC: null face from a failed unwarp read as too dark (grouped with BH3) — unwarp fails only on a degenerate homography after geometry validation; rare, and a lit low-chroma face is below the floors by definition.
  - `[low]` `[reject]` BH: jpeg == null fallback — grouped with EC jpeg row.
  - `[low]` `[reject]` BH: glare gets EV+ — grouped with EC glare row.
  - `[low]` `[reject]` BH: all-null located faces labelled TOO_DARK — grouped with EC unwarp row.
  - `[low]` `[reject]` BH: hint ignores whether EV+ is at the top of the range — needs exposure state plumbing; unlikely in everyday use (issue recovered at +1.2).
  - `[false]` `[reject]` BH: hint should mention the torch — the intent asks for EV+; turning the torch on changes the recording condition and recalibrates.
  - `[low]` `[patch]` BH: "capture again" does not match the placement's Retake button — reworded to "tap EV+, then Retake and capture again".
  - `[low]` `[reject]` BH: EV change does not recalibrate centers — pre-existing EV control behaviour, not caused by this change.
  - `[low]` `[patch]` BH: test gaps (score 0 with non-null faces; dim case) — added `an undetected zero-score frame that still carries faces is too dark`; the requested "detected frame with one fully-read dark face → TOO_DARK" case was dropped because `cornerSamples` succeeds there and the spec's Usable row returns null (colours are saved) — that sub-claim is false.
  - `[false]` `[reject]` BH: AC contradiction — spec edit.
  - `[false]` `[reject]` BH: spec gaps (empty logs, oversized, Code Map claim) — spec edit; logs fill during review.
  - `[low]` `[reject]` BH: constant drift — grouped with EC constant row.
  - `[low]` `[reject]` BH: cornerMiss recomputes cornerSamples — negligible cost on one capture; refactor adds surface.
  - `[low]` `[patch]` VG1: screen wiring untested and Placement.miss defaulted to null — removed the default so omitting the miss is a compile error.
  - `[medium]` `[patch]` VG2: no test through CornerReader.read — grouped with the IA test row; patched as above.
  - `[low]` `[reject]` VG other: jpeg == null fallback — grouped with EC jpeg row.

## Design Notes

"Located" is read from `CornerFrame` only: `detected || score > 0.0 || faces.any { it != null }`. `CornerReader` sets `score = 0.0` and all faces null exactly when the detector returned no location, so no production change is needed. A face is "below the floors" when it is null (dropped by CornerReader for lacking evidence) or has fewer than `CORNER_FACE_MIN_EVIDENCE_CELLS = 6` cells passing `FrameQuality.isStickerEvidence` (the same count `CornerReader.read` uses). Suggested texts: TOO_DARK "Faces too dark for colors: tap EV+ and capture again, or place the 7 corners (colors are not saved)."; UNREAD_CELL "A face had an unread cell: place the 7 corners (colors are not saved)."; keypoints-only hint " Faces were too dark for colors: tap EV+ to record them."

## Verification

**Commands:**
- `cd /mnt/e/Dynamic/IndFun/RubikXplorer-issue-329 && export JAVA_HOME=/usr/lib/jvm/java-25-openjdk-amd64 ANDROID_HOME=$HOME/Android/Sdk && ./gradlew :app:testDebugUnitTest --tests 'dev.cubentia.app.camera.recorder.RecorderRulesTest'` -- expected: all tests pass
- `cd /mnt/e/Dynamic/IndFun/RubikXplorer-issue-329 && ./gradlew spotlessCheck :app:compileDebugKotlin` (same env) -- expected: success

## Auto Run Result

Status: done

**Summary:** The debug fixture recorder now tells a "too dark" corner miss apart from a "no cube" miss. A new pure rule `cornerMiss(hold, reading)` classifies a capture without colours as NO_CUBE (nothing located), TOO_DARK (located but not detected, or a face missing or below the sticker-evidence floor) or UNREAD_CELL. The capture status says "Faces too dark for colors: tap EV+, then Retake and capture again, or place the 7 corners (colors are not saved).", and a keypoints-only save after a too-dark miss appends "Faces were too dark for colors: tap EV+ to record them." A genuine no-cube miss keeps "Detector missed: place the 7 corners (colors are not saved)." Optional item (3) (capture the most recent detected frame) was left out: it touches FixtureRecorderActivity's capture path, which #317/#328 sessions are editing, and is not small.

**Files changed:**
- `app/src/camera/debug/kotlin/dev/cubentia/app/camera/recorder/RecorderRules.kt` -- `CornerMiss`, `cornerMiss`, `cornerMissStatus`, `keypointsOnlySaved`, `CORNER_FACE_MIN_EVIDENCE_CELLS`.
- `app/src/camera/debug/kotlin/dev/cubentia/app/camera/recorder/FixtureRecorderScreen.kt` -- `Placement.miss` (no default); capture and keypoints-only statuses use the rules.
- `app/src/testDebug/kotlin/dev/cubentia/app/camera/recorder/RecorderRulesTest.kt` -- 8 tests covering every I/O row, including real `CornerReader.read` output on a dull gray frame (TOO_DARK vs NO_CUBE).

**Review:** 28 findings; 4 patched (1 medium, 3 low), 0 deferred, 24 rejected (reasons in the Review Triage Log).

**Follow-up review recommended:** false (one medium and three low patches on a first pass).

**Verification:** `./gradlew :app:testDebugUnitTest --tests 'dev.cubentia.app.camera.recorder.RecorderRulesTest' spotlessCheck :app:compileDebugKotlin` -- BUILD SUCCESSFUL; RecorderRulesTest 42 tests, 0 failures, 0 skipped.

**Residual risks:** Not checked on a device. A located frame whose faces miss the floors because of glare also gets the EV+ hint. A detected frame with a fully read but dark face still saves colours (unchanged capture behaviour). If no JPEG was encoded, the status still says "Detector missed", as before.
