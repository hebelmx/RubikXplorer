---
title: 'Browse and filter the Library'
type: 'feature'
created: '2026-11-09'
status: 'done'
baseline_revision: '26bbf0fcbdaa48e5587fe71cbaea76e0ff3147e9'
review_loop_iteration: 0
followup_review_recommended: false
context: []
warnings: ['oversized']
deferred: []
---

<intent-contract>

## Intent

**Problem:** The 78 verified Algorithms and their tiles exist, but no screen shows them, so a Learner cannot browse them or narrow them to a family or to their own Algorithm Status (FR-18).

**Approach:** Add Library as the third of four tabs, with a screen that groups the 78 tiles under OLL and PLL family headings in a grid, above neutral filter chips for family and Algorithm Status. Grouping and filtering are a pure function in `:core:domain`; an empty result shows the specified copy and a link to 2-look on the Learn tab.

## Boundaries & Constraints

**Always:** One tile per Case, using its primary Algorithm. Grid is 4 columns at the expanded window class, 3 at medium and 2 at compact, keyed on the window width class and not on the content width beside the rail. Filter chips are M3 filter chips: a selected chip uses `surface-raised` with a ✓, never violet; one family and one status can be selected at once, tapping the selected chip clears it, and the two combine with AND. An Algorithm with no entry in the status map is *Not started*. Every filter that matches nothing shows exactly "No algorithms marked Learning yet." and the link. Filter selection and grid position are hoisted to the shell so tab switches and width-class changes keep them. New Learner-facing text is a content string or Android resource.

**Never:** No tap action on a tile and no detail screen (9.5). No status label on the tile, no `SetAlgorithmStatus`, Room table, `Flow` or status control (9.6): the shell passes no statuses yet, so every Algorithm reads as *Not started*. No 2-look Lesson, and no change to when *Faster last layer* appears (9.7). No *Recognize case* entry (v1.1). No stars, scores, badges or Learned count. No `SessionEvent`: the Library is not a session (AD-20). No writes under `_bmad-output/planning-artifacts/`, `_bmad/` or `.claude/`.

## I/O & Edge-Case Matrix

| Scenario | Input / State | Expected Output / Behavior | Error Handling |
|----------|--------------|---------------------------|----------------|
| Shipped bundle, no filter | Library tab opened | 78 tiles under family headings, OLL families before PLL, each family once in first-appearance order, Cases in content order | No error expected |
| Family filter | `oll-dot` selected | Only that family's 8 tiles under one heading | No error expected |
| Status filter | One Algorithm *Learning*, *Learning* selected | That one tile under its family heading | No error expected |
| Default status | Empty status map, *Not started* selected | All 78 tiles | No error expected |
| Combined filters | Family and status both selected | Tiles meeting both | No error expected |
| Nothing matches | *Learning* selected, empty status map | The empty copy and link; no grid | No error expected |
| Nothing matches, other filter | *Learned* selected, or family + status with no match | The same copy and link | No error expected |
| Link tapped, 2-look unlocked | Course finished | Learn tab, Stage list, *Faster last layer* row in view | No error expected |
| Link tapped, 2-look locked | Course not finished | Learn tab, Stage list | No error expected |
| Clear | Selected chip tapped again | That filter is cleared | No error expected |
| Content failed | `CatalogState.Failed` | "The Library could not be loaded." | Sealed state |
| Case without a primary | Bundle fails the join | That Case is left out (the validator rejects such a bundle) | No crash |

</intent-contract>

## Code Map

- `app/src/main/kotlin/dev/cubentia/app/ui/shell/AppShell.kt:64-72` -- `Tab` enum; `:138` `learnScroll` hoisting pattern; `:356-378` `destination` lambda and `allStagesFinished` (move above the lambda); `:390` `BoxWithConstraints` where the window class is computed; `BackHandler` already returns from any non-Learn tab.
- `app/src/main/kotlin/dev/cubentia/app/ui/shell/WindowWidthClass.kt` -- `of(widthDp)`, breakpoints 600 and 840.
- `app/src/main/kotlin/dev/cubentia/app/ui/shell/LearnScreen.kt:100-106` -- Loading draws nothing, Failed shows a message; `:240` the *Faster last layer* row is last in the scrolling list, so scrolling to the end brings it into view.
- `app/src/main/kotlin/dev/cubentia/app/ui/library/AlgorithmTile.kt` -- `AlgorithmTile(case, algorithm, strings, modifier, ...)`; its diagram needs a tile at least 124dp wide (2 columns at 320dp gives 140dp).
- `app/src/main/kotlin/dev/cubentia/app/content/ContentStrings.kt` -- `resolve(Text)`.
- `app/src/main/kotlin/dev/cubentia/app/content/LessonCatalog.kt` -- `CatalogState.Ready.bundle`, `.strings`.
- `core/domain/src/main/kotlin/dev/cubentia/domain/content/Model.kt:130-175` -- `Case`, `CaseSet`, `Algorithm.isPrimary`, `ContentBundle.cases/algorithms`.
- `core/domain/src/main/kotlin/dev/cubentia/domain/content/Ids.kt` -- `FamilyId`, `AlgorithmId`, `CaseId`.
- `core/domain/src/main/resources/content/strings/en.json:451` -- `msg.library.*`; `ShippedContentTest` exempts only `msg.` and `btn.` keys from the content-use check.
- `core/domain/src/main/resources/content/algorithms/{oll,pll}.json` -- 18 family ids.
- `app/src/test/kotlin/dev/cubentia/app/ui/shell/AppShellTest.kt:179` -- `launch` and shell test harness; `AppShellSkipCheckTest.kt:263` reaches an unlocked *Faster last layer*.
- `app/src/test/kotlin/dev/cubentia/app/ui/library/AlgorithmTileTest.kt` -- shipped-bundle Robolectric setup.

## Tasks & Acceptance

**Execution:**
- `core/domain/src/main/kotlin/dev/cubentia/domain/content/AlgorithmLibrary.kt` -- add `AlgorithmStatus` (`NOT_STARTED`, `LEARNING`, `LEARNED`), `LibraryFilter(family, status)`, `LibrarySection(set, family, entries)`, `LibraryEntry(case, algorithm)`, `AlgorithmLibrary.families(cases)` and `AlgorithmLibrary.sections(cases, algorithms, filter, statuses)` -- pure grouping and filtering under the Kover gate.
- `core/domain/src/test/kotlin/dev/cubentia/domain/content/AlgorithmLibraryTest.kt` -- cover the domain rows of the I/O matrix against the shipped bundle and small fixtures -- proves grouping and filters without UI.
- `core/domain/src/main/resources/content/strings/en.json` -- add `msg.library.family.<id>` for all 18 families, `msg.library.family.heading` (`{set} · {family}`), `msg.library.status.not-started|learning|learned`, `msg.library.set.oll|pll`, `msg.library.filter-empty` with the exact copy, and `btn.library.open-two-look` ("Go to 2-look") -- Learner text as content.
- `app/src/main/res/drawable/ic_library.xml` -- add a 24dp Material icon, tinted at the call site like `ic_learn.xml` -- the tab icon.
- `app/src/main/res/values/strings.xml` -- add `nav_library` and `library_error` -- tab label and load failure.
- `app/src/main/kotlin/dev/cubentia/app/ui/library/LibraryScreen.kt` -- add `LibraryScreen(catalog, widthClass, filter, onFilterChange, gridState, onOpenTwoLook, modifier, statuses = emptyMap())`, `LibraryFilterSaver`: title, a scrolling family chip row, a status chip row, then `LazyVerticalGrid` with full-span heading items (semantics `heading`) and `AlgorithmTile`s, or the empty copy and a `TextButton` link -- the Library surface.
- `app/src/main/kotlin/dev/cubentia/app/ui/shell/AppShell.kt` -- add `LIBRARY` between `SOLVE` and `YOU`, hoist the filter (`rememberSaveable`) and `LazyGridState`, pass the window class into `destination`, and wire the link to the Learn tab with `selectedStage` cleared and, when `allStagesFinished`, `learnScroll` scrolled to its end -- reachable tab and working link.
- `app/src/test/kotlin/dev/cubentia/app/ui/library/LibraryScreenTest.kt` -- Robolectric tests of the screen rows of the I/O matrix: all 78 reached by scrolling, headings, chips selected with ✓ on `surface-raised`, empty copy, link callback, failure -- the observable surface.
- `app/src/test/kotlin/dev/cubentia/app/ui/shell/AppShellLibraryTest.kt` -- through `AppShell`: the four-tab bar, 4 columns at `w1000dp` beside the rail, 2 at phone width, filter kept across a tab switch, the link in both locked and unlocked states -- proves the wiring and the window-class keying.

**Acceptance Criteria:**
- Given the Library tab, when it opens, then 78 Algorithm tiles show grouped by OLL and PLL family, in 4 columns at expanded width.
- Given neutral filter chips for family and Algorithm Status, when a filter matches nothing, then "No algorithms marked Learning yet." shows with a link to 2-look.

## Spec Change Log

## Review Triage Log

### 2026-11-09 — Review pass
- verdicts: 15 findings — high 0, medium 0, low 13, false 2, maybe-false 0
- findings:
  - `[low]` `[reject]` Blind: the title and chip rows stay outside the scrolling grid and take height in short or large-text windows — at 411dp height one full tile row is still visible and the grid scrolls; moving the controls into the grid would put filters out of reach after scrolling, which is a design trade, not a direct correction.
  - `[low]` `[patch]` Blind: the family chip row resets its position on a tab switch and can hide the selected chip — hoisted as `familyChipsScroll` beside `learnScroll`, pinned in `AppShellLibraryTest`.
  - `[low]` `[patch]` Blind: the Library title has no heading semantics — added, pinned in `LibraryScreenTest`.
  - `[low]` `[patch]` Blind: the selected check is spoken as text — replaced with the decorative `ic_check` icon and removed the `library_selected_check` string.
  - `[low]` `[patch]` Blind: no medium-width column test through the shell — added `medium window uses three columns beside rail`.
  - `[low]` `[patch]` Blind: restoration is only tested on the bare screen — added `tab and family filter survive state restoration` through `AppShell`.
  - `[low]` `[patch]` Blind: no populated combined-filter test on the screen — added `family and status filters constrain the visible tiles together`.
  - `[low]` `[patch]` Verification gap: family-chip tests never check the grid's contents — covered by the same combined-filter test.
  - `[low]` `[patch]` Verification gap: the three-column medium layout has no assertion — same fix as the Blind medium row.
  - `[low]` `[patch]` Intent: AC 1's parts are proven on separate surfaces — the expanded shell test now also reaches all 78 tiles beside the rail.
  - `[low]` `[patch]` Intent: the AC 2 copy is not observed through the tab — the shell test now asserts the exact string.
  - `[low]` `[reject]` Intent: the link does not enter 2-look, and the row is absent while locked — Story 9.7 owns the rows' unlock behaviour and content; until then the Learn tab is the only 2-look surface.
  - `[low]` `[reject]` Intent: a real status filter is not observable at the app surface — Story 9.6 owns persistence and is listed after this one.
  - `[false]` `[reject]` Intent: *Learned* showing the Learning copy — the AC and EXPERIENCE.md give that one string for any filter that matches nothing.
  - `[false]` `[reject]` Intent: tests assert the spec's readings rather than discriminating between them — that is what conformance to a resolved reading means; no defect is named at a location.

## Design Notes

**One empty copy.** The AC and the EXPERIENCE.md State Patterns row give a single string for every empty filter, so *Learned* with no matches also says "Learning". No other copy is specified, so none is invented here.

**Family means the 18 families.** FR-18 lists dot, line, L-shape, edges-only and so on. The mockup's chip row shows All, OLL and PLL, but DESIGN.md says the document wins over a mockup, and the AC names family and status. Deselecting a chip is how a Learner returns to all.

**Status source.** Story 9.6 owns persistence and will pass its `Flow` value as `statuses`. Until then the *Learning* and *Learned* chips always reach the empty state, which is the AC's scenario.

**Link target.** 2-look lives on the Learn tab as the *Faster last layer* row (EXPERIENCE.md Information Architecture). The row is gated by Course completion and that gate stays, so a Learner who has not finished the Course lands on the Stage list.

## Verification

**Commands:**
- `export JAVA_HOME=/usr/lib/jvm/java-25-openjdk-amd64 ANDROID_HOME=$HOME/Android/Sdk; ./gradlew spotlessCheck build` -- expected: BUILD SUCCESSFUL, including `AlgorithmLibraryTest`, `LibraryScreenTest`, `AppShellLibraryTest` and the Learner-text and content checks.

## Auto Run Result

**Summary.** Library is now the third of four tabs. It shows the 78 tiles under `OLL · Family` headings in 2, 3 or 4 columns by window class, with single-select chips for 18 families and 3 Algorithm Statuses. Any filter that matches nothing shows the specified copy and a link to the Learn tab, scrolling to *Faster last layer* when it is unlocked.

**Files changed.**
- `core/domain/src/main/kotlin/dev/cubentia/domain/content/AlgorithmLibrary.kt` -- `AlgorithmStatus`, `LibraryFilter`, sections and families.
- `core/domain/src/test/kotlin/dev/cubentia/domain/content/AlgorithmLibraryTest.kt` -- grouping, filters, missing primary.
- `core/domain/src/main/resources/content/strings/en.json` -- family, status, heading and empty-state text.
- `app/src/main/kotlin/dev/cubentia/app/ui/library/LibraryScreen.kt` -- the screen and filter saver.
- `app/src/main/kotlin/dev/cubentia/app/ui/shell/AppShell.kt` -- the tab, hoisted filter and scroll state, and the link.
- `app/src/main/res/drawable/ic_library.xml` -- tab icon.
- `app/src/main/res/values/strings.xml` -- tab label and load failure.
- `app/src/test/kotlin/dev/cubentia/app/ui/library/LibraryScreenTest.kt` -- screen behavior.
- `app/src/test/kotlin/dev/cubentia/app/ui/shell/AppShellLibraryTest.kt` -- tab, columns, restoration and link through the shell.

**Review.** 15 findings. Patched: 10 rows, all low. Deferred: none. Rejected: 5 (low 3, false 2), each with its reason in the Review Triage Log. Reading the diff also found two defects no layer reported, fixed before verification: tiles did not fill their grid cells, and screen text had no ink color, which renders black in the dark theme beside the rail.

**Follow-up review: not recommended.** Patched by verdict: high 0, medium 0, low 10 rows.

**Verification.** `./gradlew spotlessCheck build` was BUILD SUCCESSFUL after the patches. `LibraryScreenTest` 8 and `AppShellLibraryTest` 6 passed with no failures or skips. All 12 matrix rows have a covering test that ran. No instrumented or device runs, so TalkBack and the drawn appearance are unverified.

**Residual risks.** Until Story 9.6 ships, *Learning* and *Learned* always reach the empty state and *Not started* shows all 78. Until Story 9.7 ships, tapping *Faster last layer* does nothing. *Learned* with no matches reads "No algorithms marked Learning yet.", as specified.
