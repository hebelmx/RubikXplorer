<!-- bmad:context -->
<!-- Verified 2026-11-14 against 355b7bd. Managed by bmad-project-context; edits inside this block are replaced on refresh. Keep anything you want preserved outside the markers. -->

## Cubentia

Offline Android tutor that teaches beginners to solve a 3×3 cube on a virtual or physical cube. On-device camera recognition (Epics 10–11) is built on `main` behind feature flags that stay OFF in release until Story 10.11 raises them: builds through 0.10.x are camera-free; 0.11.0 and v1.0 ship the Guided 6-face Scan as GA and Placement as BETA, plus the new Quick Scan as BETA once its port stories pass (SCP 2026-11-24 camera-release-timeline). Kotlin, Jetpack Compose, hexagonal ports and adapters with a functional core: `:app` plus pure-JVM `:core:domain`, `:core:solver`, `:core:application` (and `:macrobenchmark`), built through convention plugins in `build-logic/`. Planning lives in `_bmad-output/planning-artifacts/` (PRD, architecture spine); the reader-facing overview is `docs/ARCHITECTURE.md`.

## Policy

- This file overrides the parent `/mnt/e/Dynamic/AGENTS.md`; its .NET, `artifacts/` and UFOAdventures rules do not apply here.
- Never commit to `main`; work on a feature branch and merge by PR so CI gates run. Conventional Commits.
- Never hand-edit `_bmad/` or `.claude/skills/` — installer-generated (BMAD 6.12.4); change behavior via `_bmad/custom/` with the `bmad-customize` skill.
- Never hand-edit `prd.md` or `ARCHITECTURE-SPINE.md` (status: final); change them through `bmad-prd` / `bmad-architecture` update or `bmad-correct-course`.
- Planning documents and historical records keep the codename RubikXplorer (e.g. `prd-RubikXplorer-…` folders, SCPs, retros, memlogs); never rename them — new code and Learner text say Cubentia.
- Never edit a `.memlog.md` by hand; append with `uv run _bmad/scripts/memlog.py append`.
- Never add the `INTERNET` permission, backup, or any analytics/ads/crash SDK — NFR-8 promises "no data collected" (AD-13).
- Test features on the main app build, from their real entry point: debug raises a feature flag above OFF only once that flag's entry point is wired in the main app (in the same PR), and a debug-only launcher or debug-only source set is never a story's device-session or acceptance path; debug-only source sets hold developer tools only (AD-15, AD-18).
- Never copy third-party solver code; `:core:solver` is a clean-room Kociemba implementation (min2phase is GPLv3-only).

## Where things are

- Binding decisions AD-1…AD-20: `_bmad-output/planning-artifacts/architecture/architecture-RubikXplorer-2026-10-29/ARCHITECTURE-SPINE.md` — read it before adding a module, port, persisted entity, `SessionEvent`, or content field.
- Requirements and IDs (FR-n, NFR-n, Glossary): `_bmad-output/planning-artifacts/prds/prd-RubikXplorer-2026-10-29/prd.md`; use Glossary terms verbatim in type names.
- Build gates (AD-1 module boundaries, AD-13 manifest and SDK denylist, Kover, R8 keep rules): `build-logic/src/main/kotlin/dev/cubentia/buildlogic/`; change gates there, never in per-module build scripts.
- Camera, scan, Quick Check, Placement or Case-recognition work is v1.0 scope (SCP 2026-11-20; release levels per SCP 2026-11-24 camera-release-timeline): plan it from Epics 10–11 in `_bmad-output/planning-artifacts/epics.md` (`post-launch/v1.1-camera-recognition.md` is only a historical pointer) and spine AD-9/AD-13/AD-18. Camera code lives only in the `app/src/camera/` source set behind the `ScanLauncher` port, never in `app/src/main`; ML Kit and `play-services-mlkit` are denylisted. Keep `CAMERA_SCAN`, `QUICK_SCAN`, `PLACEMENT`, `CASE_RECOGNITION` OFF for release in `build-logic/.../policy/FeatureFlagLevels.kt` until Story 10.11 raises one (the last three need `CAMERA_SCAN` above OFF); with all OFF the release has no `CAMERA` permission and no CameraX/ML on its classpath.
- Public website and privacy policy: `https://hebelmx.github.io/cubentia-site/privacy/`, built from the separate repo `hebelmx/cubentia-site` (never rename it). Its `check` job reads `docs/privacy-facts.json` at `vars.APP_REF`; regenerate the file with `./gradlew :app:writePrivacyFacts` and verify it with `:app:checkPrivacyFacts`. A manifest, permission or SDK change also changes the public policy: update the site page in the same release (`docs/release.md`).
- Next planning step or unsure which workflow fits: run the `bmad-help` skill.

## Running and verifying

- Env: `export JAVA_HOME=/usr/lib/jvm/java-25-openjdk-amd64 ANDROID_HOME=$HOME/Android/Sdk`; JDK 25 runs Gradle locally (CI uses 17), and `ANDROID_HOME` is required wherever the gitignored `local.properties` is absent (fresh clone or worktree).
- Before pushing, run what CI gates: `./gradlew spotlessCheck build` (compile, lint, JVM tests, Kover, boundary/manifest/denylist checks, debug and release APKs); fix formatting with `./gradlew spotlessApply`.
- `build` does not test the included `build-logic` build; after touching it also run `./gradlew -p build-logic test`.
- APKs only: `./gradlew assembleDebug assembleRelease`. `./gradlew detekt` is advisory (AD-14); never make `build` depend on it — CI fails if it does.
- Toolchain (verified 2026-10-29): Kotlin 2.4.21, AGP 9.5.0, Gradle wrapper 9.8.2, Java 17 bytecode target, compileSdk 37 / targetSdk 36 / minSdk 29; versions live only in `gradle/libs.versions.toml`.
- Pure JVM modules (`:core:*`) are where tests and the ≥ 80% Kover gate apply; prefer JVM tests over instrumented tests for any logic.

## Conventions that differ from defaults

- `:core:domain`, `:core:solver`, `:core:application` are `kotlin("jvm")` with no Android, Compose, Room, CameraX or Hilt imports; Android code goes in `:app` only.
- All session state changes go through `SessionEvent` → `reduce()`; ViewModels and composables never mutate state. Async results must echo the effect's `revision` or be discarded.
- Each persisted entity has one writer (Ownership Matrix in the spine) — e.g. calibration only via `UpdateCalibration`, Algorithm Status only via `SetAlgorithmStatus`.
- The applicationId is `com.exxerpro.cubentia`, but Kotlin packages and namespaces are `dev.cubentia.*`; use the applicationId for `adb`, Macrobenchmark targets and Play, never `dev.cubentia`.
- Cube home frame is WHITE on U, GREEN on F; `Facelets` are URFDLB, row-major per the Kociemba net; reach a `CubeState` only through `CubeValidator`.
- Certainty is `VERIFIED | LIKELY | UNKNOWN`, produced only by `tutor/Assess`; never a numeric confidence, never fill a partial scan from the expected state.
- Lessons, Algorithms and tutor thresholds are JSON content with predicates from the closed Kotlin registry; never hard-code lesson logic or Learner-facing text in screens — use `Text(key, args)`. Learner text goes in `core/domain/src/main/resources/content/strings/en.json`; `checkLearnerText` rejects new Learner text in `strings.xml`.
- Domain failures are sealed results, not exceptions or nulls.

## Known pitfalls

- AGP 9 has built-in Kotlin: never apply `kotlin("android")` or kapt (build error); use KSP, and pin KGP 2.4.21 on the root classpath or Android modules compile with AGP's bundled 2.2.10.
- Verify Android/Gradle/Compose APIs and versions against current docs before using them; training-data versions were wrong in this project's own planning.
- `./gradlew: bad interpreter: /bin/sh^M`: `.gitattributes` now forces LF for `gradlew`; on an older checkout run `rm gradlew && git checkout -- gradlew`, and never commit a CRLF `gradlew`.
- Don't add modules, layers, or abstractions the spine doesn't require — split `:app` into feature modules only after P0.3.

<!-- /bmad:context -->

## Communication & Human-in-the-Loop Rules

- **Lead with the bottom line:** Start every response with a 1–2 sentence plain-English summary of what changed, what was decided, or what you need next before diving into technical details or file paths.
- **Pair IDs with plain meaning:** On first mention in any message, explain internal shorthand (e.g., write *"Story 10.20 (offline replay benchmark)"* or *"AD-15 (debug feature flag policy)"*, never just *"10.20"* or *"AD-15"*).
- **Keep git metadata minimal:** Mention branch names and commit hashes once at the end as a reference, rather than weaving them into the main explanation.
- **When pausing for a decision or sign-off:**
  - Translate internal shorthand (story numbers, AC codes, design tokens, CI gates) into plain product/engineering impact first.
  - Briefly explain *why* the blocker matters before listing numbered options.
  - Provide a clear recommendation (e.g., *"Option A [Recommended]: ..."*) rather than open-ended questions.

## Orchestration

- A story built in another repository (for example `hebelmx/cubentia-site`) routes its spec deferrals and its post-merge human checks to `action_items` in `sprint-status.yaml` when the story closes; deferrals left only in a spec's frontmatter get lost (Epic 13 retro item 111).

## Emulators

- Reference-device AVDs (Galaxy S24 FE on Android 15/16/17, Galaxy Tab S7 FE 5G on Android 14), KVM checks, launch/install commands and how to recreate them: `docs/emulators.md`. Emulators give indicative numbers only; performance and camera acceptance stay on the physical devices.

## Closing GitHub issues

These rules bind every agent, orchestrator, PR and Sprint Change Proposal (SCP). An issue closes in only two ways:

1. **Resolved** (close reason *completed*): the change is merged on `main` and every "Done when" point is proven by a test, `file:line` or device evidence. A story may deliver it; the issue then closes when that story merges and proves it.
2. **Decided** (close reason *not planned*): the product owner decides it will not be done (won't fix, accepted as is, obsolete, superseded) or that there is nothing to do (does not exist, cannot be reproduced, duplicate). Ask naming each issue number and saying it will close; the closing comment records the decision. Approval of a plan, an SCP or a group that carries exceptions is not that decision.

- **A plan is never a resolution.** An SCP, a new or backlog story, an action item, a deferral, a "parked" label or "work continues in Story X" leaves the issue open; comment the story key on it.
- **The record goes on the issue before it closes.** Tick each proven "Done when" box in the issue body and post a closing comment that maps every point to its evidence. A PR link, ticked boxes only in the PR, or the issue's "Possible fix" is not that record. If a point was changed or narrowed during the work, say what changed and who decided it.
- **Owed work keeps it open.** If any "Done when" point is unproven, including a device or human check that was skipped, waived at merge or deferred, the issue stays open unless the product owner explicitly decides to close it without that point (comment which point, and who decided). This covers the points the issue itself asks for: a device or human check that comes only from a story's acceptance criteria or a code review is tracked as an action item and does not keep the issue open.
- **Planning and partial PRs say `Refs #N`.** Never put `close`, `fix` or `resolve` (any form) directly before an issue number in a PR or commit that does not resolve it: GitHub closes the issue even inside a sentence such as "the build PR closes #N" (this closed #203 early).
- **Dropping or superseding a story** means listing every issue it carried, open or already closed on the strength of that story, and getting a decision on each: keep open (re-plan) or *not planned*. Reopen an issue that was closed on a plan that is now dropped.
