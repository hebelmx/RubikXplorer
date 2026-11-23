---
title: 'GH-261 Scan review secondary buttons legible on the review surface'
type: 'bugfix'
created: '2026-11-22'
status: 'done'
baseline_revision: '1a0a020ee481b3c3e61fcbd5265e3dab47719e4c'
review_loop_iteration: 0
followup_review_recommended: true
context: []
warnings: []
deferred:
  - summary: >-
      GuidedScanActivity themes with CubentiaTheme (system dark mode) instead of PreferredTheme (the app's theme choice).
    evidence: |-
      GuidedScanActivity.kt:240 calls CubentiaTheme { } while MainActivity uses PreferredTheme(preferenceStore); a Learner who picked Light with a dark system gets a dark scan flow. Pre-existing; out of scope for #261.
    location: >-
      app/src/camera/kotlin/dev/cubentia/app/camera/GuidedScanActivity.kt:240
    severity: low
  - summary: >-
      The hairline outline on secondary outlined buttons is about 1.3:1 against surface-base, under WCAG 1.4.11's 3:1 non-text minimum.
    evidence: |-
      hairline #D5DAE0 on #F4F5F7 and #2A2F36 on #111317; Close and the M3 default outline (outline = hairline) already used it before #261. Changing it is a DESIGN.md token decision.
    location: >-
      app/src/main/kotlin/dev/cubentia/app/ui/scan/ScanReviewScreen.kt SecondaryButton
    severity: low
---

<intent-contract>

## Intent

**Problem:** On Scan review the *Close* label looks faint. Close already uses `ink-primary`, but `ScanReviewScreen` paints no surface of its own and its only host, `GuidedScanActivity`, sets no Compose background either (unlike `AppShell`, which paints `surface-base`), so the review sits on the window's `Theme.Material.Light` background. With the system in dark mode the review gets dark tokens, so `ink-primary` #E7EAEE lands on a near-white window (about 1.2:1). Meanwhile *Retake faces* and *Enter colors instead* fall back to Material defaults (`primary` = `accent`), so they look different from Close.

**Approach:** Give the review its own `surface-base` background across the full screen, so every label sits on a token surface in both themes. Style all three secondary outlined buttons the same way Close is styled (`ink-primary` content, 1dp `hairline` border, always enabled), and prove it with a pixel-level contrast test in light and dark.

## Boundaries & Constraints

**Always:** Use only existing `RubikTokens` (DESIGN.md colours). Close stays enabled, with no greyed or disabled styling, whenever the review is shown. Keep the existing test tags and button order.

**Never:** Add new tokens or change token hex values. Do not touch retake mode's *Cancel*, the filled *Accept* button, the calibration offer, or `GuidedScanActivity` theme selection. No planning-artifact or DESIGN.md edits. No device screenshot work.

## I/O & Edge-Case Matrix

| Scenario | Input / State | Expected Output / Behavior | Error Handling |
|----------|--------------|---------------------------|----------------|
| Light theme | `CubentiaTheme(darkTheme = false)`, review with Retake and Enter colors | Each of close/retakeFaces/enterColors: background pixel = `surfaceBase`, label ink pixel has ≥ 4.5:1 contrast with it and matches `inkPrimary` | No error expected |
| Dark theme on a light window | `CubentiaTheme(darkTheme = true)` in Robolectric (light window background) | Same as above with dark tokens: the label sits on dark `surfaceBase`, not the window | No error expected |
| Close while edited/painting | Review changed, or paint mode locked | `review:close` is enabled and its label colour is unchanged | No error expected |

</intent-contract>

## Code Map

- `app/src/main/kotlin/dev/cubentia/app/ui/scan/ScanReviewScreen.kt` -- `ScanReviewScreen` root `Box(modifier)` (~line 118) has no background. In `ReviewMode`, Close (~427-435) has `outlinedButtonColors(contentColor = tokens.inkPrimary)` + `BorderStroke(1.dp, tokens.hairline)`. Retake faces (~437) and Enter colors (~442) use defaults. `tokens = RubikTheme.tokens` is already in scope there.
- `app/src/camera/kotlin/dev/cubentia/app/camera/GuidedScanActivity.kt` -- read-only evidence: `CubentiaTheme { ... ScanReviewScreen(...) }` (~240, ~295) with no Surface or background.
- `app/src/main/AndroidManifest.xml:17` -- read-only: the window theme is `Theme.Material.Light.NoActionBar`.
- `app/src/main/kotlin/dev/cubentia/app/ui/shell/AppShell.kt:340` -- reference pattern: `.background(RubikTheme.tokens.surfaceBase)`.
- `app/src/main/kotlin/dev/cubentia/app/ui/theme/Tokens.kt`, `Theme.kt` -- tokens; `primary = tokens.accent` explains the sibling default colour.
- `app/src/test/kotlin/dev/cubentia/app/ui/scan/ScanReviewScreenTest.kt` -- Robolectric NATIVE graphics. `launch()` wraps `CubentiaTheme { }` (system theme). `captureToImage().toPixelMap()` is already used. `enterColors` / `retake` vars enable the sibling buttons.
- `app/src/test/kotlin/dev/cubentia/app/ui/theme/ThemeTokensTest.kt` -- WCAG luminance/ratio helper to mirror (private there).

## Tasks & Acceptance

**Execution:**
- `app/src/main/kotlin/dev/cubentia/app/ui/scan/ScanReviewScreen.kt` -- paint the root `Box` with `fillMaxSize()` + `background(RubikTheme.tokens.surfaceBase)`. Extract one private secondary-button style (`ink-primary` content colour, 1dp `hairline` border) and apply it to `review:close`, `review:retakeFaces` and `review:enterColors`. Close keeps no `enabled` parameter. -- gives the labels a token surface in both themes and one consistent style.
- `app/src/test/kotlin/dev/cubentia/app/ui/scan/ScanReviewScreenTest.kt` -- add a `darkTheme` option to `launch` (default keeps current behaviour). Add a test parameterised over light and dark: for each of the three tags, capture the node and take a pixel just inside the border as the background, which must equal `surfaceBase`. Take the pixel with the highest contrast against it as the label ink: its ratio must be ≥ 4.5 and it must be within a small tolerance of `inkPrimary`. Also assert `review:close` is enabled after a paint/edit and while paint mode is locked. -- covers done-when items 2 and 3, and fails on the current code in dark.

**Acceptance Criteria:**
- Given the Scan review in light or dark theme, when Close, Retake faces and Enter colors instead are shown, then each label is drawn in `ink-primary` on `surface-base` with at least 4.5:1 contrast.
- Given any review state (unchanged, edited, painting), when the review is shown, then Close is enabled and never greyed.

## Spec Change Log

## Review Triage Log

### 2026-11-22 — Review pass
- verdicts: 22 findings — high 6, medium 0, low 7, false 8, maybe-false 0
- findings:
  - `[high]` `[patch]` (edge) Uncoloured review Text now draws M3 default black on dark surfaceBase — verified: no Surface/LocalContentColor in ScanReviewScreen or CubentiaTheme; root made a `Surface(color = surfaceBase, contentColor = inkPrimary)`. This also confirms the root cause: before the fix only Close (explicit #E7EAEE) was faint on the light window while other text stayed black.
  - `[high]` `[patch]` (edge) Dark-theme legibility checked only for the three buttons — same root cause; added dark checks on the title and review:instruction, including retake mode.
  - `[false]` `[reject]` (edge) The edit step never proves the review changed — swatch:WHITE paints the pre-selected uncertain sticker 7, as manualEntryAsksBeforeDiscardingStickerEdits shows.
  - `[high]` `[patch]` (edge, claim) The "every label sits on a token surface" claim fails for uncoloured text — same root cause as the first row; fixed by the Surface content colour.
  - `[high]` `[patch]` (blind) Dark-theme title, instruction and lines are black on near-black — same root cause; same patch.
  - `[high]` `[patch]` (blind) The tests sample only the buttons — same root cause; same test patch.
  - `[false]` `[reject]` (blind) Retake Cancel and the calibrate offer now sit on surfaceBase in accent — accent meets well over 4.5:1 on surfaceBase in both themes (#5B3FD1 on #F4F5F7, #A797FF on #111317), so they stay legible.
  - `[low]` `[defer]` (blind) The GuidedScanActivity CubentiaTheme vs PreferredTheme mismatch is not recorded — pre-existing; recorded in `deferred`.
  - `[low]` `[defer]` (blind) The hairline outline is about 1.3:1, under the 3:1 non-text minimum — pre-existing: Close and the M3 default outline (`outline` = hairline) already used it; a DESIGN token decision; recorded in `deferred`.
  - `[false]` `[reject]` (blind) fillMaxSize overrides the caller's sizing — the outer caller constraints still bound fillMaxSize, and the only host already passes fillMaxSize (GuidedScanActivity.kt:342).
  - `[low]` `[reject]` (blind) The test copies the WCAG helper from ThemeTokensTest — developer-only; sharing it means a new test utility, which is more than a direct correction.
  - `[low]` `[patch]` (blind) The test code uses fully qualified names and Math.pow — switched to imports and Kotlin pow.
  - `[low]` `[reject]` (blind) The highest-contrast pixel heuristic could pick a stray pixel — the node holds only the label, the hairline border and the background, and the ink must also match inkPrimary within tolerance; restricting it to the text bounds adds complexity.
  - `[low]` `[reject]` (blind) The Close-enabled-after-edit test runs in dark only — the theme does not change enablement; the existing light test covers the unchanged state.
  - `[high]` `[patch]` (verification-gap) Uncoloured text is unreadable on the new dark surface and only the three buttons are checked — pre-verified; same root cause and patch as the first row.
  - `[high]` `[patch]` (verification-gap, other) Every uncoloured Text draws black on dark surfaceBase — duplicate of the row above; same patch.
  - `[false]` `[reject]` (verification-gap, other) The calibrate offer keeps accent on the new surface — accent meets 4.5:1 on surfaceBase, as above.
  - `[false]` `[reject]` (intent) The fix is tested on the screen, not on the host or device — the screen is the only surface that draws the labels, and the device check is out of scope per the intent; the content-colour finding confirms the dark-system root cause.
  - `[low]` `[defer]` (intent) The host's theme choice (CubentiaTheme vs PreferredTheme) is not investigated — same deferred item as the blind row.
  - `[false]` `[reject]` (intent) Content colour is asserted as a pixel, not as the declared value — the rendered ink matching inkPrimary is the outer surface and implies the declared colour.
  - `[false]` `[reject]` (intent) Only surfaceBase is tested — the review surface is now surfaceBase by construction, and ThemeTokensTest already covers ink-primary on every surface token.
  - `[false]` `[reject]` (intent) The root fillMaxSize/background is a layout change — the host already fills the screen; a background is the minimal surface fix.
  - `[low]` `[reject]` (intent) Close enablement is not checked under the discard dialog or while launching — launching only exists in retake mode, where Close is not shown, and a dialog over the review never changes Close's `enabled`.

## Design Notes

Root cause: the screen is surface-less, not mis-coloured. The fix lives in the screen, not the activity, so any host (and the tests) gets the token surface. The activity uses `CubentiaTheme` (system dark) instead of `PreferredTheme` (the app's theme choice). That is a separate consistency issue and out of scope here.

## Verification

**Commands:**
- `cd /mnt/e/Dynamic/IndFun/RubikXplorer-issue-261 && export JAVA_HOME=/usr/lib/jvm/java-25-openjdk-amd64 ANDROID_HOME=$HOME/Android/Sdk && ./gradlew :app:testDebugUnitTest --tests 'dev.cubentia.app.ui.scan.ScanReviewScreenTest'` -- expected: all pass
- `cd /mnt/e/Dynamic/IndFun/RubikXplorer-issue-261 && ./gradlew spotlessCheck build` -- expected: BUILD SUCCESSFUL

## Auto Run Result

**Summary:** The real cause was that the Scan review had no surface of its own. `ScanReviewScreen` painted no background, and its host `GuidedScanActivity` sets none either, so the review sat on the light `Theme.Material.Light` window. In dark system mode Close's explicit `ink-primary` (#E7EAEE) was drawn on that near-white window, at about 1.2:1. The other text kept M3's default black, and the sibling buttons used `accent`, so only Close looked faint. The review root is now `Surface(color = surface-base, contentColor = ink-primary)`. Close, Retake faces and Enter colors instead share one `SecondaryButton` style: `ink-primary` label, 1dp `hairline` border, never disabled.

**Files changed:**
- `app/src/main/kotlin/dev/cubentia/app/ui/scan/ScanReviewScreen.kt` -- the root `Surface` with token background and content colour, plus a private `SecondaryButton` used by the three secondary buttons.
- `app/src/test/kotlin/dev/cubentia/app/ui/scan/ScanReviewScreenTest.kt` -- `launch(darkTheme)` and four new tests:
  - pixel contrast and ink for the three buttons, in light and in dark;
  - Close enabled and still ink after an edit and while painting;
  - title and instruction legible in dark, in both review and retake mode.
- `_bmad-output/implementation-artifacts/spec-gh-261-scan-review-secondary-button-contrast.md` -- this spec.

**Review findings:** 22 in total.
- **Patched: 9 rows.** Seven are high rows with one shared root cause: uncoloured text was black on the dark surface, and only the three buttons were tested. The fix is the `Surface` content colour plus dark title and instruction tests. One low row was patched: qualified names and `Math.pow` replaced with imports and Kotlin `pow`.
- **Deferred: 3 rows, 2 items.** `GuidedScanActivity` themes with `CubentiaTheme` instead of `PreferredTheme`. The hairline outline is about 1.3:1.
- **Rejected: 10 rows.** Eight were false and two low; the reasons are in the Review Triage Log.

**Follow-up review:** recommended (`true`). A high-verdict entry was patched (by verdict: high 1 entry with 7 rows, low 1). The named unverified risk: the new root `Surface` changes the content colour of every uncoloured element on the review and in retake mode. Only the title and instruction are pixel-checked, and no reviewer has looked at the patched diff.

**Verification:**
- `./gradlew :app:testDebugUnitTest --tests 'dev.cubentia.app.ui.scan.ScanReviewScreenTest'` -- BUILD SUCCESSFUL, 77 tests, 0 failures.
- `./gradlew spotlessCheck build` -- BUILD SUCCESSFUL.
- The implementer confirmed that the new tests fail against the original screen, and that the dark text test fails against a background-only root.

**Residual risks:**
- The pixel checks depend on Robolectric rendering: the default button shape and anti-aliased text.
- The device symptom is attributed to dark system mode. The PO's check on the Lenovo is still pending.
