# Epic 9 Context: Faster last layer and the Algorithm Library

<!-- Generated from planning artifacts. Regenerate with compile-epic-context if planning docs change. -->

## Goal

A Learner who can already solve can browse and watch all 78 verified OLL/PLL Algorithms, track their own Algorithm Status, and take the 2-look LL mini-Course through to mastery. This is the step after the beginner Course. Every Algorithm is proven correct at build time and animated by the engine from Notation, so the Library stays small, offline and impossible to get silently wrong.

## Stories

- Story 9.1: Algorithm and Case content for all 78
- Story 9.2: One LastLayerMatcher and build-failing Algorithm verification
- Story 9.3: Recognition diagrams derived from state
- Story 9.4: Browse and filter the Library
- Story 9.5: Algorithm detail with Demonstration
- Story 9.6: Set my Algorithm Status
- Story 9.7: 2-look LL Lessons
- Story 9.8: 2-look completion and mastery

## Requirements & Constraints

- **Library scope.** 57 OLL + 21 PLL, grouped by family, one primary Algorithm per Case (alternatives are out of scope). For any Algorithm the Learner can watch its Demonstration, see its recognition diagram and Notation, and set its Algorithm Status. Filters cover family and status.
- **Verification fails the build.** For each Algorithm, the inverse applied to solved must match its own Case and no other, and the Algorithm must then solve the LL up to AUF. A mistyped Algorithm breaks the build.
- **Content provenance.** Algorithms and Case names come from common knowledge, with no copied diagrams or prose. Sources are recorded in the PRD memlog. The primary Algorithms and the exact 2-look set are decided during authoring.
- **2-look set.** About 16 Cases (3 + 7 OLL, 2 + 4 PLL), all drawn from the Library. A Case may be solved by a sequence of Library Algorithms, such as the edge-orientation dot.
- **Unlock.** The mini-Course unlocks after the Course is completed or passed by a skip-ahead check.
- **2-look states.** *Completed* means every Lesson predicate has been met. *Mastered* means two hint-free LL solves of fresh Scrambles using only 2-look Algorithms. This rule differs from Stage mastery.
- **Algorithm Status is the Learner's own.** Values are *Not started*, *Learning* and *Learned*. The app may offer a change but never sets one automatically. It persists across restarts and process death, and *Reset progress* includes it after confirmation.
- **Offline and localizable.** The Library needs no network. All Learner-facing text lives in content strings or data (English only).
- **Accessibility and layout.** Content labels on every control, WCAG AA contrast, scalable text, a non-gesture alternative for every essential action, color-blind glyphs on diagrams. Phones and tablets, portrait and landscape.
- **Cut order.** Library recognition diagrams may be cut if the schedule slips. The verified Library animations are never cut.
- **v1.1 only.** Case recognition from a photo is deferred with all camera work. The matcher must support it (partial views return candidates), but v1.0 shows no *Recognize case* entry or cross-link.

## Technical Decisions

- **Content is data.** Algorithms and 2-look Lessons are JSON under `:core:domain` resources, parsed with kotlinx.serialization. `content/schema/*.schema.json` is the single shape source and is tested against the Kotlin model. Algorithm records carry id, set, family, Case name and Notation.
- **IDs.** Case (`case-oll-45`) and Algorithm (`alg-oll-45-a`, declaring its `caseId`) are distinct, stable namespaces, and an ID is never reused once shipped. 2-look Lessons reference Algorithm IDs and never copy Notation.
- **Derived, never stored.** Case states and recognition diagrams are computed from Notation and the matcher.
- **One `LastLayerMatcher` in `:core:domain`.** It is the only definition of Case patterns and AUF, used by verification, diagram derivation, 2-look predicates and later Case recognition.
  - Canonical frame: the LL is the face opposite the solved F2L color (YELLOW for the Course), rotated to U.
  - OLL matches by orientation mask under pre-AUF `U^k`.
  - PLL matches by permutation under pre- and post-AUF and `y^k` viewing.
  - Partial views return the full candidate set.
- **Predicates.** LL-step predicates join the sealed `Predicate` registry and are color-anchored and orientation-invariant. Each implements `preservedPieces` and `ObjectiveProgress`, and each Lesson needs a legal path test.
- **Sessions.** The Library, its detail screen and its Demonstrations are not sessions and emit no `SessionEvent`. 2-look Lessons run as `SessionKind.TWO_LOOK` through the one `SessionHost` and pure reducer, resume on cold start, and stay subject to the one-active-session rule.
- **Playback.** The one pure `PlaybackController` drives Library Demonstrations through `CubeRenderer` and is never checkpointed. `Notation.parse` and `Notation.format` are the only string-to-Move conversions.
- **Algorithm Status persistence.** `algorithm_status_event` is an append-only Room table keyed by `algorithmId`, with `SetAlgorithmStatus` as its single writer. It exposes `Flow<Map<AlgorithmId, Status>>`. Reset is an appended event. Each Room schema change ships a migration and a migration test.
- **Diagrams.** Every sticker surface uses the `StickerAppearance` table (glyphs when on), with no hand-drawn assets. The diagram cut is a build constant until a flag is added through `bmad-correct-course`, because the `FeatureFlags` enum is closed and has no entry for it.

## UX & Interaction Patterns

- **Navigation.** Library is one of the four tabs, with 4 tile columns at expanded width. Algorithm detail keeps the bottom nav.
- **Algorithm tile.** `surface-raised`, `rounded.md`. Shows the diagram (U face plus side bands), Case name in `label`, Algorithm in `notation`, and status as small text (`Learning` / `Learned`, nothing for *Not started*). Without diagrams it shows name and Notation only, with no placeholder art.
- **No gamification.** No stars, scores or badges, and the Learned count is never framed as an achievement.
- **Filter chips.** Neutral M3 filter chips; selected is `surface-raised` with ✓, never violet. Empty result: "No algorithms marked Learning yet." with a link to 2-look.
- **Detail.** Tapping a tile opens the Case diagram, Notation, and a Demonstration from the Case state with free View Rotation. The shared transport offers play/pause, step, restart and speed 0.5×/1×/2×. Scrubbing snaps to Move boundaries, and the rendered cube must match that boundary. Status changes only here, through an explicit control. *Not started* reads "Not in your set yet."
- **Learn tab.** *Faster last layer* uses the shared Stage row, showing Completed / ◆ Mastered and "Mastery: 1 of 2" after the first qualifying solve.
- **2-look Lesson.** Recognition diagram, Demonstration, and the Teaching Modes with hints. At the end: "Add these algorithms to Learning?" Accepting calls `SetAlgorithmStatus`; declining changes nothing.
- **Milestone.** A ≤ 1.2 s burst of the six sticker colors, then a `display` line, on mastery; static under reduce motion.
- **Reset sheet.** *Reset progress* lists exactly what is erased, now including Algorithm Statuses.

## Cross-Story Dependencies

- 9.1 supplies the content for everything else. 9.2 verifies it and provides the matcher that 9.3 and 9.7 need.
- 9.3 supplies the diagrams for the 9.4 tiles, 9.5 detail and 9.7 Lessons. 9.4 is the entry point to 9.5, which hosts the 9.6 status control.
- 9.6's `SetAlgorithmStatus` must exist before the 9.7 offer. 9.8 builds on 9.7's predicates and `TWO_LOOK` sessions.
- Builds on the Lesson framework and Teaching Modes (Epic 2), Hint Ladder (Epic 4), 3D renderer and View Rotation (Epic 5), persistence (Epic 6), and Course completion, skip-ahead, reset sheet and milestones (Epic 7).
- Epic 9 runs after Stories 12.6–12.10 and before the 3.x leftovers, the Story 7.8 pilot build and Stories 12.1–12.5.
