---
title: 'One source for physical copy; skip-check needs the virtual cube'
type: 'bugfix'
created: '2026-11-09'
status: 'done'
baseline_revision: '36c1ffc526370d32bb1f73224eb01aecea6e5f5f'
review_loop_iteration: 0
followup_review_recommended: false
context: []
warnings: []
deferred:
  - summary: >-
      docs/ARCHITECTURE.md still describes camera checking with no v1.0/v1.1 qualifier.
    evidence: |-
      Line 3 says the app "checks their real cube with the camera", while v1.0 ships camera-free and the limitation paragraph added here says so. Pre-existing text, not caused by this story.
    location: >-
      docs/ARCHITECTURE.md:3
    severity: low
---

<intent-contract>

## Intent

**Problem:** Physical-mode copy lives in two places: the mismatch ladder and the physical controls render `R.string.physical_*` while the matching `msg.physical.mismatch.*` keys in `en.json` go unused, and dead duplicates sit in `strings.xml`. Separately, the Physical mode switch is available on the skip-ahead check, so a Stage can be skipped on the Learner's word alone (Epic 8 retro items 6 and 7).

**Approach:** Resolve all physical-mode copy from `en.json` content keys through `ContentStrings`, delete the `strings.xml` entries this leaves unused, and hide the Physical mode switch while the skip-ahead check is open. Record the self-reported nature of Independent practice as an accepted v1.0 limitation.

## Boundaries & Constraints

**Always:** Reuse the existing `msg.physical.*` and `btn.physical.*` keys and add a new key only where none exists, with the wording unchanged. Learner-visible text stays byte-for-byte the same. The physical-copy resolution goes through `Text(key, args)` so the `ShippedContentTest` call scan covers it. Hide the switch from the UI for `isSkipCheck` only.

**Never:** Change `Reduce.kt`, `SessionEvent` or `TRANSITIONS.md`. Hide the switch in Independent practice, Lessons or Solution playback. Move non-physical `strings.xml` copy (Certainty words, `lesson_*`, `mode_*`, `skip_check_instruction`): the rest of `epic-2-retro-item-3` stays open. Edit planning artifacts.

## I/O & Edge-Case Matrix

| Scenario | Input / State | Expected Output / Behavior | Error Handling |
|----------|--------------|---------------------------|----------------|
| Ladder copy | `PHYSICAL_MISMATCH`, each rung | Question, corner, next-question and re-enter text resolve from `en.json`, unchanged wording | A missing key shows the key (`ContentStrings`); the content test prevents it |
| Candidate line | Physical, Diagnosis candidate | `Candidate: {candidate}` resolved with the argument | No error expected |
| Skip-ahead check open | `isSkipCheck = true` | `lesson:physicalMode` and its switch do not exist | No error expected |
| Independent practice | `isPractice = true` | `lesson:physicalModeSwitch` exists and still switches to physical | No error expected |
| Lesson | neither flag | Switch shown as before | No error expected |

</intent-contract>

## Code Map

- `core/domain/src/main/resources/content/strings/en.json` -- l.34-44 physical keys; add the missing ones beside them.
- `app/src/main/kotlin/dev/cubentia/app/ui/lesson/MismatchLadder.kt` -- every `R.string.physical_mismatch_*` (l.65-162) and `confirm_cube_enter` (l.181); drop `matchesAfterAllLabel` for a `strings: ContentStrings` parameter.
- `app/src/main/kotlin/dev/cubentia/app/ui/lesson/LessonPanel.kt` -- `physical_*` at l.189-274; `matchesAfterAllLabel` l.134, l.180; only production caller is `LessonScreen` l.548, no test calls it directly.
- `app/src/main/kotlin/dev/cubentia/app/ui/lesson/ConfirmCubeCard.kt` -- `confirm_*` at l.50, 69, 87; only rendered in physical mode.
- `app/src/main/kotlin/dev/cubentia/app/ui/lesson/LessonScreen.kt` -- `LessonTopBar` call l.~265 and body l.~385-430 (`physical_mode`, switch); `matchesAfterAllLabel` l.615; `isSkipCheck` already a parameter (l.157).
- `app/src/main/kotlin/dev/cubentia/app/ui/solve/SolutionPlayback.kt` -- `physical_done` l.351, `physical_mode` l.368; `strings` already in scope.
- `app/src/main/res/values/strings.xml` -- l.42-63 `physical_*`, `confirm_*`, and unreferenced `playback_follow_on_my_cube`, `playback_cube_should_be_solved` (l.83-84).
- `core/application/.../usecase/StagePractice.kt` -- read-only evidence: skip-check Lesson ids are `skip-check-*`, practice ids `practice-*`, so a physical Practice session can never be resumed as a check; `Reduce.startPractice` l.319 always begins `VIRTUAL`.
- `docs/ARCHITECTURE.md` -- "Physical-cube synchronization" l.98-113, where the limitation is recorded.
- Tests: `app/src/test/.../ui/shell/AppShellSkipCheckTest.kt` l.187, `app/src/test/.../ui/lesson/PracticeScreenTest.kt` l.56, `core/domain/src/test/.../content/ShippedContentTest.kt` (`androidStrings` helper). `PhysicalLessonScreenTest` already asserts ladder text through the shipped bundle.

## Tasks & Acceptance

**Execution:**
- `core/domain/src/main/resources/content/strings/en.json` -- add keys for the Physical mode label, Done, I did something else, More options, the two overflow items, next question, five corners and the three Confirm-your-cube labels -- one source.
- `app/.../ui/lesson/MismatchLadder.kt`, `ConfirmCubeCard.kt`, `LessonPanel.kt`, `LessonScreen.kt`, `app/.../ui/solve/SolutionPlayback.kt` -- resolve from `ContentStrings` -- AC 1.
- `app/.../ui/lesson/LessonScreen.kt` -- `LessonTopBar` omits the Physical mode row when `isSkipCheck` -- AC 2.
- `app/src/main/res/values/strings.xml` -- delete the entries now unused plus the two `playback_*` dead duplicates -- AC 1.
- `docs/ARCHITECTURE.md` -- record the Independent practice limitation -- AC 2.
- `core/domain/src/test/.../content/ShippedContentTest.kt` -- guard: no `physical_*` entry returns to `strings.xml`, and every migrated label exists in `en.json` -- this regressed twice (`epic-2-retro-item-3`).
- `app/src/test/.../ui/shell/AppShellSkipCheckTest.kt`, `PracticeScreenTest.kt` -- switch absent on the course and Stage check; present in practice and it reaches physical.

**Acceptance Criteria:**
- Given the mismatch ladder and other physical-mode copy, when it is rendered, then the text resolves from `en.json` content keys, and the unreferenced `strings.xml` duplicates are deleted.
- Given the skip-ahead check, when it is open, then the Physical mode switch is hidden, so the check can be passed only on the virtual cube.
- Given Independent practice, when it is open, then Physical mode is available, and its self-reported nature is recorded in `docs/ARCHITECTURE.md` as an accepted v1.0 limitation.

## Spec Change Log

## Review Triage Log

### 2026-11-09 — Review pass
- verdicts: 23 findings — high 0, medium 3, low 14, false 6, maybe-false 0
- findings:
  - `[low]` `[patch]` Intent: `frame_expected` is drawn only in physical mode and stayed in `strings.xml` — fixed: `FrameLabel` resolves `msg.physical.frame-expected`; `strings` threaded through `DemonstrationPlayer`; the resource is deleted.
  - `[low]` `[patch]` Intent: the `strings.xml` guard covers only the `physical_` prefix — fixed: it also rejects `confirm_*` and the named retired resources.
  - `[low]` `[patch]` Intent: several migrated labels and the candidate line have no rendered-text assertion — fixed: assertions added.
  - `[false]` `[reject]` Intent: "passed only on the virtual cube" is not enforced at the pass seam — the switch was the only Lesson-side `ModeSwitched` dispatcher, `startPractice` always begins `VIRTUAL`, `skip-check-*` and `practice-*` ids differ, and no installed users hold an older snapshot, so no path reaches the seam in physical state.
  - `[low]` `[reject]` Intent: the practice test mounts `LessonScreen` directly rather than through `AppShell` — `AppShell` passes `isPractice = true` and the row keys only on `isSkipCheck`; a shell test adds setup for no reachable difference.
  - `[false]` `[reject]` Intent: the limitation lives only in an architecture doc — the AC names no location and "recorded" is met.
  - `[false]` `[reject]` Intent: `Text(text = ...)` at non-physical sites is incidental — it is the blank-label fix for those sites, not churn.
  - `[low]` `[patch]` Verification: six migrated labels have no assertion on rendered text — fixed: "It matches", "Enter it" (card and rung 3), "Physical mode" (top bar and playback), "More options" description.
  - `[medium]` `[patch]` Verification: the candidate line's only check is negative — fixed: positive `Candidate: <expected>` derived from the session's diagnosis; this also completes the matrix row.
  - `[low]` `[patch]` Verification: the guard misses the `confirm_*` and `playback_*` names — same fix as the Intent guard row.
  - `[low]` `[patch]` Blind: the `strings.xml` guard covers one prefix — same fix as the Intent guard row.
  - `[low]` `[patch]` Blind: `PHYSICAL_LABELS` omits keys its KDoc covers — fixed: `back-to-virtual`, `objective-met`, the two `msg.playback.*` keys and `frame-expected` added.
  - `[false]` `[reject]` Blind: a restored physical skip-check passes on the Learner's word — as the Intent pass-seam row: no such state is reachable, and a guard for it would protect a state never shown.
  - `[medium]` `[patch]` Blind: importing both `Text` classes lets a bare `Text(someString)` compile and draw nothing — real (it blanked a shipped playback button); fixed: the five UI files alias the domain class as `ContentText`, and the `ContentChecks` scan matches it (also covering `ResetConfirmation`, no problems reported).
  - `[low]` `[reject]` Blind: the blank-label fix is not recorded in the spec — the fix would edit this build's spec; it is stated under Auto Run Result.
  - `[low]` `[patch]` Blind: several labels have no rendered-text assertion — same fix as the Verification labels row.
  - `[medium]` `[patch]` Blind: the candidate line has no positive test — same fix as the Verification candidate row.
  - `[low]` `[defer]` Blind: `docs/ARCHITECTURE.md` still says the app checks the cube with the camera with no version qualifier — pre-existing tagline and §8 text; deferred.
  - `[low]` `[patch]` Blind: "it unlocks Stages" does not distinguish the check, since a physical Lesson also unlocks them — fixed: "it skips whole Stages without teaching them".
  - `[false]` `[reject]` Blind: new button labels use the `msg.` prefix — `ContentStrings` ignores prefixes, `msg.physical.mismatch.matches-after-all` is an existing button key, and the AC names `msg.physical.mismatch.*`; no harm named.
  - `[false]` `[reject]` Blind: the guard copies file discovery and parses its label — the `app_name` assertion fails loudly if the label format changes, and no diverging caller is named.
  - `[low]` `[patch]` Edge: re-added `confirm_*` or `playback_*` names pass the guard — same fix as the Intent guard row; `strings.xml` is the only values file.
  - `[low]` `[patch]` Edge claim: the EXPECTED badge and Next still come from `strings.xml` — EXPECTED fixed as the Intent frame row; `lesson_next` is also drawn in virtual mode, so it stays with `epic-2-retro-item-3`.

## Design Notes

"Other physical-mode copy" is read as every label drawn only in physical mode, including the Confirm-your-cube card. `confirm_cube_enter` also serves rung 3, so it moves with the rest. Copy shared with virtual mode (Certainty words, `lesson_next`) is out of scope and stays with `epic-2-retro-item-3`.

## Verification

**Commands:**
- `export JAVA_HOME=/usr/lib/jvm/java-25-openjdk-amd64 ANDROID_HOME=$HOME/Android/Sdk; ./gradlew spotlessCheck build` -- expected: BUILD SUCCESSFUL.

## Auto Run Result

Status: done

**Summary:** All physical-mode copy now resolves from `en.json` content keys, the `strings.xml` duplicates are gone, the Physical mode switch is hidden on the skip-ahead check only, and the self-reported nature of Independent practice is recorded as an accepted v1.0 limitation. One Learner-visible change beyond the "byte-for-byte" boundary: the playback *I'm following on my cube* button rendered blank before this story, because a bare `Text(someString)` bound to the domain class, and it now shows its label.

**Files changed:**
- `core/domain/src/main/resources/content/strings/en.json` -- 16 new physical keys, wording unchanged.
- `app/src/main/res/values/strings.xml` -- 25 entries deleted.
- `app/.../ui/lesson/MismatchLadder.kt`, `ConfirmCubeCard.kt`, `LessonPanel.kt`, `LessonScreen.kt`, `DemonstrationPlayer.kt`, `app/.../ui/solve/SolutionPlayback.kt` -- resolve from `ContentStrings`, domain `Text` aliased as `ContentText`; `LessonTopBar` omits the switch when `isSkipCheck`.
- `core/domain/src/test/.../content/ContentChecks.kt`, `ShippedContentTest.kt` -- scan covers `ContentText(`; guard against migrated resources returning, exact wording pinned.
- `app/src/test/.../AppShellSkipCheckTest.kt`, `PracticeScreenTest.kt`, `PhysicalLessonScreenTest.kt`, `SolutionPlaybackTest.kt`, `FrameLabelTest.kt`, `DemonstrationPlayerTest.kt` -- switch absent on both checks, kept in practice, rendered-text assertions.
- `docs/ARCHITECTURE.md` -- accepted v1.0 limitation.

**Review findings:** 23 total. Patched 15 findings in 7 entries. Deferred 1 (camera wording in `docs/ARCHITECTURE.md`). Rejected 7, each with its reason in the triage log: pass seam (twice, unreachable), practice route through `AppShell`, limitation location, `Text(text = ...)` as churn, spec note for the blank-label fix, key prefixes, guard duplication.

**Follow-up review:** `false`. Patched entries by verdict: high 0, medium 2, low 5. No specific unverified risk can be named: the alias is compiler-checked, the scan extension ran clean, and the candidate change is test-only.

**Verification:** `./gradlew spotlessCheck build` -- BUILD SUCCESSFUL after all patches; 854 JVM and Robolectric tests across `:app` and `:core:domain`, 0 failures, 0 skipped. Every I/O matrix row is covered by a test that ran: `PhysicalLessonScreenTest` (ladder, candidate, Lesson switch), `AppShellSkipCheckTest` (course and Stage check), `PracticeScreenTest` (practice). No emulator or device run.

**Residual risks:** Earlier runs in this story hit a Spotless "Could not read path" race under `app/build/` and one `Cube3DEmphasisTest` failure that passed 5 repeats, cause not found; neither recurred in the final run. The skip-check rule holds because the switch is hidden and no other path reaches physical state, not because the pass itself checks the mode. The rest of `epic-2-retro-item-3` stays open.
