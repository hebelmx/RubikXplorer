# Epic 7 Context: Complete the Course to a first solve and mastery

<!-- Generated from planning artifacts. Regenerate with compile-epic-context if planning docs change. -->

## Goal

A Learner can progress through all seven Stages of the beginner Course to a first solve, practice on fresh Scrambles with reduced help until a Stage is Mastered, see their hint trend fall, reset progress, and skip ahead by passing a check. The epic closes by putting a signed Cubentia build in about 8 pilot beginners' hands through Play closed testing around week 9. The pilot results have to come back in time for the pre-production fixes, and the observed pilot is the evidence for the usability release gate.

## Stories

- Story 7.1: Stage progress log and gating
- Story 7.2: Learn tab and Stage detail
- Story 7.3: Author Stages 4–6 to a first solve
- Story 7.4: Independent practice on a fresh Scramble
- Story 7.5: Mastery Condition and milestone
- Story 7.6: Progress on the You tab, with reset
- Story 7.7: Welcome and the skip-ahead check
- Story 7.8: Ship a pilot build to closed testing

## Requirements & Constraints

**Course and progression (7.1–7.7, built):**
- Stage completion comes from the Completion Predicate on the Cube State only, never from Move count.
- Skip-ahead marks a Stage *Skipped*, never *Mastered*. Giving up records nothing against the Learner.
- Mastery Condition (configurable): 2 successful completions, at least 1 with reduced assistance.
- Reset progress erases Stages and attempts (plus Algorithm Statuses) and leaves calibration and preferences alone.

**Pilot release (7.8):**
- **Privacy:** no account, no PII, no analytics, ads or crash SDK, and no network. All data stays in app-private storage and backup is disabled. The Play data-safety form declares "no data collected". Pilot observation (task completion, hint use, points of confusion, recovery from deliberate mistakes) is recorded **outside the app** in a written pilot session protocol. The app gets no telemetry for this.
- **Stability:** at least 99.5% crash-free sessions and ANR below the Play bad-behavior threshold. Android vitals from the closed-testing build is the only field signal. The vitals review itself belongs to release-readiness (Epic 12), not 7.8.
- **Size:** the release AAB's size check must report against an installed size under 30 MB. That budget includes the bundled Solver pruning tables (bundled delivery was chosen). APK or AAB file size alone is not accepted as installed-size evidence. Size depends on the build, not the device, so it is measured on the release AAB.
- **Usability release gate:** a pilot group of beginners completes the Course. The pilot confirms that Move instructions make sense without knowing Notation and that Learners recover from deliberate mistakes. Every severity-1 and severity-2 issue must be fixed before production (that work is in Epic 12). The 7.8 pilot record feeds the success-metric baselines SM-1–SM-9. With about 8 people these are directional only.
- **Pilot logistics (open question):** who the ~8 beginner testers are and when closed testing starts (proposed: once the Course is usable), plus whether to run the A/B comparison of plain instructions against contextual explanation. The A/B run is possible only with consenting participants.
- **Story split:** release-workflow and protocol preparation may close separately from actual signed distribution, because secrets, the Play account, manual promotion and participants are external prerequisites. Real pilot access must still happen early, and must not be pushed to the end of production.

## Technical Decisions

- **Signing and build types:**
  - AAB only, with Play App Signing. The upload keystore lives in GitHub Actions secrets.
  - `release` uses R8 with resource shrinking, and its keep rules are owned by `build-logic`. `debug` keeps StrictMode, the fixture recorder and all feature flags.
- **Release flow:**
  - Development is trunk-based on `main`. A `vX.Y.Z` tag triggers the release workflow.
  - The workflow builds the signed AAB, sets `versionCode` to the CI run number, uploads the R8 mapping file, runs instrumentation, UI and renderer-sync tests (tags only, on top of PR CI), runs the size check, and uploads to the Play **internal** track.
  - Promotion internal → closed testing → production is **manual** in Play Console.
- **Identity:**
  - `applicationId = "com.exxerpro.cubentia"` and the app name is "Cubentia". The 7.8 upload is the first Play upload, and an application ID can never change after it.
  - Learner-facing text contains no "Rubik" (a test enforces this).
  - Publishing uses the verified ExxerPro organization Play account, so the new-personal-account rule of 12 testers for 14 days does not apply.
- **Offline and privacy enforced by the build:**
  - The merged release manifest has no `INTERNET` permission, and v1.0 declares **no** runtime permission (`CAMERA` arrives only in v1.1).
  - `allowBackup="false"`, with `dataExtractionRules` that exclude everything.
  - Release logs never contain Cube States.
  - CI fails on any violation or on a denylisted analytics, ads or crash SDK. `checkReleaseMergedManifest` keys on `variant.debuggable`.
  - Every camera feature flag is OFF in release.
- **PR CI already runs:** build, Spotless/ktlint, Android lint (including accessibility), JVM tests, Kover ≥ 80% on `:core:*`, the privacy checks and an AAB size check. detekt is non-blocking.
- **Reference devices:**
  - Minimum: Lenovo Tab M10 Plus 3rd Gen (TB128XU, Android 13 / API 33). Mid: Samsung Galaxy S24 FE.
  - The Galaxy Tab S7 FE 5G is now an extra compatibility device only.
  - The Lenovo meets every performance NFR, and its known limits are tracked in #119, which is not v1.0-blocking.
  - 7.8 names no device.

## Cross-Story Dependencies

- **7.8 runs after the pre-pilot stories:**
  - 12.6, the Cubentia rebrand and application ID. It must land before the first upload.
  - 12.7–12.10, the Epic 8 retro fixes, including the release-permission gate proof.
  - 12.11 and 12.12, the Epic 9 seams and the 2-look rules.
  - 12.13, the durable session end.
- **Pre-release copy:** Learner-visible copy fixes must ship before 7.8. For example, `msg.solve.preparing` must read "Preparing the solver. The first solve after the app starts takes longer. Later solves are faster."
- **The pilot build packages** the full Course from 7.1–7.7, plus Epics 2–6, 8 and 9.
- **Epic 12 consumes 7.8:**
  - 12.3 reviews closed-testing vitals and the data-safety form.
  - 12.4 triages the 7.8 pilot record against the release gates.
  - 12.5 does the store listing, the privacy-policy page and the closed → production promotion.
