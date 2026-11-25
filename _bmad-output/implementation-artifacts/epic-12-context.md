# Epic 12 Context: Release-ready for learners and reviewers

<!-- Generated from planning artifacts. Regenerate with compile-epic-context if planning docs change. -->

## Goal

Make Cubentia pass every v1.0 release gate and give a hiring manager a clean five-minute evaluation: accessible, fast and stable on the reference devices, offline and private by construction, the pilot's severity-1/2 findings fixed, and a polished Play listing plus a public portfolio repo. The epic also carried pre-pilot fixes (12.6–12.13) and v1 playback/gesture work (12.14–12.17), all now done. What remains is release hardening and shipping: stability, size and privacy evidence (12.3), pilot fixes and the gate checklist (12.4), and the store listing, public repo and production promotion (12.5). The v1.0 scope has since grown to include camera scanning and Placement, behind flags, and Epics 14–15, which ship unflagged except their camera ACs (the 14.1 entry-level picker is live in release). Story 10.11 raises the camera flags; its order against 12.5's production promotion is open for a PO decision (see Release order with camera work).

## Stories

- Story 12.1: Accessibility audit and fixes
- Story 12.2: Performance and compatibility on the reference devices
- Story 12.3: Stability, size and privacy hardening
- Story 12.4: Pilot findings fixed and release gates passed
- Story 12.5: Store listing and public portfolio repo
- Story 12.6: Rebrand to Cubentia
- Story 12.7: Physical progress is recorded only when true
- Story 12.8: The mismatch ladder resolves a mismatch
- Story 12.9: One source for physical copy; skip-check needs the virtual cube
- Story 12.10: Prove the release-permission gate end to end
- Story 12.11: Sessions end when practice closes or progress resets
- Story 12.12: 2-look rules reconciled
- Story 12.13: Closing a session is durable when a write fails
- Story 12.14: Solution Playback in 3D or on the 2D net
- Story 12.15: Move pictures and the Move strip
- Story 12.16: See the turn while dragging, commit on release
- Story 12.17: Know when the view is rotated, and reset it in one tap

## Requirements & Constraints

- **Offline:** a fresh install with no network (airplane mode) must complete the Course, use the Algorithm Library, and enter a cube and solve it. No `INTERNET` permission.
- **Privacy:** no account, no personal data, no analytics, ads or crash SDK; data stays in app-private storage. The Play data-safety form declares "no data collected". Stories 12.3–12.5 check a camera-free release (all camera flags OFF, no runtime permission). Once Story 10.11 raises a camera flag, `CAMERA` becomes the only runtime permission, camera frames stay in memory and are never stored or sent, and "no data collected" still holds but has to be confirmed again.
- **Stability:** ≥ 99.5% crash-free sessions and ANR below Play's bad-behavior threshold, read from the closed-testing build's Android vitals before production.
- **Size:** installed size < 30 MB, including Solver pruning tables.
- **Performance baseline (already met):** ≥ 30 FPS on the minimum device, 60 FPS mid-range, input acknowledged within 100 ms; 95% of solves within 3 s on the minimum device.
- **Accessibility:** WCAG AA contrast, dynamic type to 200% with reflow, a content label and non-gesture route for every essential action, color-blind glyphs; no banned patterns (shake/red flash, streaks, points, push notifications, carousels).
- **Release gates (all required for production):** (1) cube-engine invariants and Library verification in CI; (2) validated Lesson success/failure paths and tested Recovery Paths; (3) e2e suite shows no virtual/physical desync, undo/pause/restart/resume keep a valid state; (4) pilot of beginners completes the Course with every severity-1/2 issue fixed; (5) offline, state recovery and accessibility verified; (6) scan gate is recorded N/A in 12.4 (camera flags OFF, no `CAMERA`); Story 10.11 checks it for each camera feature it raises. Each gate must link to passing evidence.
- **Success-metric baselines:** pilot metrics SM-1–SM-9 are directional (~8 people); the first round records baselines, not targets.
- **Store readiness:** screenshots, a short promo video recorded from the app, the registered privacy-policy URL, the Cubentia website as the listing's website, a completed content-rating questionnaire. No "Rubik's" mark in the name, icon or any Learner-facing text; a trademark search for "Cubentia" and the icon remain open.
- **Public repo:** goes public only once the app works end to end; README with screenshots, architecture overview linking the architecture doc, green CI and coverage badges, Conventional Commits history.

## Technical Decisions

- **Offline/privacy is build-enforced:** merged release manifest has no `INTERNET` and no runtime permission while camera flags are OFF; `allowBackup="false"` with exclude-all `dataExtractionRules`; release logs never contain Cube States or scan samples. CI fails on any violation or on a dependency from the analytics/ads/crash-SDK denylist (ML Kit and Play-services ML included). With all camera flags OFF, the release runtime classpath has no `androidx.camera:*` and no ML/CV runtime. The manifest policy keys on `variant.debuggable` so no build type can bypass it.
- **Privacy facts:** `docs/privacy-facts.json` is derived from the release merged manifest and the denylist; `:app:checkPrivacyFacts` fails when stale. The website repo checks its privacy page against the facts at the app release tag named by its `APP_REF` variable, never at `main`. Without `CAMERA` the facts stay `schemaVersion: 1`. With `CAMERA` they list it as the only runtime permission and move to `schemaVersion: 2` with `cameraFramesLeaveDevice: false`.
- **CI gates on every PR:** build, Spotless/ktlint, Android lint (a11y), JVM tests, Kover ≥ 80% on `:core:*`, the privacy checks, and an AAB size check against the 30 MB limit. Release tags also run instrumentation, UI and renderer-sync tests.
- **Build and release:** AAB only with Play App Signing; upload keystore in GitHub Actions secrets. `release` uses R8 + resource shrinking with keep rules owned by `build-logic`; R8 mapping uploaded with every release; `versionCode` = CI run number; a `vX.Y.Z` tag uploads to the internal track; promotion to closed testing and production is manual in Play Console.
- **Stability without telemetry:** Android vitals is the only field signal. StrictMode in `debug`; Solver, classification and I/O never on the main thread; Effect failures become events, never crashes.
- **Identity:** application ID `com.exxerpro.cubentia` (permanent after first upload). Remaining `Rubik*` code identifiers (`RubikTheme`, `RubikDatabase`, …) await a 12.5 decision.
- **AD-4 commit-before-animate:** every input (gesture, button, accessibility action) becomes a `MoveIntent` that the reducer commits as `MoveCommitted` before anything animates. The renderer only plays the presentation queue (`Animate`/`Snap` items with a target revision and `CubeState`), never mutates state, and never drops a committed Move. View Rotation and drag preview stay renderer-local, and `renderedFacelets()` always returns committed state.
- **Session integrity:** every session state change goes through `SessionEvent` → `reduce()`. ViewModels and composables never mutate state. An async Effect result must echo the effect's `revision` (and `effectId`) or it is discarded. Rotation and process death never change session state (snapshots are written on every committed transition, conditional on revision). Each persisted entity has exactly one writer, as listed in the Ownership Matrix.
- **Learner text:** Learner-facing text, including fix copy from the pilot and any store-facing strings shown in the app, goes in `core/domain/src/main/resources/content/strings/en.json` and is shown through `Text(key, args)`. Screens never hard-code it, and `checkLearnerText` rejects new Learner text in `strings.xml`.
- **Reference devices:** minimum Lenovo Tab M10 Plus 3rd Gen (API 33), mid Samsung Galaxy S24 FE; the Galaxy Tab S7 FE 5G is an additional compatibility device.

## UX & Interaction Patterns

- **Five-minute evaluation (Flow 6):** install and open offline with no sign-up → Welcome → *I'm new*; turn the cube by gesture and rotate the view; Library → an OLL tile → scrub its Demonstration; Solve → *Enter colors* and solve (scan is v1.1). The payoff: smooth 60 FPS turns, a consistent visual system in both themes, nothing crashes, nothing asks for an account. Store screenshots and the promo video should show this path, and the screenshots also go on the website's landing page.

## Cross-Story Dependencies

- **12.3** depends on Story 13.6 (privacy-policy URL registered in Play Console; the data-safety form needs it). All of 12.14–12.17 already precede it. `sprint-change-proposal-2026-11-17-playback-view-and-move-glyphs.md` assigns epic-6-retro-item-1 (`PreferenceStore` must not overwrite saved preferences with defaults after a read error) to 12.3, but the story's ACs in `epics.md` do not include it, so it awaits a PO decision.
- **12.4** depends on the Story 7.8 pilot record (task completion, hint use, points of confusion, recovery from deliberate mistakes). Planning docs mark Story 7.8 done, but its closed-testing review (GitHub #127, § 2) waits for Story 13.6's registered privacy-policy URL. The docs record no pilot results or completion date. Check #127 for the live status, and do not assume any.
- **12.5** depends on Stories 13.4 and 13.5 (privacy-policy and support pages) and on all gates passing. When the repo goes public, the GitHub repo and local folder are renamed to Cubentia (deferred from 12.6); the site repo's `APP_REPO` variable and source links must be updated and its `check` job pass. The site repo itself is never renamed, so the privacy URL never changes.
- **Release order with camera work:** the planning docs conflict here. Story 10.11's own text (reconciled by SCP 2026-11-21) depends on 12.3, 12.4, 12.5 and 13.6 and is the last PR before the `vX.Y.Z` tag. An older note says 10.11 runs before 12.3–12.5. The order is open for a PO decision (deferred from Story 12.4: 12.5 AC4 promotes to production, so production could ship before 10.11 checks gate 6 and re-confirms the data-safety form); 10.11's text is the newer statement. Its first run raises `CAMERA_SCAN` and `PLACEMENT`, and `QUICK_SCAN` follows in a later 10.11-n. The v1.0 issue fixes promoted by `sprint-change-proposal-2026-11-20-v1-1-promotion.md` and `sprint-change-proposal-2026-11-24-github-issue-triage.md`, and Epics 14–15, come before 12.3–12.5.
