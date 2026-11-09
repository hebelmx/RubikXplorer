# Epic 12 Context: Release-ready for learners and reviewers

<!-- Generated from planning artifacts. Regenerate with compile-epic-context if planning docs change. -->

## Goal

Make the app pass every v1.0 release gate and give a hiring manager a clean five-minute evaluation: accessible, fast and stable on the reference devices, private and offline by construction, with pilot findings fixed, a polished Play listing and a public portfolio repo. The epic also carries five pre-pilot stories (12.6–12.10) that must land **before the Story 7.8 pilot build**. They rebrand the app to **Cubentia**, because the application ID becomes permanent at the first Play upload, and they fix Epic 8 cross-story defects so that physical-cube mode never records, restores or skips on something the real cube did not do.

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

## Requirements & Constraints

- **Performance:** face-turn input acknowledged within 100 ms; at least 30 FPS for turns and View Rotation on the minimum reference device, 60 FPS on mid-range; 95% of a representative random-state corpus solved within 3 s on the minimum device, with median, p95, memory and failure rate reported.
- **Compatibility:** minSdk 29 through targetSdk 36; phones and tablets, portrait and landscape. Rotation never changes session state.
- **Accessibility:** a content label on every control; WCAG AA contrast in both themes (text at least 4.5:1, status icons at least 3:1, highlight about 7:1 on the cubie body); dynamic type to 200% with no truncation; a non-gesture route for every essential action; color-blind glyphs on every sticker surface; Android lint accessibility checks green.
- **Privacy and offline:** no account, analytics, ads or crash SDK. The merged manifest has no `INTERNET` and **no runtime permission in v1.0** (camera-free; the camera arrives in v1.1). Course, Library, cube entry and solve all work from a fresh install in airplane mode. The data-safety form says "no data collected".
- **Stability and size:** at least 99.5% crash-free sessions, ANR rate below the Play bad-behavior threshold (Android vitals is the only field signal), installed size under 30 MB.
- **Localizability:** all Learner-facing text lives in content keys or resources, never in code. **No Learner-facing string may contain "Rubik"**, and a test enforces this.
- **Release gates:** (1) engine invariants and Algorithm verification pass in CI; (2) every Lesson has validated success and failure paths and every Error Category has a tested Recovery Path; (3) the e2e suite shows no silent virtual/physical desync, and undo, pause, restart and resume preserve a valid state; (4) the pilot completes the Course and every severity-1 and severity-2 issue is fixed; (5) offline, state recovery and accessibility are verified; (6) N/A for v1.0 (camera flags OFF in release, no `CAMERA`). Success metrics SM-1 to SM-9 get pilot baselines, not guessed targets.
- **Store:** name Cubentia, application ID `com.exxerpro.cubentia`. A trademark search for "Cubentia" and the icon are still open before 12.5. The listing needs screenshots, a promo video recorded from the app, a privacy-policy page and a content rating. The repo goes public only once the app works end to end.

## Technical Decisions

- **Single state owner:** each Learning Session is changed only through the pure `reduce(SessionState, SessionEvent)` on one confined loop, held by the `@Singleton SessionHost`. Flags that affect progress or resume belong in `SessionState` and the snapshot, never in `rememberSaveable` or screen state. `session/TRANSITIONS.md` is the normative transition table and must match the reducer.
- **Sync State Machine (physical mode):** `PHYSICAL_AWAITING_CONFIRM`, `PREDICTED`, `VERIFIED` and `MISMATCH`. `expected` changes only on a Learner confirm. Undoing a physical confirm reverts `expected` and asks the Learner to perform the inverse. `ModeSwitched` and `CheckpointRestored` are undo barriers. A restored physical session always lands in `PHYSICAL_AWAITING_CONFIRM`.
- **Certainty:** produced only by `tutor/Assess`, as categorical `VERIFIED`, `LIKELY` or `UNKNOWN`. On a predicted state Certainty is never a false `VERIFIED`. `PhysicalObservation.Partial` is never completed from `expected`, and none is produced in v1.0. A `Full` observation only comes through `CubeValidator`, for example from the Manual Cube Editor.
- **Persistence:** a snapshot on every committed transition and a versioned Checkpoint at Objective boundaries. `progress_event` (`LessonCompleted`, …) is append-only and written from reducer effects. Every Room schema change needs a migration and a migration test.
- **Content is data:** text is `Text(key, args)` resolved from `en.json` content keys. Physical-mode copy uses the existing `msg.physical.*` keys, not `strings.xml` duplicates.
- **Build-enforced privacy:** CI fails on `INTERNET`, on any runtime permission in release, or on a denylisted SDK. `allowBackup="false"`. The release-manifest policy must key on `variant.debuggable` (or fail on a null or unknown build type), never on the build-type name.
- **CI gates:** build, Spotless/ktlint, Android lint (including accessibility), JVM tests, Kover at least 80% on `:core:*`, AD-13 checks and the AAB size check. Release tags also run the instrumentation, UI and renderer-sync tests.
- **Release:** AAB only with Play App Signing; R8 plus resource shrinking with keep rules in `build-logic`; mapping uploaded; `versionCode` is the CI run number; a `vX.Y.Z` tag uploads to the internal track; promotion is manual in Play Console. Feature levels come only from the `FeatureFlags` port, set per build type.
- **Rename targets (12.6):** packages `dev.cubentia.*` moved with `git mv`; namespaces `dev.cubentia.app` and `dev.cubentia.macrobenchmark`; `rootProject.name = "Cubentia"`; DB file `cubentia.db` (no installed users yet). Excluded from the rename: `_bmad/`, `.claude/skills/`, `.git/`, build outputs and historical `_bmad-output` records (SCPs, retros, memlogs, `spec-*`, planning folder names). Other `Rubik*` identifiers (`RubikTheme`, `RubikDatabase`, …) stay for a 12.5 decision. The match pattern is `rubikxplorer`, case-insensitive; the misspelling `rubixplorer` matches nothing.

## UX & Interaction Patterns

- Focus order: frame label → cube description → Move key bar → lesson panel → mode switcher. Every Certainty change is announced. The cube has a semantics summary and a *Read faces* action.
- Responsive Lesson layout by window class: compact stacked (stage min 280dp), medium side by side, expanded with the panel capped at 560dp.
- Banned patterns: shake or red flash on a mistake, countdowns, streaks, points, badges, push notifications, auto-advance past a Notice, carousels. Mistakes use neutral ink, and there are no exclamation marks except at milestones.
- In physical mode the first-solve milestone appears only after an explicit *Yes, it's solved*. The physical undo instruction ("Turn {move} on your cube to undo.") goes in a polite live region.
- Flow 6 (hiring-manager evaluation): offline install, no sign-up, Welcome → *I'm new*, gestures and View Rotation, scrubbing a Library OLL Demonstration, *Enter colors* → solve. It must be smooth, visually consistent in both themes and crash-free.

## Cross-Story Dependencies

- **Order:** 12.6 → 12.7 → 12.8, then 12.9 and 12.10 (independent), then Epic 9, the 3.x leftovers, the 7.8 pilot, and finally 12.1–12.5.
- 12.6 runs first, with nothing else in flight, because it touches nearly every file and must fix the application ID before the 7.8 upload. Afterwards, AGENTS.md needs a project-context refresh because paths and the title change.
- 12.7 and 12.8 both change the reducer and `SessionHost`, so they run one after the other.
- 12.10 shares its TestKit setup with the open `epic-2-retro-item-4`. 12.9 advances `epic-2-retro-item-3`.
- 12.2 needs Story 3.8's pruning-table calibration and its calibrated corpus evidence. 12.4 needs the 7.8 pilot record. 12.5 owns the GitHub repo and local folder rename that 12.6 deferred, plus the trademark search and icon.
