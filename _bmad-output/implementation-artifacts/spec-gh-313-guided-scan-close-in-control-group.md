---
title: 'GH #313: Guided Scan Close sits with the shutter and the face prompt'
type: 'bugfix'
created: '2026-11-24'
status: 'done'
baseline_revision: 'ae162ff191a5db365a28280498f8f3055fa16952'
review_loop_iteration: 0
followup_review_recommended: false
context: []
warnings: []
deferred:
  - summary: >-
      Compact 200% font with Use this face anyway and Enter colors instead both shown is not tested for fit.
    evidence: |-
      Pre-existing button stack in the compact viewfinder; GH #313 removes the 48 dp top row so it only frees height. A compact fontScale200 render with canUseAnyway = true and offerEnterColors = true would settle whether Close/Capture stay on screen.
    location: >-
      app/src/testDebug/kotlin/dev/cubentia/app/camera/GuidedScanScreenTest.kt
    severity: low
---

<intent-contract>

## Intent

**Problem:** In the Guided 6-face Scan viewfinder, *Close* sits alone in a top Row (top-left corner) while *Capture* and *Enter colors instead* sit low in the controls panel; on the Lenovo TB128XU in landscape (and on Galaxy portrait) the PO did not find *Close* on a quick look (GH #313).

**Approach:** For the `GuidedScanPhase.Viewfinder` phase only, drop the top Row and render *Close* inside the viewfinder's controls group: an outlined *Close* in one row with *Capture* (dismissive at start, primary at end), and the "Face n of 6" progress beside the face prompt. Same layout rule in compact portrait and in the wide (medium/expanded or landscape) panel.

## Boundaries & Constraints

**Always:** Reuse the existing `OutlinedButton` *Close* style and copy (`btn.scan.close`) and keep test tags `scanClose` and `scanProgress` (many route tests click/read them). *Close* still calls `actions.onClose` — the #325 discard confirm (`DiscardDialog`, `GuidedScanFlow` back rules, commit 2d2bbff1) and the retake / Quick Scan hand-over back rules stay byte-for-byte as they are. Buttons keep `heightIn(min = 48.dp)` and wrap text (no `maxLines`), so 200% font is never cut off. Phone-portrait no-scroll rule keeps holding (`assertFitsOnOneScreen`). Progress keeps its polite live region.

**Never:** Change the top-row Close for other phases (Calibration, Rationale, Denied, Unavailable, CornerViewfinder — Quick Scan's corner view is out of scope). No new strings, no DESIGN.md/EXPERIENCE.md/epics.md/sprint-status.yaml edits, no flow/back-handling change, no new composable files.

## I/O & Edge-Case Matrix

| Scenario | Input / State | Expected Output / Behavior | Error Handling |
|----------|--------------|---------------------------|----------------|
| Compact portrait | w360dp-h640dp, Viewfinder step 1 | *Close* in the row with *Capture* below the net; progress beside prompt; viewfinder, prompt, coaching, net, Capture, Close on one screen, no scroll | — |
| Lenovo landscape | w1333dp-h800dp-land, Viewfinder | *Close* in the right panel, same row as *Capture*, not overlapping the preview | — |
| Medium portrait | w800dp-h1333dp-port | same as landscape (wide panel) | — |
| Can use anyway | `canUseAnyway = true` | *Use this face anyway* stays full width above the Close/Capture row | — |
| 200% font | compact and landscape with fontScale 2 | no text overflow; Close and Capture inside the root; no scroll in compact | — |
| TalkBack order | any Viewfinder | clickables in tree order: … *Close* immediately followed by *Capture* | — |
| Other phases | Unavailable / Calibration / CornerViewfinder | top-row *Close* unchanged | — |

</intent-contract>

## Code Map

- `app/src/camera/kotlin/dev/cubentia/app/camera/GuidedScanScreen.kt` -- `GuidedScanScreen` (~:258-318) top Row holds `scanClose` and, for `GuidedScanPhase.Viewfinder`, `scanProgress`; `Viewfinder(...)` (~:408-504) shared by Viewfinder and CornerViewfinder phases, wide branch `maxWidth >= EXPANDED_WIDTH || maxWidth > maxHeight` (panel Column) and compact branch (Prompt above camera, bottom Column); `CaptureButtons` (~:930) renders `scanUseAnyway` + `scanCapture`; `progressText(phase)`; `Prompt(...)` returns early when `phase.step == null`.
- `app/src/testDebug/kotlin/dev/cubentia/app/camera/GuidedScanScreenTest.kt` -- compact (w360dp-h640dp-port) tests, `assertFitsOnOneScreen`, `overlaps`/`inside` helpers; line ~200 reads `scanProgress` live region.
- `app/src/testDebug/kotlin/dev/cubentia/app/camera/GuidedScanMediumScreenTest.kt` -- w800dp-h1333dp-port panel tests.
- `app/src/test/kotlin/dev/cubentia/app/ui/a11y/A11yAssertions.kt` -- reuse `fontScale200`, `assertNoTextOverflow` (visible to testDebug).
- Read-only: `GuidedScanCloseRouteTest.kt`, `QuickScanRouteTest.kt`, `ScanRouteTest.kt`, `CaseRecognitionRouteTest.kt` click `scanClose` / read `scanProgress` — must stay green unchanged.

## Tasks & Acceptance

**Execution:**
- `app/src/camera/kotlin/dev/cubentia/app/camera/GuidedScanScreen.kt` -- skip the top Row when `phase is GuidedScanPhase.Viewfinder`; pass an `onClose` and a progress `ContentText` into `Viewfinder(...)` only from the Viewfinder branch (null from CornerViewfinder, which keeps its top Row); in both layout branches render progress in a Row with the Prompt (progress at end, live region, tag `scanProgress`, shown even when `step` is null) and render the shutter row as `Row { OutlinedButton(Close, tag scanClose) ; Button(Capture, weight 1f) }` after `scanUseAnyway` -- puts Close in the control group.
- `app/src/testDebug/kotlin/dev/cubentia/app/camera/GuidedScanScreenTest.kt` -- add compact tests: Close sits in the Capture row (vertical ranges overlap, horizontal gap ≤ 16 dp, both below the net), clicking it calls `onClose`, clickables order has Close immediately before Capture, no top-left Close above the prompt; 200% font variant: `assertNoTextOverflow`, Close/Capture inside root, no scroll.
- `app/src/testDebug/kotlin/dev/cubentia/app/camera/GuidedScanMediumScreenTest.kt` -- add the same placement check for the wide panel (Close not overlapping the preview, in Capture's row); add a landscape test class or method at `w1333dp-h800dp-land` (Lenovo landscape) including a 200% font run.

**Acceptance Criteria:**
- Given the Guided 6-face Scan viewfinder in tablet landscape, when it renders, then *Close* sits in the controls panel in the same row as *Capture*, never alone in a far corner.
- Given compact phone portrait, when it renders, then *Close* sits beside *Capture* and the viewfinder, face prompt, net, palette-free controls and Capture still fit on one screen without scrolling.
- Given TalkBack traversal, when moving through the controls, then *Close* comes immediately before *Capture*, after the face prompt and progress.
- Given 200% font, when the viewfinder renders compact or landscape, then no text overflows.
- Given existing GuidedScanScreenTest / GuidedScanMediumScreenTest / GuidedScanCloseRouteTest / QuickScanRouteTest / ScanRouteTest, when run, then they pass unchanged.

## Spec Change Log

## Review Triage Log

### 2026-11-24 — Review pass
- verdicts: 24 findings — high 0, medium 1, low 6, false 17, maybe-false 0
- findings:
  - `low` `reject` (blind) Top-row hiding and `onClose` are decided in two places — real only for a future caller; the route tests and `onNodeWithTag` already fail on a duplicate or missing `scanClose`; the fix is a refactor, not a direct correction.
  - `low` `patch` (blind) KDoc on `Viewfinder`'s `onClose` says "null keeps it in the top row" — comment corrected to "null leaves *Close* out of the panel".
  - `false` `reject` (blind) Quick Scan hand-over / retake viewfinder also loses its top-row Close — those are `GuidedScanPhase.Viewfinder` (the face-by-face viewfinder the issue names); the spec excludes only CornerViewfinder, and QuickScanRouteTest clicks `scanClose` there and passes.
  - `medium` `patch` (blind) "Again: face n of m" progress beside a re-prompt untested at 200% — grouped with the gap finding below; fixed (see that row).
  - `low` `reject` (blind) `step == null` with progress untested — Viewfinder with no step is not a phase the flow shows (it moves to review); adding a test adds surface for no everyday case.
  - `false` `reject` (blind) Phone landscape untested — the change removes the 48 dp top row and adds no row to the wide panel, so it can only free height there.
  - `low` `reject` (blind) Close/Capture row only tested in English — the app ships English only (`en.json`); at 200% English both buttons fit (200% tests pass).
  - `false` `reject` (blind) `isSwatchGlyph()` lives in the wrong file — no named harm; it is used by two test files in the same package.
  - `false` `reject` (blind) Duplicated test setup (`panelStrings`, qualified `@Composable`) — no named harm.
  - `false` `reject` (blind) TalkBack-order test mixes merged and unmerged trees — merging does not reorder siblings; the merged-tree clickables check is the TalkBack-relevant one.
  - `false` `reject` (blind) Nothing checks the old top-left Close is gone in wide layouts — `assertCloseBesideCapture` uses `onNodeWithTag("scanClose")`, which fails on two nodes, and the node must sit in Capture's row.
  - `false` `reject` (edge) Hand-over / retake / reprompt / Quick Check Close moves without placement tests — same composable path as the tested Viewfinder; route tests exercise Close in each.
  - `medium` `patch` (edge) Unweighted progress squeezes the prompt at 200% — grouped with the gap finding below.
  - `low` `reject` (edge) Long localized Close label could starve Capture — English only today.
  - `low` `defer` (edge) Compact 200% with `canUseAnyway` + `offerEnterColors` both set untested — pre-existing stack; the change frees 48 dp, so not caused here; recorded in `deferred`.
  - `low` `reject` (edge) Wide-panel prompt → progress → Close order not pinned — the panel is one Column and PromptRow/CaptureButtons are shared with the compact branch whose order is pinned.
  - `medium` `patch` (gap) No 200% compact check of a re-prompt beside "Again: face 1 of 2" — new test failed (prompt 114 of 336 dp); PromptRow now gives the prompt `weight(2f)` and the progress `weight(1f, fill = false)` so it wraps in a third; test passes.
  - `false` `reject` (intent) Evidence is Robolectric, not the devices — device acceptance is the story's manual session, not something this run can do; noted as residual risk.
  - `low` `patch` (intent) No test at the Lenovo's 600 dp size — landscape class now runs at `w1000dp-h600dp-land`; a method-level `w1333dp-h800dp-land` test keeps the spec's matrix row covered.
  - `false` `reject` (intent) TalkBack checked by semantics order, not a swipe — semantics tree order is what TalkBack traverses; no TalkBack runner exists under Robolectric.
  - `false` `reject` (intent) Compact portrait keeps the prompt above the camera (reading B1 not met) — the no-scroll rule and the 2026-11-16 device check require the prompt above the grid; the shutter's group is the only consistent reading.
  - `false` `reject` (intent) Calibration / corner views keep top-left Close — the intent names the Guided 6-face viewfinder ("Face n of 6"), not those phases.
  - `false` `reject` (intent) Progress moved and a test helper gained `skip` — both needed for "Close with the face prompt" and the 200% check; no behaviour change.
  - `false` `reject` (intent) Existing-test passes not shown in the diff — verified: GuidedScanScreenTest 26, Medium 4, Landscape 3, GuidedScanCloseRouteTest, QuickScanRouteTest, ScanRouteTest, CaseRecognitionRouteTest, QuickScanScreenTest all pass.

## Verification

**Commands:**
- `./gradlew :app:testDebugUnitTest --tests 'dev.cubentia.app.camera.GuidedScan*' --tests 'dev.cubentia.app.GuidedScanCloseRouteTest' --tests 'dev.cubentia.app.QuickScanRouteTest' --tests 'dev.cubentia.app.ScanRouteTest' --tests 'dev.cubentia.app.CaseRecognitionRouteTest' --tests 'dev.cubentia.app.camera.QuickScanScreenTest'` -- expected: all pass
- `./gradlew spotlessCheck build` -- expected: success

## Auto Run Result

Status: done

**Change:** In the Guided 6-face Scan viewfinder (`GuidedScanPhase.Viewfinder`, including Quick Check, retake and Quick Scan hand-over), the top row is gone; *Close* (same outlined style, tag `scanClose`) now starts *Capture*'s row, and "Face n of 6" (`scanProgress`, polite live region) sits at the end of the face prompt's row, in both compact and wide layouts. Other phases keep the top-row Close. Close still calls `actions.onClose`, so the #325 discard confirm and the retake / hand-over back rules are untouched.

**Files:**
- `app/src/camera/kotlin/dev/cubentia/app/camera/GuidedScanScreen.kt` -- top row skipped for Viewfinder; new `PromptRow`; `CaptureButtons` Close + Capture row.
- `app/src/testDebug/kotlin/dev/cubentia/app/camera/GuidedScanScreenTest.kt` -- compact placement, TalkBack order, progress-beside-prompt, 200% (plain and re-prompt) tests.
- `app/src/testDebug/kotlin/dev/cubentia/app/camera/GuidedScanMediumScreenTest.kt` -- medium panel placement and 200% tests; `GuidedScanLandscapeScreenTest` at w1000dp-h600dp-land plus w1333dp-h800dp-land.
- `app/src/test/kotlin/dev/cubentia/app/ui/a11y/A11yAssertions.kt` -- optional `skip` filter on `assertNoTextOverflow`.

**Review:** 24 findings; patches applied 4 (1 medium: re-prompt squeezed at 200% — PromptRow fix; 3 low: KDoc, Lenovo 600 dp landscape size); deferred 1 (compact 200% fullest stack); rejected 19 (reasons in the triage log). Follow-up review recommended: false (patched: high 0, medium 1, low 3).

**Verification:** targeted suites (GuidedScan*, GuidedScanCloseRouteTest, QuickScanRouteTest, ScanRouteTest, CaseRecognitionRouteTest, QuickScanScreenTest) pass; `./gradlew spotlessCheck build` BUILD SUCCESSFUL.

**Residual risks:** not checked on the Lenovo TB128XU or Galaxy S24 FE; the swatch colour-blind glyph is excluded from the 200% overflow check (Robolectric overstates its height even at 100%); in the wide panel the prompt now fills two thirds of its row.
