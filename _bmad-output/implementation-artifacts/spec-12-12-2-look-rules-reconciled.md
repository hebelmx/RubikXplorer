---
title: '2-look rules reconciled'
type: 'feature'
created: '2026-11-10'
status: 'done'
baseline_revision: 'b48613119f01c2461f0b1c4934b297d61b8b10db'
review_loop_iteration: 0
followup_review_recommended: false
context:
  - '{project-root}/_bmad-output/implementation-artifacts/epic-12-context.md'
  - '{project-root}/core/application/src/main/kotlin/dev/cubentia/application/session/TRANSITIONS.md'
warnings: ['oversized']
deferred: []
---

<intent-contract>

## Intent

**Problem:** Story 9.8 built 2-look *Mastered* without *Completed* (`TwoLookMastery.kt:36`), so the *Faster last layer* row can read ◆ *Mastered* over unfinished Lessons; the milestone plays from LL practice regardless. Epic 9 retro items 4–7 also left: stale `TRANSITIONS.md` text on the LL-practice `practiceEntry` snapshot field, no test pinning `TwoLookSolve` to `LastLayerMatcher` (AD-19 as narrowed by SCP 2026-11-10), an untested shell choice of recognition diagram, and 2-look Learner copy in `strings.xml` with no gate stopping more (epic-2-retro-item-3).

**Approach:** `mastered = completed && qualifyingSolves >= solvesRequired` (pre-completion solves count); the row shows "Mastery: N of 2" whenever a qualifying solve exists and it is not mastered; the 2-look milestone plays once on the transition to mastered, whether the last step was an LL practice solve or the last 2-look Lesson. Add the cross-check and recognition tests, move the six 2-look `strings.xml` strings to `en.json`, extend `checkLearnerText` to fail on any `strings.xml` name outside a checked-in baseline, and fix `TRANSITIONS.md`.

## Boundaries & Constraints

**Always:** Glossary terms verbatim; Learner text via content keys resolved with `ContentStrings` (wording unchanged when moved); `LastLayerMatcher` stays the only Case/AUF definition, `TwoLookSolve` unchanged; `:core:*` stays Android-free; JVM/Robolectric tests for every AC; gate changes live in `build-logic` and get a build-logic test.

**Never:** no Room schema change, no new dependency, no new `SessionEvent`; do not change the LL practice scramble pool (already all 78 Library Algorithms, `AppShell.newTwoLookPractice`) or `TwoLookSolve` logic; do not touch the 12.11 session-ending code; never edit planning artifacts, `_bmad/` or `.claude/skills/`; no new wording beyond what is moved (keep "!" only in the existing milestone line).

## I/O & Edge-Case Matrix

| Scenario | Input / State | Expected Output / Behavior | Error Handling |
|----------|--------------|---------------------------|----------------|
| Solves before completion | 2 qualifying LL solves, some 2-look Lesson not completed | status IN_PROGRESS (or COMPLETED if all done), `mastered=false`, row shows "Mastery: 2 of 2", no ◆ | — |
| One solve | 1 qualifying solve, completed | COMPLETED, "Mastery: 1 of 2" | — |
| More solves than needed, not completed | 3 qualifying solves | "Mastery: 2 of 2" (count capped at required) | — |
| Mastered | all Lessons completed + ≥2 solves | MASTERED, no mastery line | — |
| Last Lesson completes mastery | ≥2 solves, last 2-look Lesson's predicate met | `LessonCompleted` written, row ◆ *Mastered*, 2-look milestone shown once in that Lesson; reopening shows no milestone | — |
| Practice solve without completion | 1 prior solve, Lessons unfinished, 2nd qualifying LL solve | no milestone overlay | — |
| Practice completes mastery | Lessons completed, 1 prior solve, 2nd solve | milestone as before | — |
| Reset | `ProgressReset` after everything | IN_PROGRESS, 0 solves | — |
| New `strings.xml` string | name not in baseline | `checkLearnerText` fails naming file and resource | — |

</intent-contract>

## Code Map

- `core/domain/src/main/kotlin/dev/cubentia/domain/tutor/TwoLookMastery.kt` L36 -- `mastered = completed && count >= solvesRequired`; KDoc the SCP rule.
- `core/domain/src/test/kotlin/dev/cubentia/domain/tutor/TwoLookMasteryTest.kt` -- L28 asserts MASTERED with no Lessons: change to IN_PROGRESS/not mastered; L45-50 boundary test needs lessons completed for its `mastered` assertion; add "solves before completion count".
- `core/domain/src/test/kotlin/dev/cubentia/domain/content/TwoLookContentTest.kt` -- add the AD-19 cross-check: for every 2-look Lesson, `TwoLookSolve.offPathMoves(demonstrationMoves, allTwoLookAlgorithmMoves) == 0` (algorithm set = every id in `bundle.lessonAlgorithms` for 2-look Lessons, parsed from Library notation, mirroring `app/.../content/TwoLookMoves.kt`), and entry state + demonstration satisfies `lesson.completion` (a `LastLayerStepReached`, which reads `LastLayerMatcher.stepPieces`). Assert the completion is `LastLayerStepReached`.
- `app/src/main/kotlin/dev/cubentia/app/ui/shell/LearnScreen.kt` L334-369 `FasterLastLayerRow` -- mastery line when `qualifyingSolves >= 1 && !mastered`, showing `min(qualifyingSolves, solvesRequired)`; heading, line and practice button text from content keys (needs `strings: ContentStrings` = `catalog.strings`); `TwoLookLockedContent` L386-417 heading/prerequisite likewise.
- `app/src/main/kotlin/dev/cubentia/app/ui/lesson/LessonScreen.kt` -- new param `twoLookStages: List<Stage> = emptyList()` used in both `TwoLookMastery.evaluate` calls (L179, L237; currently `emptyList()`); `isTwoLookMilestone` L217: `(twoLookPractice || isTwoLook) && initialTwoLookMastered == false && twoLookProgress.mastered && !milestoneDismissed && session.lesson?.id == lesson.id && session.objectiveMet` plus the existing physical clause for practice only; L358 result line from content keys.
- `app/src/main/kotlin/dev/cubentia/app/ui/shell/AppShell.kt` -- pass `twoLookStages = ready.bundle.twoLookStages` to both `LessonScreen` calls (Course/2-look ~L230, practice ~L289); for a 2-look Lesson, `onPracticeNewScramble` opens a fresh LL practice (`openLesson = null; twoLookScramble = newTwoLookPractice(ready).entryState; openPractice = "two-look-practice"; practiceEpoch++`) instead of the Stage-practice branch (which has no 2-look Stage). Extract the recognition choice L222-229 into `internal fun recognitionDiagramFor(bundle: ContentBundle, lessonId: LessonId): RecognitionDiagram?` and call it inside the existing `remember`.
- `app/src/main/res/values/strings.xml` L177-178, L205-208 -- delete `learn_faster_last_layer`, `learn_two_look_locked_prerequisite`, `learn_two_look_mastery`, `learn_two_look_practice`, `lesson_two_look_qualified`, `lesson_two_look_not_qualified`.
- `core/domain/src/main/resources/content/strings/en.json` near L585-592 -- add `msg.learn.faster-last-layer`, `msg.learn.two-look-locked-prerequisite`, `msg.learn.two-look-mastery` ("Mastery: {done} of {total}"), `btn.learn.two-look-practice`, `msg.lesson.two-look.qualified`, `msg.lesson.two-look.not-qualified`, same wording as the deleted resources.
- `core/domain/src/test/kotlin/dev/cubentia/domain/content/ShippedContentTest.kt` L462 `MIGRATED_RESOURCES` -- add the six names; add a TWO_LOOK_LABELS wording pin like `PHYSICAL_LABELS` L465.
- `build-logic/src/main/kotlin/dev/cubentia/buildlogic/policy/LearnerTextPolicy.kt` -- add pure `checkStringResources(file, xml, allowed: Set<String>): List<Violation>` (each `<string>`/`<plurals>` name not in `allowed` is a violation; new `Rule.STRING_RESOURCE`).
- `build-logic/src/main/kotlin/dev/cubentia/buildlogic/tasks/CheckLearnerTextTask.kt` -- optional `@InputFiles stringResources` + `@InputFile @Optional stringResourceBaseline`; `register` adds `src/main/res/values*/strings.xml` and `learner-text-baseline.txt` (project dir) when the baseline exists; keep `@SkipWhenEmpty` semantics working for `:core:*`.
- `app/learner-text-baseline.txt` (new) -- one resource name per line: every name left in `strings.xml` after the move (chrome and pre-existing deferred copy), header comment saying new Learner text goes to `en.json`.
- Tests: `build-logic/src/test/.../policy/LearnerTextPolicyTest.kt` (allowed passes, new name fails), `build-logic/src/test/.../tasks/CheckLearnerTextTaskTest.kt` (new, ProjectBuilder like `CheckMergedManifestTaskTest`: task fails on a strings.xml name outside the baseline); `app/src/test/.../ui/shell/AppShellTest.kt` L880-944 row test; `app/src/test/.../ui/lesson/LessonScreenTest.kt` L150-235 LL milestone scenario (needs `twoLookStages` and completed-Lesson events for the milestone case); new `app/src/test/.../ui/shell/RecognitionDiagramChoiceTest.kt`.
- `core/application/src/main/kotlin/dev/cubentia/application/session/TRANSITIONS.md` L224 and L273 -- LL practice restore rebuilds from `SessionSnapshot.practiceEntry` (the generated scramble = `pathEntry`, written only for PRACTICE with non-empty `twoLookAlgorithms`), falling back to `start` for older snapshots (`SessionHost.kt` L247-250); replace "No snapshot or checkpoint format changes" with the `practiceEntry` field description.

## Tasks & Acceptance

**Execution:**
- `core/domain` TwoLookMastery + tests -- mastered requires completed.
- `core/domain` TwoLookContentTest -- AD-19 cross-check.
- `core/domain` en.json + ShippedContentTest; `app` strings.xml -- move 2-look copy.
- `app` LearnScreen, LessonScreen, AppShell -- row line rule, content-key copy, milestone on Lesson or practice, 2-look Lesson practice button, `recognitionDiagramFor`.
- `build-logic` policy, task, baseline file + tests -- strings.xml gate.
- `TRANSITIONS.md` -- practiceEntry text.
- `app/src/test` -- row, milestone and recognition tests below.

**Acceptance Criteria:**
- Given two qualifying LL solves but not every 2-look Lesson completed, when the Learn tab renders, then `learn:fasterLastLayer:status` reads *In progress* or *Completed* (not *Mastered*), `learn:fasterLastLayer:mastery` reads "Mastery: 2 of 2", and an LL practice solve in that state shows no `milestone:overlay` (AppShellTest/LessonScreenTest).
- Given that state, when the last 2-look Lesson's predicate is met in `LessonScreen`, then `milestone:headline` reads "2-look last layer mastered!" once, the row reads ◆ *Mastered*, and reopening the Lesson shows no milestone.
- Given every 2-look Lesson's Demonstration, when content tests run, then `TwoLookSolve` scores 0 off-path Moves and the end state satisfies the Lesson's `LastLayerStepReached` predicate.
- Given `recognitionDiagramFor`, when shell tests run, then for the first OLL Lesson (`alg-oll-02-a`) and first PLL Lesson (`alg-pll-t-a`) it equals `RecognitionDiagram.derive` of that Algorithm.
- Given 2-look copy, when `checkLearnerText` runs, then the six strings resolve from `en.json` keys and a `strings.xml` name not in the baseline fails the task.
- Given `TRANSITIONS.md`, when read, then it describes `practiceEntry` and its restore.

## Spec Change Log

## Review Triage Log

### 2026-11-10 — Review pass
- verdicts: 16 findings — high 0, medium 0, low 15, false 1, maybe-false 0
- findings:
  - `[low]` `[patch]` (blind) The gate scans only `values*/strings.xml`, so a string in another values file bypasses it — `register` now scans `values*/*.xml`; CheckLearnerTextTaskTest adds a `messages.xml` case.
  - `[low]` `[patch]` (blind) Debug/release resource sets are not scanned — added `RESOURCE_DIRS` (`src/main/res`, `src/debug/res`, `src/release/res`); task test covers `src/debug/res/values/messages.xml`.
  - `[low]` `[reject]` (blind) The resource check silently turns off when the baseline file is missing — needed so `:core:*` modules (no res) keep working; deleting the baseline is a visible, reviewed change; a required-baseline branch adds complexity for an unlikely event.
  - `[low]` `[patch]` (blind) `<string-array>` names are not checked — regex now matches `string-array`; LearnerTextPolicyTest asserts it.
  - `[low]` `[reject]` (blind) Regex would false-flag a `<string name=...>` written inside CDATA — no CDATA in the project; a false positive fails loudly; an XML parser is more than a direct correction.
  - `[low]` `[reject]` (blind) "Mastery: 2 of 2" with *In progress* should explain the missing Lessons — the AC prescribes exactly this display; new explanatory copy is outside the story.
  - `[low]` `[reject]` (blind) Task test calls `check()` directly instead of a Gradle run — the test goes through the real `register()` configuration; `check` wiring is unchanged and the full build ran the task; a TestKit Android fixture is heavy for no distinct path.
  - `[low]` `[patch]` (edge) Strings outside main `values*/strings.xml` bypass the gate — same root cause as the first two rows; same patch.
  - `[low]` `[patch]` (edge) `string-array` (and `item type="string"`) names pass — `string-array` added (same patch as row 4); `item type="string"` is not used for strings in this project.
  - `[low]` `[patch]` (verification-gap) The 2-look Lesson milestone's practice route (`AppShell` `onPracticeNewScramble`) is untested at shell level — added AppShellLibraryTest `last two look Lesson milestone practices a fresh LL scramble` (completes the last 2-look Lesson with 2 prior solves, sees the milestone, taps practice, asserts PRACTICE `practice-two-look` with 2-look algorithms and an unmet Objective).
  - `[low]` `[reject]` (intent) AC1 row and "no milestone" are tested on different screens — each part is covered at its own surface (Learn row in AppShellTest, milestone in LessonScreenTest).
  - `[low]` `[patch]` (intent) The Learn row reaching ◆ *Mastered* in the "solves first, then completion" order was not tested on the Learn screen — AppShellTest now completes all Lessons while 3 solves exist and asserts *Mastered* with no mastery line.
  - `[low]` `[reject]` (intent) AC3's 0 off-path result is near-guaranteed and uses the 2-arg overload — the AC asks exactly this pin; the history overload adds only the entry check.
  - `[false]` `[reject]` (intent) Recognition test finds the Lesson by "contains" rather than "first referenced" — the equality assertion fails unless the chosen Algorithm is the first referenced one, so the AC is pinned.
  - `[low]` `[reject]` (intent) Gate fails on any new name, not only Learner-facing ones, and misses rewording — the baseline-by-name design is recorded in Design Notes; a deliberate baseline edit is the review point.
  - `[low]` `[patch]` (intent) `onPracticeNewScramble` change for 2-look Lessons is unrequested and untested — grouped with the verification-gap row; covered by the new shell test.

## Design Notes

- The milestone overlay covers the 2-look *Add to Learning* offer when the last Lesson triggers mastery; *Continue* closes the Lesson. Accepted: the offer is optional and can be had by reopening the Lesson.
- The baseline is a list of resource names, not wording, so chrome can be re-worded but a new name needs a deliberate baseline edit visible in review.

## Verification

**Commands:**
- `export JAVA_HOME=/usr/lib/jvm/java-25-openjdk-amd64 ANDROID_HOME=$HOME/Android/Sdk && ./gradlew spotlessApply spotlessCheck build` -- expected: BUILD SUCCESSFUL.
- `./gradlew -p build-logic test` -- expected: BUILD SUCCESSFUL.

## Auto Run Result

**Summary:** 2-look *Mastered* now requires *Completed* (qualifying LL solves made before completion still count). The *Faster last layer* row shows "Mastery: N of 2" (capped) whenever a qualifying solve exists and it is not mastered. The 2-look milestone plays once on the transition to mastered, from LL practice or from completing the last 2-look Lesson; its practice button there opens a fresh LL practice. Added the AD-19 cross-check (`TwoLookSolve` 0 off-path, end state satisfies the matcher-built predicate), a tested `recognitionDiagramFor`, moved the six 2-look strings to `en.json`, extended `checkLearnerText` to fail on string resources outside `app/learner-text-baseline.txt`, and fixed the `practiceEntry` text in `TRANSITIONS.md`.

**Files changed:**
- `core/domain/.../tutor/TwoLookMastery.kt` -- mastered = completed && solves >= required.
- `core/domain/src/main/resources/content/strings/en.json` -- six 2-look keys, wording unchanged.
- `app/src/main/res/values/strings.xml` -- six 2-look resources removed.
- `app/learner-text-baseline.txt` -- allowed `strings.xml` resource names.
- `app/.../ui/shell/LearnScreen.kt` -- content-key copy, mastery line rule.
- `app/.../ui/lesson/LessonScreen.kt` -- `twoLookStages` param, milestone from Lesson or practice, content-key result line.
- `app/.../ui/shell/AppShell.kt` -- passes `twoLookStages`, 2-look Lesson practice opens LL practice, `recognitionDiagramFor`.
- `build-logic/.../policy/LearnerTextPolicy.kt`, `tasks/CheckLearnerTextTask.kt` -- string-resource baseline rule over `values*/*.xml` in main/debug/release res.
- `core/application/.../session/TRANSITIONS.md` -- `practiceEntry` field and restore.
- Tests: TwoLookMasteryTest, TwoLookContentTest, ShippedContentTest, LessonScreenTest, AppShellTest, AppShellLibraryTest, new RecognitionDiagramChoiceTest, LearnerTextPolicyTest, new CheckLearnerTextTaskTest.

**Review:** 16 findings (high 0, medium 0, low 15, false 1). Patches applied: 8 rows in 4 entries (all low) -- gate scans all values XML in main/debug/release res; `string-array` checked; shell test for the 2-look Lesson milestone practice route; Learn-row test for solves-then-completion → *Mastered*. Deferred: none. Rejected: 8 -- missing-baseline silent off (needed for `:core:*`, unlikely); CDATA false positive (none in project, fails loudly); explanatory copy for 2 of 2 (AC prescribes display); TestKit task test (real `register()` exercised, full build ran the task); AC1 split across screens (each covered); AC3 overload (AC asks this pin); recognition "contains" lookup (false: equality pins first); name-only baseline (design choice).

**Follow-up review recommended:** false -- patched counts: high 0, medium 0, low 4 entries.

**Verification:** Codex implementation run: `spotlessApply spotlessCheck build` and `-p build-logic test` green (512 app, 539 domain, 109 build-logic tests). After review patches: targeted AppShellTest (43) and AppShellLibraryTest (17) green; `./gradlew spotlessApply spotlessCheck build` BUILD SUCCESSFUL and `./gradlew -p build-logic test` BUILD SUCCESSFUL.

**Residual risks:** When the last 2-look Lesson triggers mastery, the milestone overlay covers the optional *Add to Learning* offer (Design Notes). LL practice scramble pool (all 78 Algorithms) was left as built and has no new test.
