# Epic 11 Context: Scan shortcuts: Quick Scan, Placement and Case recognition

<!-- Generated from planning artifacts. Regenerate with compile-epic-context if planning docs change. -->

## Goal

Build three camera shortcuts on top of the Epic 10 scan pipeline. Quick Scan reads the whole cube from two corner photos. Placement looks at the Learner's cube (scanned or entered in the Editor), explains what it sees, and suggests the Course Stage to start from. Case recognition names the OLL/PLL Case from two top-corner photos. Each feature sits behind its own feature flag and can be turned OFF with no dead UI. If work slips they are cut in this order: Quick Scan drops to BETA, then Case recognition, then Placement. As of 2026-11-20, Quick Scan and Placement are v1.0 scope, together with the Placement fixes for #251 (explain the result) and #252 (re-placement without resetting progress). Story 10.11 raises `QUICK_SCAN` and `PLACEMENT` for the v1.0 release. `CASE_RECOGNITION` gets its own release decision.

## Stories

- Story 11.1: Quick Scan from two corners
- Story 11.2: Start the Course from my cube (Placement), amended 2026-11-20 for #251 and #252
- Story 11.3: Recognize an OLL/PLL Case

## Requirements & Constraints

- **Quick Scan:** two captures from opposite corners give all 54 stickers. If any face is uncertain or the state is invalid, the Guided Scan grid asks for only those faces, and the app never guesses a state. It is recommended GA only at ≥ 95% valid after ≤ 3 corrections in under 15 s. Otherwise it stays BETA. Any OpenCV or LiteRT runtime goes only in the camera source set, within the APK size budget.
- **Placement, base:** evaluate each Stage Predicate on an accepted Full cube and suggest the first unmet Stage, with *Choose another Stage*. Accepting unlocks that Stage and records earlier Stages as skipped by placement (◇, never mastered, always revisitable). With `PLACEMENT` OFF, *Start from my cube* is absent everywhere, and the persisted onboarding-complete behavior (#137) is unchanged.
- **Placement result (#251):**
  - The result shows the cube reading, then the reason, then the suggestion, in that order. All of it comes from content strings, and TalkBack reads it in the same order. It ends with the line "The lesson sets up its own cube, so you can put yours aside."
  - Before any Stage Predicate runs, a cube 1 or 2 face turns from solved (including a last-layer or first-layer turn) gets its own outcome that names the turn(s) in words. The turns are found by a bounded enumeration of at most 2 Moves, never by the Solver.
  - A cross or first two layers solved on a color other than white: the reading credits the solve, the reason explains that the Course starts from white, and the suggestion is *First cross*. *Show me the last layer anyway* is added when the first two layers are solved.
  - Partly solved: the reading names what is done and how far the next Stage is, using that Stage's Objective progress measure (for example "2 of 4 first-layer corners are in place"). At the last layer, the reading names the Case when exactly one fits.
- **Re-placement (#252):** Learn → *Find my place* → *Scan my cube* first shows "This keeps your progress. Stages you've finished stay as they are.", then the usual scan, review and Placement. Accepting only adds progress. If the target is already reached, it shows "You've already reached *Stage*." with *Open Stage* and writes nothing. With `PLACEMENT` OFF, *Find my place* offers only *Choose my level* (Story 14.1) and has no dead control.
- **Case recognition:** two opposite top-corner views with yellow on top. Match under AUF, intersect the candidates from the two captures, and answer within 1 s. Never pick silently among several candidates. Offer *Mark as Learning*, never apply it automatically.
- **Acceptance:** on the main app build from the real entry point, never a debug-only launcher. The #251 and #252 work is accepted on the Lenovo Tab M10 Plus 3rd Gen (the minimum camera spec) and the Galaxy S24 FE, with `PLACEMENT` raised in debug. 11.1 and 11.3 capture need device session C with real cubes. 11.3 matching is JVM-tested.
- **Privacy:** frames stay in memory and are never stored or sent. A manual route is always one tap away.

## Technical Decisions

- **Flags:** the `FeatureFlags` port maps `QUICK_SCAN`, `PLACEMENT` and `CASE_RECOGNITION` to OFF, BETA or GA per build type in `build-logic`. Each can be above OFF only when `CAMERA_SCAN` is above OFF in the same build type, or the build fails. Debug raises a flag only in the PR that wires its main-app entry point (`FeatureFlagLevels.byBuildType["debug"]`). Code never reads `BuildConfig`.
- **Recognition:** only the CameraX adapter in `app/src/camera/` touches pixels, reached through the `ScanLauncher` port. Quick Scan's corner detection and unwarp emit ordinary `FaceSample`s. Only `ScanAssembler` rotates samples into `Facelets` order, and the Editor and the Quick Scan hand-over also go through it.
- **Placement:** reuse `core/application/.../usecase/PlaceFromScan.kt` as the only writer of `StageSkippedByPlacement`. Placement never calls the Solver. Stage Predicates and Objective progress come from content data, and all text is `Text(key, args)`.
- **Only add, never downgrade:** `PlaceFromScan` may run on existing progress. It appends `StageSkippedByPlacement` only for earlier Stages that are `LOCKED` or `IN_PROGRESS`, and `StageUnlocked` only for a target not yet unlocked. It never writes `ProgressReset`, never re-locks, and never touches Lesson completions, Checkpoints, attempts or Algorithm Statuses. `progress_event` is append-only and goes through the single serial writer with idempotency keys.
- **Case naming:** `LastLayerMatcher` in `:core:domain` is the only definition of Case patterns and AUF. Placement's "It looks like {case}." and Recognize both use it. The LL is the face opposite the solved F2L color, rotated to U.

## UX & Interaction Patterns

- **Placement outcomes, checked in this order.** Each outcome gives a reading, then a reason, then an action:
  - **Solved:** "Your cube is solved." / "Scramble it and scan again, or start Stage 1." Actions: *Scan again*, *Start Stage 1*.
  - **1–2 turns from solved:** "Your cube is one turn from solved: {move, in words}." (or "two turns from solved: … then …") / "Finish it, then scramble it and scan again, or pick a Stage." Actions: *Scan again*, *Choose another Stage*.
  - **Nothing solved:** "Your cube is fully mixed. Nothing is solved yet." / "*Cube fundamentals* starts from here."
  - **Non-white:** "Your {color} cross is solved, nice work." (or "Your first two layers are solved, nice work. You started from {color}.") / "Our lessons start with the white face and leave yellow for the end, so to follow them, start with the white cross." Suggests *First cross*, with *Choose another Stage*, plus *Show me the last layer anyway* (suggests *Last-layer orientation*) when the first two layers are solved.
  - **Partly solved:** for example "Your white cross is done, and 2 of 4 first-layer corners are in place." / "*First-layer corners* picks up from here."
  - **At the last layer:** "Your first two layers are solved. Only the last layer is left: the top needs orienting." (or "…the top is oriented; the pieces need moving into place."), plus "It looks like {case}." when exactly one Case fits / "*Last-layer orientation* (or *Faster last layer*) covers this." Offers *Faster last layer* or Stage 5/6.
- **Placement details:** accepting *Faster last layer* or a 2-look Stage marks Stages 1–7 skipped by placement. *Choose another Stage* lists Stages 1–7 and the 2-look Stages. *Enter colors instead* or a camera denial opens the Manual Cube Editor, and its accepted cube is placed like a scanned one. No exclamation marks.
- **Placement result visual:** the reading in `body` (`ink-primary`), the reason in `meta` (`ink-secondary`), the suggestion "Start at *Stage*?" in `title` with its buttons, then the set-up note in `meta`. Plain text only: no cube net, icons or color in this pass.
- **TalkBack:** Placement reads the reading, then the reason, then the suggestion. A BETA feature reads "<feature>, BETA".
- **Entry points:** *Start from my cube* is on Welcome (first launch only), on Recognize's "The first two layers aren't solved yet.", and on the failed skip-ahead check ("Let's find where to start"). The Learn top app bar has *Find my place*, a sheet with *Scan my cube* (hidden when `PLACEMENT` is OFF) and *Choose my level*. Links to a feature that is OFF are removed.
- **BETA:** one shared BETA label component sits inside the feature's control, with the tooltip "May misread colors in poor light. Manual entry always works."
- **Quick Scan capture:** a Y-shaped corner guide and a "1 / 2" counter. *Enter colors instead* stays visible.
- **Recognize:** a unique match shows "OLL 45 · T-shape" and opens the Algorithm detail with its Demonstration playing. Several matches show "This could be one of 3 cases" with tiles and *Scan another angle*. No match shows "I couldn't match this to a case. Try another angle or better light." An already solved LL shows "Your last layer is already solved." with *Back to Library*.

## Cross-Story Dependencies

- 11.1 depends on 10.2 and 10.3. After it come 10.18 (Quick Scan detects the cube's real edges and corners in the frame) and then 10.17. If 10.18 slips, Quick Scan ships BETA or OFF without blocking the rest of v1.0.
- 11.2 depends on an accepted Full scan (10.5) or an accepted Editor cube. Its base PR wires Welcome's `onStartFromCube` in `AppShell` and raises `PLACEMENT` to GA in debug, with an AppShell test that taps through to the scan. The #251 and #252 fixes are issue builds against the amendment: #251 comes before #252, and both come after #253 (Editor messages) and #249 (paint mode). They share the same Placement result. *Find my place* is shared with Story 14.1's *Choose my level* (#209, `StageSkippedByChoice`).
- 11.3 depends on 10.2 and on `LastLayerMatcher` and the Algorithm Library (Epic 9). Its *Start from my cube* link shows only when `PLACEMENT` is on.
- Story 10.11 raises `CAMERA_SCAN`, `QUICK_SCAN` and `PLACEMENT` for v1.0 from the test-matrix evidence, which adds the `CAMERA` permission. This happens before release Stories 12.3, 12.4 and 12.5.
