---
title: 'Select the first unread sticker in corner fixture review'
type: 'bugfix'
created: '2026-11-23'
status: 'done'
baseline_revision: '1640c5edc03bab5a91960d5eafc0010b80d10fdd'
review_loop_iteration: 0
followup_review_recommended: false
context: ['{project-root}/AGENTS.md']
warnings: []
deferred:
  - summary: >-
      Automate the fixture recorder's first and subsequent face transitions if a debug recorder UI harness is added.
    evidence: |-
      The regression tests run the same new-entry initialization as both handlers. Source inspection verifies both handlers and their selection binding. Neither screen transition currently has UI automation.
    location: >-
      app/src/camera/debug/kotlin/dev/cubentia/app/camera/recorder/FixtureRecorderScreen.kt:361
    severity: low
---

<intent-contract>

## Intent

**Problem:** When an operator reviews a captured corner face, sticker zero starts selected even if another sticker is unread. A palette tap can overwrite the read sticker and save the wrong fixture truth.

**Approach:** Start a new review with no pending operator selection so the existing prefill rule selects the first unread sticker in row order.

## Boundaries & Constraints

**Always:** Apply the same selection rule when opening the first face and advancing to any subsequent face. Keep explicit selection and the existing palette behavior.

**Never:** Change capture, detection, release flags, or historical fixtures. Historical labels require a separate photo audit.

## I/O & Edge-Case Matrix

| Scenario | Input / State | Expected Output / Behavior | Error Handling |
| --- | --- | --- | --- |
| Partially read | Sticker zero read, four and six unread | Four selected. Applying a palette color fills four and advances to six | Sticker zero retains its original color and prefilled source |
| Fully read | All nine stickers read | No selection. A palette tap has no effect until a sticker is explicitly selected | Reviewed face remains complete |

</intent-contract>

## Code Map

- `app/src/camera/debug/kotlin/dev/cubentia/app/camera/recorder/FixtureRecorderScreen.kt` uses `TruthEntry().withPrefill(...)` in both `usePlacement()` and the advance branch of `confirmCornerFace()`.
- `app/src/camera/debug/kotlin/dev/cubentia/app/camera/recorder/RecorderRules.kt` defines `TruthEntry`. The default selection of zero is retained by `withPrefill`. Other production callers already explicitly initialize without a selection.
- `app/src/testDebug/kotlin/dev/cubentia/app/camera/recorder/RecorderRulesTest.kt` covers explicit selection, prefill refresh, and truth sources.

## Tasks & Acceptance

**Execution:**
- `app/src/camera/debug/kotlin/dev/cubentia/app/camera/recorder/RecorderRules.kt` initializes a new `TruthEntry` with no pending selection so both corner review paths reuse the first-unread rule.
- `app/src/testDebug/kotlin/dev/cubentia/app/camera/recorder/RecorderRulesTest.kt` covers both matrix cases through the same new-entry initialization used by corner review.

**Acceptance Criteria:**
- Given a corner capture ready for review, when the operator opens a face or confirms one and advances, then the review grid highlights the first unread sticker or shows no selection if all stickers were read.

## Spec Change Log

## Review Triage Log

### 2026-11-23 — Review pass

- verdicts: 4 findings — high 0, medium 0, low 4, false 0, maybe-false 0
- findings:
  - `[low]` `[defer]` The two corner review transitions lack a UI regression. Source inspection confirms both create fresh entries and bind their selection. A debug recorder UI harness would address this pre-existing gap.
  - `[low]` `[reject]` The new partial-read test stops at the last unread sticker. The existing confirmation test separately checks the final color and truth-source lists, including sticker zero. The new test verifies the selection and mutation that previously caused corruption.
  - `[low]` `[reject]` The code map describes the pre-fix default in present tense. It records the baseline for the implementation. The result below documents the new default.
  - `[low]` `[defer]` The verification review independently confirmed a future transition-specific regression could escape the current JVM tests. Both handlers were inspected and use the tested constructor and prefill expression. This is the same pre-existing UI coverage gap.

## Verification

- `./gradlew :app:testDebugUnitTest --tests dev.cubentia.app.camera.recorder.RecorderRulesTest` exercises the new-entry review rules.
- `./gradlew spotlessCheck build` runs the repository checks with the documented Java and Android SDK environment.

## Auto Run Result

Status: done

- `RecorderRules.kt` starts an uninitialized `TruthEntry` with no selection. Both corner review handlers use that default, so prefill selects the first unread sticker or leaves a complete face unselected.
- `RecorderRulesTest.kt` verifies the partial and complete cases using the production initialization sequence, including palette behavior and preservation of prefilled sticker zero.
- Both new tests failed with the old default. After the fix, `./gradlew :app:testDebugUnitTest --tests dev.cubentia.app.camera.recorder.RecorderRulesTest` passed all 31 cases with no skips or failures. `./gradlew spotlessCheck build` passed. Both commands passed again during workflow verification with the repository's documented Java and Android SDK environment.
- The review found no implementation defect. The existing UI automation gap is deferred, two suggestions were rejected as recorded above, and no patches were needed. Follow-up review recommended: false (zero high or medium patches).
- The review traced both screen paths to the tested rule. No physical camera session was run. The historical fixture labels remain a separate audit.
