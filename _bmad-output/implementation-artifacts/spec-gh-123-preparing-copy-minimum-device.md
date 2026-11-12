---
title: 'GH #123: Solve tab preparing copy true on the new minimum device (Lenovo)'
type: 'bugfix'
created: '2026-11-12'
status: 'done'
baseline_revision: '41ee3ff4484544610d716aaca9a64281a7c71bf5'
review_loop_iteration: 0
followup_review_recommended: false
context: []
warnings: []
deferred:
  - summary: >-
      The #104 Known issues bullet in docs/pruning-tables.md still describes the original 3.8 copy "Preparing the solver (one time, a few seconds)." in the present tense ("therefore overstates").
    evidence: |-
      Pre-existing since Story 3.8; not the "old text" the #123 intent names. The bullet is followed by the #112 and #123 reword notes, so the current copy is still stated, but the lead sentences read as if the 3.8 copy were current.
    location: >-
      docs/pruning-tables.md:175-176
    severity: low
---

<intent-contract>

## Intent

**Problem:** On the new minimum reference device (Lenovo Tab M10 Plus 3rd Gen, SCP 2026-11-12) solver preparation takes 12.6 s debug / up to 10.5 s release, so the shipped `msg.solve.preparing` copy ("Preparing the solver. This can take a few seconds the first time after the app starts.") understates the wait.

**Approach:** Replace only the string value with the PO-approved copy A from SCP 2026-11-12 §4.3, update the tests that pin the old text, and annotate the `docs/pruning-tables.md` Known issues bullet.

## Boundaries & Constraints

**Always:** New value is exactly `Preparing the solver. The first solve after the app starts takes longer. Later solves are faster.` Keep the key `msg.solve.preparing`, the `solve:preparing` tag, Cancel, and two-state preparation unchanged. Existing dated history (the #112 sentence in pruning-tables.md, `docs/benchmarks.md` Lenovo run notes, `docs/evidence/**` logs, `_bmad-output/**` specs and planning history) stays as is.

**Never:** Change `msg.solve.preparing-first-use` or any other string. Change any Kotlin logic. Edit anything under `_bmad-output/planning-artifacts`, `_bmad/`, or `.claude/skills/`. Push or open PRs.

</intent-contract>

## Code Map

- `core/domain/src/main/resources/content/strings/en.json:3` -- `msg.solve.preparing` value to replace (only locale file).
- `app/src/test/kotlin/dev/cubentia/app/ui/shell/AppShellSolveTest.kt:229,278` -- two `assertTextEquals` calls on `solve:preparing` pinning the old text (`leavingSolveCancelsARunningSolve`, `lessonIsUntouchedDuringPreparationAndReturnsOnClose`).
- `docs/pruning-tables.md:180` -- Known issues (#104) bullet; holds the #112 reword note (keep it as history) and receives the new note.
- `docs/benchmarks.md:513` -- read-only: dated Lenovo evidence quoting the #112 copy as what was shown at the time.
- `_bmad-output/planning-artifacts/epics.md:853`, `.../ux-designs/ux-RubikXplorer-2026-10-29/EXPERIENCE.md:130` -- read-only: already quote the target text.

## Tasks & Acceptance

**Execution:**
- `core/domain/src/main/resources/content/strings/en.json` -- set `msg.solve.preparing` to the new exact text -- shipped copy must match Story 3.7 AC.
- `app/src/test/kotlin/dev/cubentia/app/ui/shell/AppShellSolveTest.kt` -- replace both old-text assertions with the new exact text -- tests must track the shipped copy.
- `docs/pruning-tables.md` -- after the #112 sentence in the #104 Known issues bullet, append: "Reworded again by #123 (SCP 2026-11-12, Lenovo Tab M10 Plus 3rd Gen as minimum reference): `msg.solve.preparing` now reads "Preparing the solver. The first solve after the app starts takes longer. Later solves are faster."; `msg.solve.preparing-first-use` is unchanged." -- record the reword.

**Acceptance Criteria:**
- Given the Solve tab with an unready solver whose tables are read (not generated), when the Learner accepts a cube, then the `solve:preparing` node shows exactly "Preparing the solver. The first solve after the app starts takes longer. Later solves are faster."
- Given first-use generation, when preparation shows, then the text is still "Setting up the solver for first use. This can take a little while."
- Given the repo, when grepping for "This can take a few seconds the first time", then matches appear only in `docs/evidence/**`, `docs/benchmarks.md` (dated Lenovo run), the dated #112 sentence in `docs/pruning-tables.md`, and `_bmad-output/**`.

## Verification

**Commands:**
- `./gradlew spotlessCheck build` -- expected: BUILD SUCCESSFUL.
- `grep -rn "This can take a few seconds the first time" --exclude-dir=.git --exclude-dir=build --exclude-dir=_bmad-output --exclude-dir=_bmad .` -- expected: only `docs/evidence/**`, `docs/benchmarks.md:513`, `docs/pruning-tables.md` (#112 history sentence).

## Spec Change Log

## Review Triage Log

### 2026-11-12 — Review pass
- verdicts: 12 findings — high 0, medium 0, low 3, false 9, maybe-false 0
- findings:
  - `[false]` `[reject]` (blind-hunter) New spec file has mode 100755 — `core.fileMode` is false in this WSL checkout, so git records 100644 (as for every other spec, e.g. spec-gh-112).
  - `[false]` `[reject]` (blind-hunter) Spec Verification section lists only expected results — the fix edits this build's spec; actual results are recorded under Auto Run Result.
  - `[low]` `[reject]` (blind-hunter) Longer copy has no compact-screen/large-font layout check — the text is an unconstrained wrapping `Text` in the Solving column (`SolveScreen.kt:70`), 11 characters longer than before; clipping is unlikely and the fix is a device test, not a direct correction.
  - (edge-case-hunter) no findings.
  - (verification-gap) no findings.
  - `[low]` `[patch]` (intent-alignment) The #112 sentence in `docs/pruning-tables.md:180` still says `msg.solve.preparing` "now reads" the old text — changed "now reads" to "then read" in the #112 sentence so only the #123 sentence states the current copy.
  - `[false]` `[reject]` (intent-alignment) `docs/benchmarks.md:513` quotes the old copy — it is the dated 2026-11-12 Lenovo run note recording what was shown at the time (dated evidence).
  - `[low]` `[defer]` (intent-alignment) `docs/pruning-tables.md:175-176` describe the original 3.8 copy in the present tense — pre-existing and not the old text the intent names; deferred.
  - `[false]` `[reject]` (intent-alignment) No string-level or planning-parity test — the intent does not require one; the rendered text is pinned by both AppShellSolveTest assertions, and the value was checked equal to epics.md:853 and EXPERIENCE.md:130.
  - `[false]` `[reject]` (intent-alignment) Build gate not shown in diff — `./gradlew spotlessCheck build` ran locally with exit 0 (before and after the patch).
  - `[false]` `[reject]` (intent-alignment) Untracked spec file exceeds "nothing more" — it is the build workflow's own record, not product scope.
  - `[false]` `[reject]` (intent-alignment) `ScreenAudit.kt:75` mentions "Preparing the solver" — a generic comment that pins no text; no reading requires a change.
  - `[false]` `[reject]` (intent-alignment) SCP 2026-11-12 `:165` still says "pending PO approval" and SCP §5 says no test changes expected — planning history, out of bounds to edit; the issue text governs and AppShellSolveTest did pin the old text.

## Auto Run Result

Status: done

**Summary:** `msg.solve.preparing` now ships the PO-approved copy A from SCP 2026-11-12 §4.3, matching Story 3.7 AC (`epics.md:853`) and `EXPERIENCE.md:130`. The two AppShellSolveTest assertions follow it, and the #104 Known issues bullet in `docs/pruning-tables.md` records the #123 reword.

**Files changed:**
- `core/domain/src/main/resources/content/strings/en.json` -- new `msg.solve.preparing` value; `msg.solve.preparing-first-use` unchanged.
- `app/src/test/kotlin/dev/cubentia/app/ui/shell/AppShellSolveTest.kt` -- both `solve:preparing` assertions expect the new text.
- `docs/pruning-tables.md` -- #123 / SCP 2026-11-12 reword note; #112 sentence put in past tense.

**Review:** 12 findings. Patched 1 (low: #112 sentence tense). Deferred 1 (low: 3.8-copy present tense at pruning-tables.md:175-176). Rejected 10 (reasons in the triage log). Patched counts: high 0, medium 0, low 1. Follow-up review: false.

**Verification:** `./gradlew spotlessCheck build` -- BUILD SUCCESSFUL (exit 0), run after implementation and again after the patch; AppShellSolveTest 9/9 passed. Grep for the old text outside `_bmad-output`/`_bmad`/build output -- only `docs/evidence/lenovo-tb128xu/*.log`, `docs/benchmarks.md:513` (dated Lenovo note) and the #112 history sentence in `docs/pruning-tables.md:180`.

**Residual risks:** No on-device check of the longer copy's wrapping (low; plain wrapping Text). Implementation ran on Codex.
