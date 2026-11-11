# Epic 12 Context: Release-ready for learners and reviewers

<!-- Generated from planning artifacts. Regenerate with compile-epic-context if planning docs change. -->

## Goal

Make the app (Cubentia) pass every v1.0 release gate and give a hiring manager a clean five-minute evaluation. It must be accessible, fast and stable on the reference devices, private and offline by construction, with the pilot's findings fixed, a polished Play listing and a public portfolio repo. The epic also carries pre-pilot stories that land before the Story 7.8 pilot: 12.6–12.10 (Cubentia rebrand, Epic 8 physical-mode fixes), 12.11–12.12 (Epic 9 session seams, honest 2-look rules) and 12.13 (durable session end). A **device batch** runs once the reference devices are connected: Stories 3.8 and 1.11 (done), then 12.2, then the 12.1 physical pass.

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

## Requirements & Constraints

- **Responsiveness and rendering:** face-turn input is acknowledged within 100 ms. Face turns and View Rotation run at ≥ 30 FPS on the minimum device and 60 FPS on the mid device. A quarter turn animates in ≤ 250 ms.
- **Solver performance:** 95% of the production random-state corpus solves within 3 s on the minimum device. Report median, p95, memory and failure rate, and link the Story 3.8 calibration and calibrated-corpus evidence.
- **Compatibility:** minSdk 29 through targetSdk 36, on phones and tablets, in portrait and landscape. Layouts follow their window class, and rotation never changes session state. API 29–30 are covered on emulators only.
- **Accessibility:** every control has a content label. Contrast meets WCAG AA in both themes. Dynamic type works to 200% with reflow and no truncation. Every essential action has a non-gesture route, and stickers carry color-blind glyphs. The 12.1 physical pass also checks, at 200% type, that a vertical drag on the Lesson cube turns or rotates the cube rather than scrolling the stage.
- **Privacy, offline, stability, size:** no account, analytics, ads or crash SDK. v1.0 declares no `INTERNET` and no runtime permission. Everything works offline from a fresh install. Crash-free sessions are ≥ 99.5%, ANR stays below the Play threshold, and the installed size is < 30 MB. The data-safety form says "no data collected".
- **Session integrity (12.7–12.13):** physical progress is written only after an explicit confirmation. Closing practice or resetting progress really ends the session. A closed session never comes back after a failed write (the host writes an *ended* snapshot and retries it, and the retry is dropped if a newer session has saved since). *Mastered* means the 2-look Lessons are completed **and** two qualifying solves are done.
- **Release gates:** each gate links to its passing evidence. The camera gate is N/A for v1.0. Pilot sev-1/sev-2 issues are fixed, and success metrics get pilot baselines, not guessed targets.

## Technical Decisions

- **Reference devices:** the minimum device is the Samsung Galaxy Tab S7 FE 5G (SM-T738U, Android 12/API 31). The mid device is the Samsung Galaxy S24 FE (SM-S721U1). Neither is truly low-end, and both support GLES 3.2.
- **Renderer:** custom OpenGL ES 3.0 on `GLSurfaceView`, behind the `CubeRenderer` port. It is a projection of committed state and never mutates it. View Rotation stays local to the renderer. Story 1.11 measured the choice on both devices: face turns had a worst-turn p90 of 20.48 ms (Tab) and 23.64 ms (S24); View Rotation had a worst p90 of 20.14 / 20.57 ms. No frame exceeded 33.3 ms. A physical p90 above 33.3 ms would reopen the rendering choice.
- **Frame-timing method:** Macrobenchmark's `FrameTimingMetric` and `FrameMetrics` cannot see `GLSurfaceView` frames. Measure GL frames with the in-app `onDrawFrame` interval log on the GL thread, optionally traced for `TraceSectionMetric`. Cover both face turns and View Rotation. The solve corpus is a JVM benchmark.
- **Solver (AD-8):** clean-room Kotlin Kociemba two-phase. Only `SolveCube` and `PlanRecovery` call it, as Effects with a 3 s timeout, and every `Solved` result is re-verified. It never runs on the main thread.
- **Pruning-table delivery (Story 3.8 decision: BUNDLED):** the asset is generated at build time (`:core:solver:writePruningTableAsset`), read and validated on the I/O dispatcher, and the move tables are built on `Dispatchers.Default`. Only an invalid asset falls back to first-run generation in `filesDir`. Nothing loads at startup or from a Lesson. The bundled tables add 922,770 B installed; total installed size is about 7.96 MB, leaving about 22 MB of headroom. The asset read takes about 24 ms, but **building the move tables takes about 8.3 s per process** (debug; about 7.6–7.8 s from Accept to Solution, release-equivalent). They are built off-main during preparation, so they never eat into the solve's 3 s timeout. Debug StrictMode recorded 0 main-thread disk violations.
- **Accepted v1.0 waivers from 3.8 (do not reopen in 12.2 without the PO):** the PO accepted the ~8 s per-process preparation against the copy "Preparing the solver (one time, a few seconds)." for v1.0 (deferred to v1.1 as #104). The PO waived the zero-failure corpus criterion: on device, 1 of 10,101 states timed out at 3,000.9 ms; on the calibrated profile (k = 16.94, median 46.75 ms, p95 291.9 ms), 10,100 of 10,101 states were within 3 s. Release-equivalent recalibration is deferred to v1.1 as #105, and 3.8 did no solver performance work. Story 12.2 still owns the final physical corpus confirmation against the 95%-in-3-s rule. Evidence is in `docs/pruning-tables.md`, `docs/benchmarks.md` and `docs/evidence/3-8/`.
- **Layout (AD-17):** layout is keyed on `WindowSizeClass`. Composables hold only ephemeral UI state, and every progress or resume flag lives in `SessionState` and the snapshot, so rotation cannot change the session. All sticker surfaces use the one `StickerAppearance` table, and `MoveButtonBar` mirrors every gesture.
- **Concurrency and state:** one `@Singleton SessionHost` with a pure `reduce` on a confined loop. Search and classification run on `Dispatchers.Default`. Effect failures become events, never crashes. `session/TRANSITIONS.md` is normative.
- **CI and release:** CI runs build, Spotless/ktlint, Android lint including a11y, JVM tests, Kover ≥ 80% on `:core:*`, the manifest and SDK policy checks (keyed on `variant.debuggable`) and an AAB size check. Release tags add instrumentation, UI and renderer-sync tests. Builds ship as an AAB with Play App Signing and R8 + resource shrinking, under application ID `com.exxerpro.cubentia`.

## UX & Interaction Patterns

- **Responsive Lesson layout:** compact windows stack (stage ≈ 45% height, min 280dp, key bar on the stage's bottom edge). Medium windows are side by side (stage ≈ 55%, vertical key grid). Expanded windows are side by side with the panel capped at 560dp, and the Library uses 4 columns. A 4-tab bottom bar becomes a navigation rail at medium and expanded widths.
- **Focus order:** frame label → cube description → Move key bar → lesson panel → mode switcher. Every Certainty change is announced. Reduce motion makes Demonstrations step, milestones static and camera transitions snap.
- **Banned patterns:** shake or red flash on mistakes, countdowns, streaks, points, badges, push notifications, auto-advance past a Notice, carousels.

## Cross-Story Dependencies

- 12.2 builds on Story 3.8 (bundled tables, calibration evidence, waivers) and Story 1.11 (renderer frame-timing method and baselines). Story 3.3 provides the JVM corpus/benchmark, and Epic 5 provides the 3D gestures and View Rotation under test.
- Story 3.7 is closed by 3.8. Story 12.1 stays in `review` until its physical pass is recorded.
- 12.6–12.13 run before the 7.8 pilot. 12.4 depends on the 7.8 pilot record. 12.5 (store listing and public repo, including the GitHub and folder rename) comes last, after every gate passes.
