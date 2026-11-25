---
title: 'GH-322 Placement scan review status-bar contrast'
type: 'bugfix'
created: '2026-11-23'
status: 'done'
baseline_revision: 'fb31743a734c0cc722cc01b75e37d2e2fd4f7a67'
review_loop_iteration: 0
followup_review_recommended: true
context: []
warnings: []
deferred:
  - summary: >-
      Confirm system-bar icon pixels and scan-control insets on reference devices.
    evidence: |-
      ScanRouteTest asserts the composed review surface and Android window icon setting with Robolectric.
      The operating system draws the icons. Confirm them on the Galaxy S24 FE in both themes, and check the
      viewfinder and retake controls on physical hardware with gesture and three-button navigation.
    location: >-
      GuidedScanActivity and ScanReviewScreen
    severity: medium (unverified)
---

<intent-contract>

## Intent

**Problem:** The Placement scan review shows white status-bar icons above a light grey surface when the Galaxy S24 FE uses the system light theme. The scan and review are hosted by a separate Activity, which has no system-bar styling for its own window.

**Approach:** The Activity hosting the review applies the status-bar appearance for the same theme used to paint its surface. Use the existing MainActivity edge-to-edge styling pattern and AndroidX system-bar styles.

## Boundaries & Constraints

**Always:** The light review has dark icons and the dark review has light icons. Respect the app's existing system, light, and dark theme choices. Maintain the existing safe-drawing inset padding and camera entry points.

**Never:** Change scan recognition, Placement outcomes, feature flags, or Learner copy. The reported Placement outcome contrast in the release-session notes belongs to a separate MainActivity window and is outside this fix.

</intent-contract>

## Code Map

- `app/src/camera/kotlin/dev/cubentia/app/camera/GuidedScanActivity.kt` hosts the actual Camera and Review phases. Its `setContent` currently uses a system-default `CubentiaTheme` and never sets status-bar appearance.
- `app/src/main/kotlin/dev/cubentia/app/MainActivity.kt` resolves the app's effective theme and uses `enableEdgeToEdge` with AndroidX `SystemBarStyle`. It provides a matching pattern for the scan window.
- `app/src/main/kotlin/dev/cubentia/app/ui/scan/ScanReviewScreen.kt` already paints a full-size theme surface with controls inside `safeDrawingPadding`. The viewfinder in `GuidedScanScreen.kt` likewise insets controls inside a themed surface.
- `app/src/testDebug/kotlin/dev/cubentia/app/ScanRouteTest.kt` has the fake-frame capture driver and a real MainActivity-to-scan launch. The Learn screen uses `learn:findMyPlace` and `findMyPlace:scan`.
- `docs/evidence/release-device-session/README.md` records the separate Placement result issue. That screen uses MainActivity's own system-bar appearance.

## Tasks & Acceptance

**Execution:**
- `app/src/camera/kotlin/dev/cubentia/app/camera/GuidedScanActivity.kt` — resolve the effective theme from the preference store and system theme, and use it both for the content and for the window's system-bar styles.
- `app/src/testDebug/kotlin/dev/cubentia/app/ScanRouteTest.kt` — launch with Learn → Find my place → Scan my cube, provide six simulated capture frames through the existing camera boundary, and assert the real scan review is displayed with the expected window status-bar appearance in light and dark mode.

**Acceptance Criteria:**
- Given the app follows a light system theme, when a Learner starts Placement from Learn and completes a Guided Scan, then Review your scan displays with dark status-bar icons above its light surface.
- Given the app follows a dark system theme, when the same scan reaches review, then its dark surface uses light status-bar icons.

## Spec Change Log

## Review Triage Log

### 2026-11-24 — Review pass

- verdicts: 10 findings — high 0, medium 6, low 2, false 0, maybe-false 2
- findings:
  - `[medium]` `[patch]` The original System-only tests could pass with saved theme overrides ignored. The route now tests explicit Light on a dark system and explicit Dark on a light system.
  - `[medium]` `[patch]` The window flag could pass while Compose used the wrong surface. Each route now asserts a native-graphics pixel from the displayed review against the expected surface token.
  - `[low]` `[patch]` Before the content catalog loaded, dark mode could show the light Activity background through transparent bars. The loading branch now paints a full-window theme surface.
  - `[maybe-false]` `[defer]` Edge-to-edge might affect controls under gesture and three-button navigation. Viewfinder and retake already inset their controls with safe drawing padding, and existing route tests cover both. A physical navigation-mode check can verify final spacing.
  - `[maybe-false]` `[defer]` Robolectric cannot establish the rendered icon pixels on the reported Galaxy. Compare both system modes during a physical Placement scan.
  - `[medium]` `[patch]` Activity 1.13.0's edge-to-edge configuration listener keeps the initial fixed bar style. An in-place configuration dispatch can reapply it after the preference changes. Set up edge-to-edge once with WindowCompat and apply the icon flags directly from the effective theme.
  - `[low]` `[reject]` The spec counted six capture frames, although FakeFrames feeds repeated samples to produce six face captures. This wording describes the test fixture and has no runtime effect. The result records the verified six-face route.
  - `[medium]` `[patch]` No existing scan-window check applied an explicit opposite theme setting. The two override route tests now assert the scan window flag.
  - `[medium]` `[patch]` Existing component pixel tests bypassed the scan Activity's theme wiring. The real launch tests now check the recomposed review surface.
  - `[medium]` `[patch]` The alignment audit found that the status icon flag was the only contrast assertion. The real Learn entry route now also verifies the review surface in all four cases. Placement outcome uses the separately styled MainActivity window and has a separate tracked report.

## Verification

- `./gradlew :app:testDebugUnitTest --tests 'dev.cubentia.app.ScanRouteTest'` should pass the real launch route with fake scan frames.
- `./gradlew spotlessCheck build` should pass the repository build and policy gates.
- A physical Galaxy S24 FE smoke test can confirm the platform-rendered pixels in both system themes.

## Auto Run Result

The scan window now uses the effective app theme for the Android system bars and for the review surface. The loading surface follows that theme while content becomes available.

- `GuidedScanActivity.kt` applies edge-to-edge once and sets the icon appearance from the live theme.
- `ScanRouteTest.kt` covers Learn → Find my place → Scan my cube → Review with six face captures from simulated camera frames. It asserts the status-bar setting and rendered surface for System light, System dark, and both explicit overrides.

Review patched three medium findings and one low finding after grouping by root cause. The remaining verification limits and the rejected fixture wording are recorded in the review log. A follow-up is recommended. The open risk is platform-drawn icon contrast and control spacing under the Galaxy's and Lenovo's native Android navigation modes.

The targeted scan-route suite passed all 15 tests, including the four theme and surface checks. `./gradlew :app:testDebugUnitTest --tests 'dev.cubentia.app.ScanRouteTest' --max-workers=2` and `./gradlew spotlessCheck build --max-workers=2` passed. `git diff HEAD --check` passed. A physical device smoke test remains unverified.
