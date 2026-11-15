---
title: 'Fixture recorder a phone operator can finish quickly'
type: 'feature'
created: '2026-11-15'
status: 'done'
baseline_revision: 'd2087515a51fdaf10a203a148002c21066c015ff'
review_loop_iteration: 0
followup_review_recommended: false
context:
  - '{project-root}/_bmad-output/implementation-artifacts/epic-10-context.md'
  - '{project-root}/AGENTS.md'
warnings: ['oversized']
deferred: []
---

<intent-contract>

## Intent

**Problem:** Session A showed the debug fixture recorder (Story 10.10) is slow and error-prone on a phone: it scrolls, labels by tap-to-cycle, puts the quality override next to the labels (a mis-tap saved a face as MISALIGNED), meters calibration on a logo center without warning, needs manual Re-meter, and saves no frame for Story 10.13's OpenCV/ML comparison.

**Approach:** Rework the debug recorder UI to the 2026-11-15 UX pass (one-screen layout; tap-sticker-then-color with auto-advance; close-call-gated prefill; rare controls apart behind a confirm; ring-sampled calibration with plausibility/uniformity warnings and a swatch preview; automatic grid re-meter), extend the fixture record (schema 2) with per-sticker `entered`/`prefilled` truth sources and a frame file name, save a downscaled JPEG per face, and have the evaluator report all-sticker and entered-only accuracy.

## Boundaries & Constraints

**Always:**
- Recorder code stays in `app/src/camera/debug/` (composed only into camera-enabled debuggable build types, Story 10.10); frames are written only by it. Pure recorder rules live in testable non-UI Kotlin files there, with JVM tests in `app/src/testDebug/`.
- Color entry follows the UX pass (PO decision 2026-11-15, which the story depends on and which names the recorder): tap a sticker to select it, tap a palette color to set it; selection then auto-advances to the next blank sticker (row-major, wrapping). No tap-to-cycle. Palette is 2×3 in editor order white, yellow, red, orange, green, blue, each with its glyph letter.
- Close-call rule: a sticker is prefilled only when its nearest calibrated center beats the second nearest by ≥ 10 ΔE (CIE76, `CLOSE_CALL_DELTA_E = 10.0`); otherwise it stays blank. Prefill refreshes from the latest acceptable frame while no sticker on the current face has been entered; after the first entry, prefilled cells freeze. Any color the operator sets is `entered` (even if equal to the prefill). Capture needs all nine cells filled.
- Record schema 2: `LiveFixtureRecord.schemaVersion = 2` adds `truthSources: List<TruthSource>` (nine, serialized `"entered"`/`"prefilled"`) and `frame: String?` (sibling file name, e.g. `U.jpg`). The decoder accepts schema 1 (no sources: every sticker counts as entered) and schema 2 (exactly nine sources required); anything else is rejected. Existing `Fixtures/live` schema-1 files are not rewritten.
- Evaluator adds entered-only correct/total and accuracy per group, device and total; the ≥ 99% gate rule stays on all classified stickers, unchanged. Regenerate `docs/spikes/oq-4-guided-scan.md` § Live baseline from the committed fixtures.
- Quality override, Reset labels and Recalibrate sit outside the net/palette area (an overflow/rare-controls row) and each needs a confirm dialog; an active override is shown on the Capture button label (e.g. `Capture U · MISALIGNED`) and clears after a capture.
- Calibration: the center cell is sampled as a ring of 12 points at 30% of the cell side around its middle (each a 3×3-pixel mean); swatch = ring mean. Warn (and ask for a plain sticker of that color in the center cell) when the ring is not uniform (any point > 12 ΔE from the mean) or the swatch is implausible: WHITE with L < 60 or chroma > 25; any other color with chroma < 20; or any color within 10 ΔE of another already-saved center. The swatch and its Lab values are shown before the operator saves it (*Save*, or *Save anyway* when warned) or retakes it.
- Auto re-meter (grid AF/AE/AWB): on face selection, on calibration-color change, and on realignment — the 9-cell mean ΔE versus the last settled frame exceeds 15, then the grid stays steady (< 5 mean ΔE frame to frame) for 600 ms. Re-meter button stays available.
- Frame: the Capture tap requests the next analysis frame; the same `ImageProxy` yields both the `FaceSample` and the JPEG (`toBitmap()`, scaled so the long edge ≤ 1024 px, quality 85), written as `<FACE>.jpg` next to `<FACE>.json`; recapture overwrites both.
- Layout: compact (phone portrait) = viewfinder ~45% height with grid and a 6-face progress strip, then net beside palette, then a status line and the one primary action, no `verticalScroll`; width ≥ 600 dp and landscape = viewfinder left, entry right. Cube/light entry and calibration use the same frame (a compact header with an edit dialog for cube/light).

**Never:**
- No change to `app/src/main`, flag levels, manifests outside `app/src/camera/debug`, `docs/privacy-facts.json`, `NearestCenterClassifier` behavior, the per-sticker FaceSample measurement (center pixel per cell), planning artifacts, `_bmad/`, `.claude/skills/`.
- No product `StickerClassifier`/confidence model (10.1), no product viewfinder or calibration screens (10.2/10.4), no fabricated session A2 data.

## I/O & Edge-Case Matrix

| Scenario | Input / State | Expected Output / Behavior | Error Handling |
|----------|--------------|---------------------------|----------------|
| Clear win | distances 5 and 30 ΔE | prefilled with nearest | none |
| Close call | distances 20 and 28 ΔE (yellow vs white) | blank | none |
| Auto-advance | cells 0,1 filled, 2 selected, color set | cell 2 entered, selection → next blank (wraps), none if all filled | none |
| Bad white | WHITE swatch L76 a20 b71 | implausible warning | Save anyway allowed |
| Good white | L88 a-6 b-16, uniform ring | no warning | none |
| Logo ring | ring points spread > 12 ΔE | non-uniform warning | Save anyway allowed |
| Schema 1 file | session A record | loads; all stickers entered | none |
| Schema 2 sources ≠ 9 | 8 sources | decode fails naming file | loader error |
| Entered-only | 54 stickers, 20 prefilled, 1 entered misread | all 53/54, entered 33/34 | none |
| Frame scale | 1920×1080 / 640×480 | 1024×576 / 640×480 | none |
| Realign | big scene change then 600 ms steady | one re-meter trigger | no re-trigger while unchanged |

</intent-contract>

## Code Map

- `app/src/camera/debug/kotlin/dev/cubentia/app/camera/recorder/FixtureRecorderActivity.kt` -- 641-line Story 10.10 recorder: `measure()` (YUV→Lab, center pixel per cell of an 80% square grid), `rgbToLab`, `meterOnGrid()`, `changeExposure`, scrolling `FixtureRecorderScreen` with tap-to-cycle truth, inline override row, `FACE_CENTER` convention (face named by center color), `ColorButton`, `FixedGrid`. Rewrite the screen; keep measurement, metering and the face/center convention.
- `app/src/camera/debug/kotlin/dev/cubentia/app/camera/recorder/FixtureExport.kt` -- `fixtureRecord(...)`, `writeFixture(root, record)`; extend with truth sources and frame bytes.
- `app/src/testDebug/kotlin/dev/cubentia/app/camera/recorder/FixtureExportTest.kt` -- JUnit 5 JVM tests of export; extend.
- `core/domain/src/main/kotlin/dev/cubentia/domain/recognition/LiveFixture.kt` -- `LiveFixtureRecord` (schemaVersion 1), `LiveFixturePaths`, `LiveFixtureJson.decode` (rejects ≠ 1).
- `core/domain/src/main/kotlin/dev/cubentia/domain/recognition/FaceSample.kt` -- `Lab`, `CalibrationCenters`, `NearestCenterClassifier` (read-only).
- `core/domain/src/evaluation/kotlin/.../evaluation/LiveBaselineEvaluator.kt`, `LiveBaselineMarkdown.kt`, `LiveFixtureLoader.kt` -- score/render/load; add entered-only.
- `core/domain/src/test/kotlin/dev/cubentia/domain/recognition/LiveFixtureTest.kt`, `.../evaluation/LiveBaselineEvaluatorTest.kt` -- existing tests to extend.
- `Fixtures/live/*/` -- 24 committed schema-1 records from session A (must still load).
- `docs/spikes/oq-4-guided-scan.md` -- "Prototype and size cost" and the recorder how-to paragraph after § Live baseline say no frames are saved; update. `Fixtures/README.md` "Live recorder fixtures" -- same.

## Tasks & Acceptance

**Execution:**
- `core/domain/src/main/kotlin/dev/cubentia/domain/recognition/LiveFixture.kt` -- `TruthSource { ENTERED, PREFILLED }` with `@SerialName`, schema 2 fields, version-aware decode, `entered(index)` helper treating schema 1 as entered.
- `core/domain/src/test/.../LiveFixtureTest.kt` -- schema 1 decode, schema 2 round trip, bad source count, unknown version.
- `core/domain/src/evaluation/.../LiveBaselineEvaluator.kt`, `LiveBaselineMarkdown.kt` -- entered-only counts and columns; note in the rendered text.
- `core/domain/src/test/.../evaluation/LiveBaselineEvaluatorTest.kt` -- entered-only matrix row; schema 1 counts all entered; update header assertions.
- `app/src/camera/debug/kotlin/.../recorder/RecorderRules.kt` (new) -- pure: `prefill(sample, calibration)`, `TruthEntry` state (set color → entered + next blank), `swatchWarnings(color, swatch, ringPoints, saved)`, `RemeterTrigger`, `scaledSize(w, h, 1024)`.
- `app/src/testDebug/kotlin/.../recorder/RecorderRulesTest.kt` (new) -- matrix rows for those rules.
- `app/src/camera/debug/kotlin/.../recorder/FixtureExport.kt` + test -- write sources and `<FACE>.jpg`; JSON `frame` names it; recapture overwrites both.
- `app/src/camera/debug/kotlin/.../recorder/FixtureRecorderActivity.kt` -- ring sampling, capture-frame request + JPEG, auto re-meter, new one-screen UI per Boundaries (split composables into a sibling file if it helps readability).
- `docs/spikes/oq-4-guided-scan.md`, `Fixtures/README.md` -- recorder how-to (session A2 flow, frames saved, schema 2, entered/prefilled), regenerate § Live baseline via `./gradlew :core:domain:evaluateLiveBaseline`.

**Acceptance Criteria:**
- Given the committed session A fixtures, when `./gradlew :core:domain:evaluateLiveBaseline` runs, then § Live baseline shows all-sticker and entered-only accuracy (216/216 both) and gate MET, with the rest of the doc unchanged by the tool.
- Given today's flag levels, when `./gradlew spotlessCheck build` runs, then it succeeds, the debug merged manifest has `FixtureRecorderActivity` and release/benchmark have none, and `docs/privacy-facts.json` is unchanged.
- Given the recorder source, when inspected, then it has no `verticalScroll`, no tap-to-cycle, and override/reset/recalibrate each open a confirm dialog.

## Spec Change Log

## Review Triage Log

### 2026-11-15 — Review pass
- verdicts: 36 findings — high 0, medium 1, low 28, false 7, maybe-false 0
- findings:
  - `[low]` `[patch]` (edge) Cube/light edited during the ≤ 3 s capture wait lands the old calibration and truth in the new directory — `capture()` now snapshots cube and light at tap time.
  - `[low]` `[patch]` (edge) A face picked during the capture wait is overridden by the automatic advance — advance now runs only if the selected face is still the captured one.
  - `[low]` `[reject]` (edge) JPEG-then-JSON write is not atomic — needs disk-full or process death mid-write in a debug tool; recapture fixes it and the loader fails loudly; temp-and-rename adds machinery (same call as 10.10).
  - `[low]` `[reject]` (edge) Failed `image.delete()` leaves a stale JPEG — negligible on app-private storage; the JSON's `frame = null` is authoritative.
  - `[low]` `[patch]` (edge) An EV± step can read as realignment and trigger an auto re-meter — `changeExposure` now requests a settle so the new exposure becomes the reference.
  - `[low]` `[patch]` (edge) Auto re-meter replaced the save result in the status line — the notice is now a separate 2 s "re-metered" note beside the status.
  - `[low]` `[reject]` (edge) Capture may land while AE/AWB converges after a re-meter — the operator taps Capture after watching the prefill settle; a settling flag adds port surface for an unlikely case.
  - `[low]` `[reject]` (edge) New face briefly prefilled from the previous face's frame — prefill refreshes every 300 ms until the first entry, and the center-mismatch blocker stops capturing a stale face.
  - `[low]` `[patch]` (edge) Palette tap with nine filled and none selected did nothing silently — status now says to tap a sticker.
  - `[low]` `[patch]` (edge) Whitespace-only cube/light edits cleared calibration — comparison now uses trimmed values.
  - `[low]` `[patch]` (edge) 0/0 entered stickers rendered as 0.0000% — renders "—"; test `no entered stickers render a dash, not zero percent`.
  - `[low]` `[reject]` (edge) A record built without sources defaults to schema 2 and cannot be decoded — the only production constructor (`fixtureRecord`) requires nine sources; an `init` guard protects no reachable state.
  - `[low]` `[patch]` (edge) Schema-2 decode accepted any `frame` string (wrong face, `../x.jpg`) — decode now requires `<FACE>.jpg` or null; test case added.
  - `[low]` `[reject]` (edge) Calibration sampling no longer refuses a frame while a quality override is set — the override is a capture flag by design; calibration has its own swatch preview and warnings.
  - `[low]` `[reject]` (blind) Gate counts prefilled truth, partly measuring the classifier against itself — the spec keeps the gate rule; the AC asks to report entered-only accuracy, now shown beside it, and the rendered text says the gate uses all stickers.
  - `[false]` `[reject]` (blind) A fully prefilled face can be captured with no touch — the AC explicitly allows high-confidence prefill, tagged `prefilled`, and the evaluator separates it.
  - `[low]` `[reject]` (blind) Prefill frame and saved frame differ — truth is the operator's confirmation of the static cube, not of a frame; the docs claim only that samples and JPEG share a frame.
  - `[medium]` `[patch]` (blind) Session A2 reuses session A's `<device>_<cube>_<light>` directories (same devices, `stla`/`stka`, `studio`), so the recorder marks old faces done and `adb pull` overwrites session A — `Fixtures/README.md` now says to clear the on-device tree and use a distinct light label (e.g. `studio-a2`) before A2.
  - `[low]` `[patch]` (blind) Schema-2 validation incomplete (frame name; no init invariant) — frame check patched (grouped with the edge finding); the init part rejected as above.
  - `[low]` `[patch]` (blind) JPEG saved without the geometry 10.13 needs — the JPEG is always the measured crop; README now states crop, orientation, 80% grid and ring geometry.
  - `[low]` `[reject]` (blind) Calibration (ring mean) and stickers (center pixel) are measured differently — the spec keeps the per-sticker measurement for comparability with session A; center truth is fixed by the face and a mismatched center blocks capture; 10.13 compares methods on the frames.
  - `[low]` `[reject]` (blind) Re-plan success criteria (time, touches) are not logged — not an AC; measured by hand in the A2 human gate.
  - `[false]` `[reject]` (blind) No size plan for committed JPEGs — the approved SCP budgets 50–100 KB per face for debug fixtures.
  - `[low]` `[reject]` (blind) `writeFixture` non-atomic — same root and reason as the edge finding.
  - `[false]` `[reject]` (blind) Epic-10 context rewrite drops unrelated constraints — step-01 regenerated the stale cache from the planning artifacts as the workflow requires; not story code.
  - `[low]` `[patch]` (blind) Entered accuracy shows 0% with no entered stickers — same fix as the edge finding.
  - `[low]` `[patch]` (blind) Status line hides results; re-meter overwrote the save message — re-meter note separated (grouped with the edge finding); the saved path is fixed and documented, not restored.
  - `[false]` `[reject]` (blind) Capture never checks captured frame quality or nine samples — an unusable frame is saved with its quality and counted unavailable, as in 10.10.
  - `[low]` `[patch]` (blind) Tests miss new failure paths — settle-window test added (grouped with the verification-gap finding); capture timeout, encode failure and odd scaling are device paths or trivial and are not added.
  - `[false]` `[reject]` (blind) Spec not in the patch — excluded by design; it is the claims file.
  - `[low]` `[patch]` (verification-gap) Re-meter settle window had no observing test — added `a change inside the settle window becomes the reference and does not re-meter`.
  - `[low]` `[patch]` (verification-gap) Screen capture flow producing truth sources untested — extracted `TruthEntry.confirmed()` (colors + sources, null until complete), used by `capture()`, tested in `RecorderRulesTest`.
  - `[low]` `[reject]` (verification-gap) Activity capture/re-meter code untested — debug CameraX code bound to `ImageProxy`; pure rules are extracted and tested; session A2 is its check.
  - `[low]` `[patch]` (verification-gap) Each half of the schema-1 decode check untested on its own — two cases added to `LiveFixtureTest`.
  - `[false]` `[reject]` (verification-gap) `CancellationException` caught as "Save failed" on destroy — the screen is gone when it happens; nothing user-visible.
  - `[false]` `[reject]` (intent) UI ACs and AC7 release exclusion verified only as rules plus build — release/benchmark merged manifests were checked (0 recorder entries) and 10.10's TestKit test still runs; on-device behavior is the story's human gate (session A2); advisory "Save anyway" matches "warns and asks".

## Design Notes

The epics AC wording "a color is chosen once and applied by tapping stickers" predates the UX pass; the PO's UX pass (memlog 2026-11-15) chose tap-sticker-then-color with auto-advance for both scan review and this recorder. With auto-advance a blank face is about one palette touch per sticker, which meets the AC's purpose (no tap-to-cycle, few touches) and keeps the recorder on the product interaction model (SCP decision 6). Numeric plausibility/uniformity bounds are not set by the UX pass; they come from session A evidence (good white L88 C≈17; bad white C≈74; real yellow C≈87) and are named constants for 10.13 to retune.

## Verification

**Commands:**
- `./gradlew spotlessCheck build` -- expected: BUILD SUCCESSFUL
- `./gradlew -p build-logic test` -- expected: BUILD SUCCESSFUL
- `./gradlew :core:domain:test :app:testDebugUnitTest` -- expected: BUILD SUCCESSFUL
- `./gradlew :core:domain:evaluateLiveBaseline && git diff docs/spikes/oq-4-guided-scan.md` -- expected: only § Live baseline table/notes change
- `grep -c FixtureRecorderActivity app/build/intermediates/merged_manifest/{debug,release,benchmark}/*/AndroidManifest.xml` -- expected: debug ≥ 1, release 0, benchmark 0
- `git diff --exit-code docs/privacy-facts.json` -- expected: exit 0

**Manual checks:**
- Session A2 on the S24 FE and Lenovo (human gate): no scrolling, ≤ 2 touches per sticker, frames pulled with the JSON.

## Auto Run Result

**Summary:** Story 10.12 code half. The debug fixture recorder (`app/src/camera/debug/`) now fits one screen (viewfinder ~45% on top with grid and 6-face strip, then face net beside a 2×3 W Y R O G B palette, a status line and one primary action; side by side at ≥ 600 dp or landscape; no scrolling). Truth entry is tap-sticker-then-color with auto-advance (no tap-to-cycle); stickers are prefilled only when the nearest calibrated center beats the runner-up by ≥ 10 ΔE, close calls stay blank, and each sticker is saved as `entered` or `prefilled` (record schema 2, schema-1 session A files still load as all-entered). The evaluator reports all-sticker and entered-only accuracy (gate unchanged). Quality override, Reset labels and Recalibrate sit in a header menu behind confirms; an active override shows on Capture. Calibration samples a 12-point ring around the center cell's middle, warns on a non-uniform ring or implausible swatch (WHITE L < 60 or C > 25; others C < 20; within 10 ΔE of another saved center) and shows the swatch before Save / Save anyway / Retake. Grid AF/AE/AWB re-meters on face selection, calibration-color change and realignment (Re-meter kept). Each Capture saves `<FACE>.jpg` (same analysis frame, long edge ≤ 1024 px) beside `<FACE>.json`. The session A2 numbers are the human gate.

**Files changed:**
- `app/src/camera/debug/.../recorder/FixtureRecorderActivity.kt` — ring sampling, capture-frame request + JPEG encode, auto re-meter wiring, EV step settles.
- `app/src/camera/debug/.../recorder/FixtureRecorderScreen.kt` (new) — one-screen UI, confirms, swatch preview, prefill loop.
- `app/src/camera/debug/.../recorder/RecorderRules.kt` (new) — prefill, `TruthEntry`, swatch warnings, `RemeterTrigger`, `scaledSize`.
- `app/src/camera/debug/.../recorder/FixtureExport.kt` — truth sources, frame file written/removed with the JSON.
- `app/src/testDebug/.../recorder/RecorderRulesTest.kt` (new), `FixtureExportTest.kt` — rules and export tests.
- `core/domain/src/main/.../recognition/LiveFixture.kt` — `TruthSource`, schema 2 (`truthSources`, `frame`), version-aware decode.
- `core/domain/src/evaluation/.../LiveBaselineEvaluator.kt`, `LiveBaselineMarkdown.kt` — entered-only counts/columns.
- `core/domain/src/test/.../LiveFixtureTest.kt`, `evaluation/LiveBaselineEvaluatorTest.kt` — schema and entered-only tests.
- `docs/spikes/oq-4-guided-scan.md` — recorder how-to for A2, frames note, regenerated § Live baseline (216/216 all and entered, MET).
- `Fixtures/README.md` — schema 2, JPEG geometry, A2 directory-reuse instructions.
- `_bmad-output/implementation-artifacts/epic-10-context.md` — regenerated (stale cache).

**Review findings:** 36 findings (medium 1, low 28, false 7). Patched 16 rows (1 medium: A2 would reuse and overwrite session A directories — README instructions; low: capture snapshots cube/light and keeps a face picked mid-save, EV step settles the re-meter trigger, separate re-meter note, palette dead-end message, trimmed cube/light compare, "—" for 0 entered, frame-name decode check, JPEG geometry documented, `TruthEntry.confirmed()`, settle-window and schema-1 tests). Deferred: none. Rejected: 13 low (non-atomic writes ×2, stale-JPEG delete, AE convergence at capture, stale prefill on face change, record `init` guard, override not blocking calibration, gate includes prefilled, prefill vs capture frame, ring vs center-pixel measurement, success-criteria logging, activity CameraX tests, CancellationException message) and 7 false (zero-touch capture allowed by AC, JPEG size budgeted by SCP, context regeneration workflow-mandated, unusable frames recorded as unavailable, spec excluded by design, CancellationException not user-visible, UI ACs are the A2 human gate with release manifests checked).

**Follow-up review recommended:** false. Patched: high 0, medium 1, low 15.

**Verification:** `./gradlew spotlessCheck build :app:testDebugUnitTest` BUILD SUCCESSFUL (after patches); `RecorderRulesTest` 11/11, `FixtureExportTest` 4/4, `LiveFixtureTest` 4/4, `LiveBaselineEvaluatorTest` 18/18; merged manifests `FixtureRecorderActivity` debug 1, release 0, benchmark 0; `evaluateLiveBaseline` reproduces the committed § Live baseline byte-for-byte; `docs/privacy-facts.json` unchanged; no `verticalScroll` or tap-to-cycle in `app/src/camera/debug`. `./gradlew -p build-logic test` not run (no `build-logic` change).

**Residual risks:** on-device behavior (S24 FE fit, ≤ 2 touches per sticker, `toBitmap()` crop handling, re-meter loop stability, ring sampling on logo centers) is only checked in session A2; writes are not atomic; the gate still counts prefilled truth (entered-only shown beside it).
