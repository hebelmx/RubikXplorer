# Epic 12 Context: Release-ready for learners and reviewers

<!-- Generated from planning artifacts. Regenerate with compile-epic-context if planning docs change. -->

## Goal

Make the app (Cubentia) pass every v1.0 release gate and give a hiring manager a clean five-minute evaluation: accessible, fast and stable on the reference devices, private and offline by construction, with pilot findings fixed, a polished Play listing and a public portfolio repo. The epic also carries pre-pilot stories that must land **before the Story 7.8 pilot build**: 12.6–12.10 (Cubentia rebrand, Epic 8 physical-mode fixes; done) and 12.11–12.12, which fix Epic 9 session-lifecycle seams and make the 2-look rules honest, so that closing practice or resetting progress really ends a session and *Mastered* only ever means finished and proven.

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

## Requirements & Constraints

- **Session ending (12.11):** closing any practice (including LL practice restored on cold start) ends the practice session; a Course Lesson opened afterwards must run as `LESSON` with Teaching Modes and write `LessonCompleted`. *Reset progress* ends any live Lesson, 2-look or practice session and deletes its snapshot; the Continue card must offer nothing locked by the reset, and a cold start restores nothing. The reset sheet tells the Learner that in-progress work ends.
- **Algorithm Status safety (12.11):** finishing a 2-look Lesson *offers* to set related statuses to *Learning*, never automatically. The offer is hidden while statuses are unloaded or the stream failed, and accepting it never changes a status that is not *Not started* (a Learner's *Learned* is never downgraded). A failing status repository must surface or retry, never silently emit "all *Not started*" and complete.
- **2-look mastery (12.12):** the mini-Course is *completed* when every 2-look Lesson predicate is met; *mastered* requires *completed* **and** two hint-free LL solves of fresh Scrambles using only 2-look Algorithms. Qualifying solves made before completion count. Before completion the *Faster last layer* row shows *Completed* or *In progress* with "Mastery: N of 2", never ◆ *Mastered*; the milestone plays once, when mastery is actually reached. LL practice Scrambles draw from all 78 Library Algorithms.
- **Localizability:** all Learner-facing text is content keys, never code or `strings.xml` duplicates; 2-look copy moves to `en.json` and the Learner-text gate fails on new Learner strings in `strings.xml`. No Learner-facing string may contain "Rubik".
- **Performance/compatibility:** input acknowledged within 100 ms; ≥30 FPS on the minimum device (60 mid-range); 95% of a random-state corpus solved within 3 s on the minimum device, with median, p95, memory and failure rate reported. minSdk 29–targetSdk 36, phones and tablets, both orientations; rotation never changes session state.
- **Accessibility:** content label on every control; WCAG AA contrast in both themes (text ≥4.5:1, status icons ≥3:1, highlight ≈7:1 on the cubie body); dynamic type to 200% without truncation; non-gesture route for every essential action; color-blind glyphs; lint a11y green. RTL mirroring of `Net2DRenderer`/`StickerReviewNet` is a deferred item to fold into 12.1.
- **Privacy/offline:** no account, analytics, ads or crash SDK; no `INTERNET` and no runtime permission in v1.0; everything works offline from fresh install. **Stability/size:** ≥99.5% crash-free sessions, ANR below the Play threshold, installed size < 30 MB.
- **Release gates:** engine and Algorithm verification in CI; every Lesson's success/failure paths and every Recovery Path tested; no silent virtual/physical desync, and undo/pause/restart/resume preserve a valid state; pilot completes the Course with all sev-1/sev-2 fixed; offline, state recovery and accessibility verified. Success metrics get pilot baselines, not guessed targets.
- **Store (12.5):** name Cubentia, application ID `com.exxerpro.cubentia`; trademark search and icon still open; screenshots, promo video from the app, privacy-policy page, content rating. Repo goes public only once the app works end to end.

## Technical Decisions

- **Single state owner:** each Learning Session has a `SessionKind` (`LESSON`, `PRACTICE`, `TWO_LOOK`, `SOLVE_PLAYBACK`); at most one is active, held by the `@Singleton SessionHost` and changed only through the pure `reduce(SessionState, SessionEvent)` on one confined loop. A screen must not infer a Lesson's kind from whatever the host currently holds. Flags affecting progress or resume live in `SessionState` and the snapshot, never screen state. `session/TRANSITIONS.md` is normative and must describe the LL-practice `practiceEntry` snapshot field and its restore.
- **Persistence:** snapshot on every committed transition, versioned Checkpoint at Objective boundaries; only `LESSON`, `PRACTICE` and `TWO_LOOK` resume on cold start. Rows are never updated; the only deletes are scoped resets, and *Reset progress* additionally ends the live session and deletes its snapshot. No Room schema change is expected for 12.11/12.12; any schema change needs a migration and migration test.
- **Last-layer definitions:** `LastLayerMatcher` (`:core:domain`) is the only definition of Case patterns and AUF, used by Library verification, recognition-diagram derivation and the 2-look predicates. `TwoLookSolve` is the separate judge of *which Algorithms a solve used* (by Move effects) and must not define Case patterns or AUF. A cross-check test pins them: every 2-look Lesson Demonstration scores 0 off-path Moves under `TwoLookSolve`, and its end state satisfies the Lesson's matcher-built predicate. Recognition diagrams are always derived (`RecognitionDiagram.derive`), never stored or hand-drawn.
- **Expected change footprint (12.11/12.12):** `:core:domain` (`TwoLookMastery`), `:core:application` (reset use case, ending the host session), `:app` wiring (`AppShell`, `LessonScreen`, `RoomAlgorithmStatusRepository`). No new dependency.
- **Build-enforced privacy and CI:** CI fails on `INTERNET`, release runtime permissions or denylisted SDKs; manifest policy keys on `variant.debuggable`. CI runs build, Spotless/ktlint, lint (incl. a11y), JVM tests, Kover ≥80% on `:core:*`, AD-13 checks and AAB size; release tags add instrumentation, UI and renderer-sync tests.
- **Release:** AAB with Play App Signing, R8 + resource shrinking, mapping uploaded, `versionCode` = CI run number, `vX.Y.Z` tag uploads to internal track, manual promotion. Feature levels only via the `FeatureFlags` port per build type.

## UX & Interaction Patterns

- *Faster last layer* row state order: *In progress* → *Completed* → ◆ *Mastered* (only once completed), with "Mastery: N of 2" shown when a qualifying solve exists.
- *Reset progress* sheet lists exactly what is erased (Stages, attempts, Algorithm Statuses) and that any Lesson or practice in progress ends, clearing the Continue card.
- Focus order: frame label → cube description → Move key bar → lesson panel → mode switcher; every Certainty change announced. Responsive Lesson layout by window class (compact stacked, stage ≥280dp; expanded panel ≤560dp).
- Banned: shake/red flash on mistakes, countdowns, streaks, points, badges, push notifications, auto-advance past a Notice, carousels. No exclamation marks except at milestones.
- Hiring-manager flow: offline install, no sign-up, Welcome → *I'm new*, gestures and View Rotation, scrubbing a Library OLL Demonstration, *Enter colors* → solve; smooth, consistent in both themes, crash-free.

## Cross-Story Dependencies

- **Order:** 12.11 then 12.12 (run explicitly, one after the other), then remaining leftovers, then the 7.8 pilot (which depends on 12.11 and 12.12), then 12.1–12.5.
- 12.11 touches the snapshot/restore path built in Epic 6; existing restore tests guard it. 12.11 resolves Epic 9 retro items 1–3; 12.12 resolves items 4–6 plus the mastery/AD-19 cross-check, and advances `epic-2-retro-item-3` (Learner text out of `strings.xml`).
- 12.12 amends Story 9.8's *Mastered* rule; Epic 9 stays closed.
- 12.2 needs Story 3.8's calibrated corpus evidence; 12.4 needs the 7.8 pilot record; 12.5 owns the GitHub repo/folder rename deferred by 12.6, plus trademark search and icon.
