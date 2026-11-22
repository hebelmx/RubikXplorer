---
title: 'GH #275: Settings labels start at the Close settings inset on tablet width'
type: 'bugfix'
created: '2026-11-22'
baseline_revision: 'eaec4a9af4f5d68ab60c312699d88c4e048047d7'
status: 'done'
review_loop_iteration: 0
followup_review_recommended: false
context:
  - '{project-root}/AGENTS.md'
warnings: []
deferred:
  - summary: >-
      Confirm on the physical TB128XU that the Settings labels are no longer clipped; the cause (missing inset vs a device-specific offset or unreported edge) is assumed, not shown.
    evidence: |-
      Robolectric reports zero safe-drawing insets and cannot reproduce the device clipping. Text at x = 0 inside safeDrawingPadding should normally not lose a glyph. Settle with a device screenshot of You -> Settings after this change.
    location: >-
      app/src/main/kotlin/dev/cubentia/app/ui/settings/SettingsScreen.kt
    severity: medium (unverified)
---

<intent-contract>

## Intent

**Problem:** On the Lenovo Tab M10 Plus (TB128XU, 800dp width class) every Settings label (title "Settings", "Theme", "Color-blind glyphs", "Move pictures", "Animation speed", "Haptics", "Reduce motion") loses its first character at the left edge, while the "Close settings" link and the action buttons look inset correctly. The Settings column applies no horizontal inset of its own: plain `Text` headings and the radio/switch rows sit at x = 0 of the safe-drawing area, and only the `TextButton`s get a start inset (their built-in content padding).

**Approach:** Give every non-button child of the Settings column the same start inset as the text inside the "Close settings" `TextButton` — `ButtonDefaults.TextButtonContentPadding`'s start padding (layout-direction aware) — and a matching end inset, so headings and switch labels start exactly where the link text starts. Prove it with a Robolectric Compose test at tablet width.

## Boundaries & Constraints

**Always:**
- The inset value is derived from `ButtonDefaults.TextButtonContentPadding` (start for start, end for end, via `LocalLayoutDirection`), not a hard-coded number, so it keeps matching the link if Material changes.
- Apply it to: the `settings_title` and `settings_theme` / `settings_speed` heading `Text`s, and the whole `SettingRadio` and `SettingSwitch` rows (padding inside the row so the 48dp row stays full-width and fully toggleable/selectable). Rows keep `fillMaxWidth`, `heightIn(min = 48.dp)`, their roles and their test tags.
- Add test tags on the three heading `Text`s: `settings:title`, `settings:themeHeading`, `settings:speedHeading`.
- `TextButton`s (Close, Reset preferences, Reset progress, calibration rows) and the dialogs stay unchanged.

**Never:**
- No change to any other screen, to `AppShell`, to strings, or to Preferences behavior.
- Do not hide the defect by removing `safeDrawingPadding`, by changing font size, or by adding `softWrap`/ellipsis.
- Do not edit or weaken existing `SettingsScreenTest` tests.

## I/O & Edge-Case Matrix

| Scenario | Input / State | Expected Output / Behavior | Error Handling |
|----------|--------------|---------------------------|----------------|
| Tablet width, headings and switch labels | Settings at `w800dp-h1280dp`, strings loaded | Left edge of each of: title, Theme heading, Animation speed heading, Color-blind glyphs, Move pictures, Haptics, Reduce motion label text equals the left edge of the "Close settings" text (±0.5dp) | No error expected |
| Tablet width, radio options | Same screen | Each theme/speed radio row's left edge equals the Close-settings text left edge (±0.5dp); its option label text starts to the right of it | No error expected |
| No label clipped | Same screen | Every label text node above has left > 0 and its unclipped bounds lie within the screen root | No error expected |

</intent-contract>

## Code Map

- `app/src/main/kotlin/dev/cubentia/app/ui/settings/SettingsScreen.kt` -- the only production file. Column at lines ~69-133 (`fillMaxSize` → `background` → `safeDrawingPadding` → `verticalScroll`, no horizontal padding). Bare heading `Text`s at lines 80, 81, 106. `SettingRadio` (~line 220: Row with RadioButton then label) and `SettingSwitch` (~line 241: label `Text` weight(1f) then Switch) — the rows to pad. Close link is `TextButton` with tag `settings:close` (line 77); its text inset comes from M3 `ButtonDefaults.TextButtonContentPadding`.
- `app/src/test/kotlin/dev/cubentia/app/ui/settings/SettingsScreenTest.kt` -- existing Robolectric Compose tests (class-level `@Config(qualifiers = "w411dp-h891dp")`; method-level `@Config(qualifiers = ...)` overrides it). Pattern for rendering with strings: `ContentStrings((ContentLoader.load(ContentSource.SHIPPED) as ContentLoadResult.Loaded).bundle.strings)` inside `CubentiaTheme { PreferenceProvider(store) { SettingsScreen({}, strings = strings) } }`.
- `app/src/main/kotlin/dev/cubentia/app/ui/theme/Spacing.kt` -- read-only; spacing scale (s3 = 12dp) for reference only; do not use in place of the ButtonDefaults-derived inset.
- Text node bounds: use `onNodeWithText("Close settings", useUnmergedTree = true)` and the label text nodes in the unmerged tree (`fetchSemanticsNode().boundsInRoot`) — the row/button nodes merge their children.

## Tasks & Acceptance

**Execution:**
- `app/src/main/kotlin/dev/cubentia/app/ui/settings/SettingsScreen.kt` -- compute start/end inset from `ButtonDefaults.TextButtonContentPadding` and apply it to the three heading `Text`s (with new tags) and inside `SettingRadio` / `SettingSwitch` rows (after the clickable modifiers so the touch target stays full-width) -- fixes the clipped labels.
- `app/src/test/kotlin/dev/cubentia/app/ui/settings/SettingsScreenTest.kt` -- add a test annotated `@Config(qualifiers = "w800dp-h1280dp")` covering all three matrix rows -- proves the fix at the TB128XU width class.

**Acceptance Criteria:**
- Given the Settings screen at an 800dp-wide tablet configuration, when it is rendered, then every Settings label starts at the same inset as the "Close settings" link text and no label is clipped at the start edge.
- Given a phone configuration (411dp), when the existing SettingsScreenTest suite runs, then all existing tests still pass.

## Spec Change Log

## Review Triage Log

### 2026-11-22 — Review pass
- verdicts: 25 findings — high 0, medium 1, low 13, false 10, maybe-false 1
- findings:
  - `[low]` `[patch]` (verification-gap) Switch rows' full-width touch target never asserted — added the "row stays full-width" assertion for glyphs, movePictures, haptics, reduceMotion.
  - `[low]` `[reject]` (verification-gap) End inset never asserted — symmetric end padding is not part of the #275 start-edge defect; testing it needs a new Switch tag, and a regression would only move a switch 12dp.
  - `[false]` `[reject]` (edge-case) Close text narrower than 34dp would be centred in the 58dp min width — "Close settings" is a fixed English string far wider than 34dp (12dp start measured with real text), the app ships English only.
  - `[low]` `[reject]` (edge-case) RTL never exercised — app ships English only; adding an RTL test is more than a direct correction.
  - `[medium]` `[patch]` (edge-case) "inside the screen" assertions used clipped boundsInRoot and could never fail — test now measures unclipped bounds (positionInRoot + size) for link text, headings, labels and radios.
  - `[false]` `[reject]` (edge-case) Matrix row 2 says radio row left equals link start while the row stays full-width — fix would edit this build's spec; the implemented reading (row content/radio starts at the inset) is the only consistent one with the Always rules.
  - `[maybe-false]` `[defer]` (blind) Root cause assumed, padding may only hide a device-specific offset — settle with a TB128XU device check of Settings after this change (screenshot).
  - `[false]` `[reject]` (blind) Test cannot reproduce the device bug — the intent's done criterion (2) explicitly asks for a Compose/Robolectric test at tablet width; the test fails on the pre-change layout (labels at x = 0 ≠ 12dp link start).
  - `[false]` `[reject]` (blind) Matrix row 2 contradicts the Always rules — same as the edge-case claim; fix is a spec edit.
  - `[low]` `[reject]` (blind) End inset untested — duplicate of the verification-gap row; same reason.
  - `[low]` `[reject]` (blind) Direction handling has no RTL test / double resolution — resolving start/end for the current direction and re-applying as start/end is consistent in both directions; English-only app.
  - `[low]` `[reject]` (blind) RadioButton has ~2dp internal padding so the visible circle sits 2dp right of the link text — cosmetic; correcting needs an extra offset.
  - `[low]` `[patch]` (blind) @GraphicsMode(NATIVE) has no stated reason — added a one-line comment (fake text measurement centres "Close settings" in the button's min width).
  - `[low]` `[reject]` (blind) Only tablet width checked — the fix is width-independent; intent targets the 800dp class; extra phone test adds no coverage of the change.
  - `[low]` `[reject]` (blind) Test finds labels by English text — consistent with the existing suite (e.g. reset-progress copy lookup); tests run in the default locale.
  - `[false]` `[reject]` (blind) Calibration rows untested — they are unchanged TextButtons whose text inset is the same TextButtonContentPadding the labels now use.
  - `[low]` `[reject]` (blind) Spec Code Map line numbers stale — fix is a spec edit.
  - `[false]` `[reject]` (intent) Device vs Robolectric surface — the intent names a Robolectric test as the acceptance surface.
  - `[low]` `[defer]` (intent) Cause not confirmed on device — grouped with the blind root-cause row (same root cause); deferred with it.
  - `[false]` `[reject]` (intent) Radio option texts start after the radio, not at the inset — the radio row's leading content starts at the inset; the issue's clipped labels were headings and switch labels; putting option text before the indicator is not a defensible reading.
  - `[false]` `[reject]` (intent) End inset and test tags go beyond "nothing more" — tags are needed by the required test; the end inset is the content box's symmetric margin with no user harm.
  - `[false]` `[reject]` (intent) Commit subjects cannot be checked yet — commits are created at finalize, each carrying #275.
  - `[low]` `[reject]` (intent) Calibration rows/buttons not rendered by the test — same as the blind calibration row; unchanged TextButtons.
  - `[low]` `[reject]` (intent) Clipped defined as left > 0 and equal to inset is a proxy — now backed by unclipped bounds; device check covered by the deferred item.
  - `[low]` `[reject]` (intent) Move pictures row only appears with strings — test supplies strings, row covered.

## Verification

**Commands:**
- `export JAVA_HOME=/usr/lib/jvm/java-25-openjdk-amd64 ANDROID_HOME=$HOME/Android/Sdk && ./gradlew :app:testDebugUnitTest --tests 'dev.cubentia.app.ui.settings.SettingsScreenTest'` -- expected: BUILD SUCCESSFUL, new tablet test runs and passes
- `./gradlew spotlessCheck` -- expected: BUILD SUCCESSFUL (run `spotlessApply` first if needed)

## Auto Run Result

Status: done

**Summary:** Settings headings (title, Theme, Animation speed) and every radio/switch row now carry a horizontal inset taken from `ButtonDefaults.TextButtonContentPadding`, so all labels start where the "Close settings" link text starts (12dp). Rows stay full-width touch targets. A Robolectric test at `w800dp-h1280dp` (native graphics) proves alignment and no start-edge clipping with unclipped bounds.

**Files changed:**
- `app/src/main/kotlin/dev/cubentia/app/ui/settings/SettingsScreen.kt` -- `labelInset` helper; inset on headings and inside `SettingRadio`/`SettingSwitch` rows; tags `settings:title`, `settings:themeHeading`, `settings:speedHeading`, `settings:<tag>:radio`.
- `app/src/test/kotlin/dev/cubentia/app/ui/settings/SettingsScreenTest.kt` -- new `labelsStartAtTheCloseSettingsTextInsetOnTablet`.

**Review findings:** 25 findings; 3 patched (1 medium: unclipped bounds; 2 low: switch full-width assert, GraphicsMode comment); 1 deferred group (device confirmation, maybe-false medium); 21 rejected with reasons in the Review Triage Log.

**Follow-up review recommendation:** false — patched by verdict: high 0, medium 1, low 2.

**Verification:** `./gradlew :app:testDebugUnitTest --tests 'dev.cubentia.app.ui.settings.SettingsScreenTest' spotlessCheck` -- BUILD SUCCESSFUL, 10 tests, 0 failures (new tablet test included).

**Residual risks:** Not checked on the physical TB128XU; Robolectric reports no system insets, so whether a device-specific offset also exists is open (deferred). Full `./gradlew spotlessCheck build` not run here; CI gates it.
