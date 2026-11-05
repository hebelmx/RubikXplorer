---
title: 'Story 5.5: Describe the cube to TalkBack'
type: 'feature'
created: '2026-11-04'
baseline_revision: '354fceed74ef973d0e541bf7d7d191fe3c416315'
status: 'done'
review_loop_iteration: 0
followup_review_recommended: true
context:
  - '{project-root}/_bmad-output/implementation-artifacts/epic-5-context.md'
  - '{project-root}/AGENTS.md'
warnings: ['oversized']
deferred:
  - summary: >-
      Nothing verifies on a device that TalkBack actually speaks the live-region changes: the Read faces text when the action toggles it, and a Certainty change on the Lesson frame label (nor that the label stays silent on first composition).
    evidence: |-
      maybe-false. FrameLabelTest and Cube3DRendererTest assert Compose semantics and platform AccessibilityNodeInfo (Polite live region, stateDescription, contentDescription) only; Robolectric has no TalkBack, and virtual Lessons always confirm VERIFIED, so no real Certainty change occurs yet. Settle with a TalkBack run on a device or emulator (toggle Read faces; force a Certainty change once Physical-cube Mode lands in Epic 8) alongside the pending hardware stories.
    location: >-
      app/src/main/kotlin/dev/rubikxplorer/app/ui/lesson/LessonScreen.kt FrameLabel; app/src/main/kotlin/dev/rubikxplorer/app/render/Cube3DRenderer.kt Content semantics
    severity: medium (unverified)
---

<intent-contract>

## Intent

**Problem:** On the Lesson stage TalkBack only hears "Cube, white on top, green in front", so a screen-reader Learner cannot tell Objective progress or the stickers. The stage has no frame label, Certainty changes are never announced, and focus order is plain layout order (NFR-7, AD-17, UX-DR29).

**Approach:** A pure `CubeSummary` in `:core:domain` turns `(CubeState, Predicate)` into `Text` parts. The Lesson passes the resolved summary to `Cube3DRenderer`, whose cube node gains a *Read faces* custom action that switches its description to a face-by-face reading. The Lesson Learner stage gains a frame label that announces Certainty. Traversal indices fix the focus order.

## Boundaries & Constraints

**Always:**
- `CubeSummary.of(state: CubeState, predicate: Predicate): List<Text>`, rules by predicate:
  - `WhiteCrossSolved`: `msg.cube-summary.cross-solved` ("White cross solved") when satisfied, else `msg.cube-summary.cross-progress` {done, total} ("{done} of {total} white cross edges placed").
  - `WhiteCornersSolved`: `msg.cube-summary.corners-solved` ("First-layer corners solved") / `msg.cube-summary.corners-progress` ("{done} of {total} first-layer corners placed").
  - `WhitePieceSolved`: `msg.cube-summary.piece-placed` / `msg.cube-summary.piece-not-placed` with `piece` = `DiagnosisRules.selectId(name)`. The ICU select reuses `msg.hint.clue.piece`'s names ("white-green edge placed" / "… not placed yet").
  - `StateReached`: `msg.cube-summary.pattern-reached` ("Pattern reached") / `msg.cube-summary.pattern-progress` ("{done} of {total} pieces match the pattern").
  - `AllOf`: the children's parts in order, flattened, without duplicates.
  - `AnyOf`: the parts of the first satisfied child, else of the first child with the highest done/total (compared exactly).
  - `AlwaysTrue`: none.
- Progress numbers come only from `predicate.progress(state)` / `isSatisfied`. No re-implemented piece logic.
- `Cube3DRenderer.Content` gains `summary: String? = null`. When non-null it is the cube node's `contentDescription`; when null the current `cube_description` text stays. `stateDescription` (Solved / Not solved) is unchanged either way.
- The `cube3d` node always carries a custom action labelled `cube_read_faces` ("Read faces"). Invoking it switches the node to faces mode:
  - `contentDescription` becomes six sentences in URFDLB order, each `"<Face> face: c1, c2, …, c9."`: face names from `editor_face_*`, color names from `colorName`, lowercased, the 9 colors row-major from `renderedFacelets()`.
  - The node is a Polite live region, so TalkBack speaks the new text right away.
  - The action label becomes `cube_read_summary` ("Read summary"); invoking that returns to summary mode, with no live region.
  - Faces mode also ends when the drawn facelets change.
- Lesson Learner stage (Guide me / Test me / Let me try): `summary` = `CubeSummary.of(session.virtual, lesson.completion)` resolved through `ContentStrings` and joined with `"; "`. With no parts, pass null.
- Lesson frame label: a `FrameLabel(certainty)` composable at the start of the stage toolbar row.
  - It shows `frame_virtual` in the free cube's frame style, tagged `stage:frame`.
  - `stateDescription` is `certainty_verified` / `certainty_likely` / `certainty_unknown`, from `decision.certainty`, and the node is a Polite live region, so every Certainty change is announced. The first composition is not announced.
- Focus order on the Lesson Learner stage, through `isTraversalGroup` / `traversalIndex`: frame label → cube description (`cube3d`) → Move key bar → stage toolbar (caption, Undo, Redo, Reset, view controls) → the lesson panel (Objective, step card, Assistance, Hint, Next) → Teaching Mode switcher (last in the panel).
- All new Learner text goes in `en.json` (`msg.cube-summary.*`) or `strings.xml`. No literals (LearnerTextPolicy).

**Never:**
- No change to `CubeRenderer`, `PresentationItem`, `SessionEvent`, `Reduce`, `Assess`, `TutorDecision`, predicates' behaviour, the free cube's frame label, Show me's `DemonstrationPlayer` stage, `build-logic/`, `_bmad/`, `.claude/skills/`, `_bmad-output/planning-artifacts/`, or spike source sets.
- No `View.announceForAccessibility` (deprecated). No new dependency. No logging of Cube States. No live region on the cube node in summary mode (a Move must not trigger a re-read).

## I/O & Edge-Case Matrix

| Scenario | Input / State | Expected Output / Behavior | Error Handling |
|----------|--------------|---------------------------|----------------|
| Stage 3 partial | cross solved, 2 corners placed; `AllOf(cross, corners)` | `[cross-solved, corners-progress{2,4}]` → "White cross solved; 2 of 4 first-layer corners placed" | — |
| Cross partial | 1 cross edge placed; `WhiteCrossSolved` | `[cross-progress{1,4}]` | — |
| Solved | `SOLVED`; `AllOf(cross, corners)` | `[cross-solved, corners-solved]` | — |
| Piece | `WhitePieceSolved("white-green")` on SOLVED / after a move away | `piece-placed{white_green}` / `piece-not-placed{white_green}` | — |
| Pattern | `StateReached("U")` on SOLVED.apply(U) / SOLVED | `pattern-reached` / `pattern-progress{n,20}`, where n = progress.done | — |
| AnyOf | `AnyOf(cross, corners)`, corners better | parts of the corners child only | — |
| Nothing | `AlwaysTrue`, or `AllOf(AlwaysTrue)` | empty list → renderer falls back to `cube_description` | — |
| Duplicates | `AllOf(cross, cross)` | one cross part | — |

</intent-contract>

## Code Map

- `core/domain/src/main/kotlin/dev/rubikxplorer/domain/predicate/*.kt`: predicate variants and `progress`/`isSatisfied`. `WhitePieceSolved.piece` is the canonical name. `AnyOf.best` is private, so reproduce its rule from public `progress`.
- `core/domain/src/main/kotlin/dev/rubikxplorer/domain/tutor/DiagnosisRules.kt:196` `selectId` (internal, same module).
- `core/domain/src/main/kotlin/dev/rubikxplorer/domain/text/Text.kt`: `Text(key, args)`.
- `core/domain/src/main/resources/content/strings/en.json`: add `msg.cube-summary.*`. `ShippedContentTest` checks that every `Text("…")` key exists and its args match.
- `app/src/main/kotlin/dev/rubikxplorer/app/render/Cube3DRenderer.kt` `Content`: `clearAndSetSemantics` block on `cube3d` (add the custom action, the faces-mode description, and the live region); `drawn` facelets; `U_CENTER`/`F_CENTER` fallback description.
- `app/src/main/kotlin/dev/rubikxplorer/app/ui/editor/StickerReviewNet.kt:200` `colorName`; `strings.xml` `editor_face_*`, `color_*`, `frame_virtual`.
- `app/src/main/kotlin/dev/rubikxplorer/app/ui/lesson/LessonScreen.kt`: `LessonBody` (has `session`, `decision`, `lesson`, `strings`), `LearnerStage` toolbar row / `renderer.Content` / `MoveKeyBar`, and the `stage`/`panel` lambdas.
- `app/src/main/kotlin/dev/rubikxplorer/app/ui/lesson/LessonPanel.kt:126`: the `TeachingModeSwitcher` sits before Hint/Next, so it needs a traversal index to come last.
- `app/src/main/kotlin/dev/rubikxplorer/app/ui/freecube/FreeCubeScreen.kt:153`: the frame label's style, to mirror.
- `app/src/main/kotlin/dev/rubikxplorer/app/content/ContentStrings.kt`: `resolve(Text)`.
- Tests:
  - `app/src/test/.../render/Cube3DRendererTest.kt` `assertSpoken`: the platform `AccessibilityNodeProvider` walk with the accessibility shadow enabled. Reuse it for traversal: `provider.addExtraDataToAccessibilityNodeInfo(id, info, "android.view.accessibility.extra.EXTRA_DATA_TEST_TRAVERSALBEFORE_VAL", null)` yields Compose's traversal chain.
  - `app/src/test/.../ui/lesson/LessonScreenTest.kt`: Lesson harness (`createComposeRule`, `TestLessons.kt` in `sharedTest`).

## Tasks & Acceptance

**Execution:**
- `core/domain/src/main/kotlin/dev/rubikxplorer/domain/predicate/CubeSummary.kt`: the pure summary, per Always.
- `core/domain/src/test/kotlin/dev/rubikxplorer/domain/predicate/CubeSummaryTest.kt`: every I/O matrix row.
- `core/domain/src/main/resources/content/strings/en.json`: the 8 `msg.cube-summary.*` messages.
- `app/src/main/res/values/strings.xml`: `cube_read_faces`, `cube_read_summary`, `cube_face_colors` ("%1$s face: %2$s."), `certainty_verified`/`likely`/`unknown`.
- `app/src/main/kotlin/dev/rubikxplorer/app/render/Cube3DRenderer.kt`: the `summary` param, the Read faces/summary custom action, the faces text, and the faces-mode live region.
- `app/src/main/kotlin/dev/rubikxplorer/app/ui/lesson/LessonScreen.kt`: the summary wiring, `FrameLabel` in `LearnerStage`, and traversal indices.
- `app/src/main/kotlin/dev/rubikxplorer/app/ui/lesson/LessonPanel.kt`: switcher traversal index (last).
- Tests in `Cube3DRendererTest`/`LessonScreenTest` (or a new `app/src/test/.../ui/lesson/LessonAccessibilityTest.kt`) for the ACs below.
- `docs/ARCHITECTURE.md`: one line saying the cube's TalkBack summary is `CubeSummary(CubeState, completion)` plus a Read faces action, and that Certainty is announced through the Lesson frame label.

**Acceptance Criteria:**
- Given a Stage 3 Lesson whose virtual state has the cross and 2 corners, when the Lesson stage shows, then the platform node for `cube3d` has contentDescription "White cross solved; 2 of 4 first-layer corners placed" and a custom action "Read faces".
- Given that node, when "Read faces" is performed, then its contentDescription is the six "<Face> face: …" sentences of the drawn facelets in URFDLB order, it is a Polite live region, and the action reads "Read summary"; performing that action restores the summary with no live region.
- Given a Move committed through the Move keys, when it drains, then the summary reflects the new progress, and faces mode (if on) has reset.
- Given the Lesson screen in Guide me, Test me or Let me try with TalkBack enabled, when the platform traversal chain is walked, then `stage:frame` comes before `cube3d`, `cube3d` before the first Move key, the Move key bar before `lesson:objective`, and `lesson:next` before `lesson:modeSwitcher`.
- Given `FrameLabel` composed with VERIFIED, when the certainty changes to LIKELY and then UNKNOWN, then the node is a Polite live region and its stateDescription follows ("Verified" → "Likely" → "Unknown").
- Given the free cube, when TalkBack reads it, then the existing `cube_description` and Solved state are unchanged (existing tests stay green) and "Read faces" is available.
- Given `./gradlew spotlessCheck build`, when it runs, then it passes (Kover ≥ 80 % on `:core:domain`, LearnerText, no new dependency).

## Spec Change Log

## Review Triage Log

### 2026-11-04 — Review pass
- verdicts: 14 findings — high 0, medium 5, low 6, false 0, maybe-false 3
- findings:
  - `[low]` `[reject]` (blind) Summary comes from `session.virtual`, but faces and Solved come from the renderer's `drawn`, so they can differ while queued turns drain or a demonstration plays. The gap lasts at most one turn per queued item; during a demonstration, describing the Learner's own state is the intended reading ("derived from `CubeState`"). Syncing would need the summary computed from rendered facelets, which means new plumbing.
  - `[low]` `[reject]` (blind) The Lesson summary replaces the "white on top, green in front" orientation text. The intent's example summary leaves orientation out, and Read faces still gives every face's center color. Adding orientation would change the specified wording.
  - `[medium]` `[patch]` (blind) The Read faces test only used a solved cube, so a wrong sticker order would pass. Cube3DRendererTest now applies R, reopens Read faces and asserts the full hand-written URFDLB row-major text through the platform node.
  - `[maybe-false]` `[defer]` (blind) FrameLabelTest checks semantics, not real announcements, and does not show that first composition is silent. Settling it needs TalkBack on a device; see `deferred`.
  - `[low]` `[patch]` (blind) The traversal test checked only selected pairs, on one wide layout. It is now a shared helper run on wide and compact screens. It asserts that the cube directly follows the frame label, then Move key → Undo → objective → Hint → Next → switcher.
  - `[medium]` `[patch]` (blind) The summary test recomputed its expectation with `CubeSummary` itself. A new test asserts the literal platform text: "White cross solved; 3 of 4 first-layer corners placed" on the corner Lesson's entry, and "… 2 of 4 …" after L D L'.
  - `[low]` `[reject]` (edge) Summary is ahead of the drawn state while queued Moves drain. Same root and reason as the first row.
  - `[medium]` `[patch]` (verification-gap) Read faces was never asserted on a scrambled cube. Same root and fix as the solved-cube row.
  - `[medium]` `[patch]` (intent) The Lesson summary test re-derives the same choice. Same root and fix as the literal-summary row.
  - `[medium]` `[patch]` (intent) The example wording was never resolved end to end. Same root and fix as the literal-summary row.
  - `[maybe-false]` `[defer]` (intent) Nothing checks that Read faces is actually spoken. This is the same device-TalkBack gap as the deferred row.
  - `[low]` `[patch]` (intent) Focus order was tested at one width, and the toolbar's place and the rest of the panel were not asserted. Same root and fix as the traversal row.
  - `[maybe-false]` `[defer]` (intent) No audible Certainty announcement, and a real Lesson never changes Certainty. This is the deferred device-TalkBack gap; virtual Lessons always confirm VERIFIED until Epic 8.
  - `[low]` `[patch]` (intent) Show me's cube had no summary, so TalkBack heard only the generic description there. `DemonstrationPlayer` gained an optional `summary`. The Lesson now passes `CubeSummary.of(start.apply(moves.take(position)), completion)`. A new test checks "3 of 4" at the start and "First-layer corners solved" at the end. Show me has no Move key bar, so the focus-order AC stays with the Learner modes.

## Design Notes

A live region only while in faces mode makes TalkBack speak the faces right after the action, without re-reading the summary after every Move. Virtual Lessons always confirm VERIFIED, so the Certainty announcement is tested on `FrameLabel` itself. The Physical-cube Mode (Epic 8) is where the label's value will change.

## Verification

**Commands:**
- `./gradlew :core:domain:test --tests 'dev.rubikxplorer.domain.predicate.*' --tests 'dev.rubikxplorer.domain.content.*'`: expected to pass.
- `./gradlew :app:testDebugUnitTest --tests 'dev.rubikxplorer.app.render.*' --tests 'dev.rubikxplorer.app.ui.*'`: expected to pass.
- `./gradlew spotlessCheck build`: expected to pass.

**Manual checks (if no CLI):**
- `git diff --stat <baseline>` shows nothing under `_bmad-output/planning-artifacts/`, `_bmad/`, `.claude/skills/`, `build-logic/`, or `app/src/spike3d*`.

## Auto Run Result

Status: done

**Summary:** TalkBack now reads the cube's Objective progress through a pure `CubeSummary.of(CubeState, Predicate)` in `:core:domain`, which returns `msg.cube-summary.*` `Text` parts. Lessons resolve it against the Lesson's `completion` and pass it to `Cube3DRenderer` as `summary`, in the Learner modes and in Show me, where it describes the Demonstration's shown state.

The `cube3d` node has a *Read faces* / *Read summary* custom action. Faces mode reads six "<Face> face: …" sentences from the drawn facelets as a Polite live region, and it resets when the drawn state changes.

The Learner stage gained a `FrameLabel` (VIRTUAL, with Certainty as a Polite-live-region `stateDescription`). Traversal groups and indices set the focus order: frame label → cube → Move key bar → stage toolbar → lesson panel → Teaching Mode switcher.

**Files changed:**
- `core/domain/.../predicate/CubeSummary.kt` (new) and `CubeSummaryTest.kt` (new): summary rules per predicate.
- `core/domain/src/main/resources/content/strings/en.json`: 8 `msg.cube-summary.*` messages.
- `app/.../render/Cube3DRenderer.kt`: the `summary` parameter, the Read faces/summary action, and the faces-mode live region.
- `app/.../ui/lesson/LessonScreen.kt`: summary wiring (Learner modes and Show me), `FrameLabel`, and traversal indices.
- `app/.../ui/lesson/LessonPanel.kt`: the panel traversal group, with the switcher last.
- `app/.../ui/lesson/DemonstrationPlayer.kt`: optional `summary` passed through.
- `app/src/main/res/values/strings.xml`: Read faces/summary, face line, and Certainty words.
- Tests:
  - `Cube3DRendererTest`: faces toggle, reset, and scrambled ordering.
  - `FrameLabelTest` (new).
  - `LessonScreenTest`: summary follows Moves, literal corner summary, Show me summary, and platform traversal on wide and compact screens.
- `docs/ARCHITECTURE.md`: one line on `CubeSummary`, Read faces and the Certainty frame label.

**Review findings:** 14 findings.
- Patches applied: 4 entries.
  - medium 2: scrambled Read faces test; literal summary test.
  - low 2: full traversal order on wide and compact screens; Show me summary.
- Deferred: 1 entry. Device TalkBack speech of the live-region changes (Read faces, Certainty) is unverified (medium, unverified).
- Rejected:
  - Summary vs drawn-state lag (low, transient; the intended reading is the Learner's `CubeState`).
  - Orientation dropped from the Lesson description (low; the intent's example wording excludes it, and Read faces gives the centers).

**Follow-up review recommendation:** true. Two medium entries were patched on this first pass (patched: high 0, medium 2, low 2). Named unverified risk: the new tests prove Compose semantics and the platform node tree, but no run has confirmed TalkBack speaks the faces or the Certainty live-region changes.

**Verification:**
- `./gradlew :core:domain:test --tests 'dev.rubikxplorer.domain.predicate.*' --tests 'dev.rubikxplorer.domain.content.*'`: pass. CubeSummaryTest has 8 tests.
- `./gradlew :app:testDebugUnitTest --tests 'dev.rubikxplorer.app.render.*' --tests 'dev.rubikxplorer.app.ui.*'`: pass. LessonScreenTest 54, Cube3DRendererTest 7, FrameLabelTest 1, 0 failures.
- `./gradlew spotlessCheck build`: pass after the patches (Kover, LearnerText, lint, APKs).
- Scope check: nothing changed under `_bmad-output/planning-artifacts/`, `_bmad/`, `.claude/`, `build-logic/` or spike source sets.
- Matrix audit: every row is covered in CubeSummaryTest, including an explicit AnyOf row where the corners read better and the solved-cube pattern-progress row. The "Nothing" fallback is covered by Cube3DRendererTest's default description.

**Residual risks:**
- TalkBack speech is unverified on a device (deferred).
- Certainty never changes in virtual Lessons, so only the `FrameLabel` component proves the announcement wiring.
- On a short compact screen, a scrolled-away panel drops out of the traversal until TalkBack scrolls (platform behaviour).
