<!-- bmad:context -->
<!-- Verified 2026-11-08 against 2cfa628. Managed by bmad-project-context; edits inside this block are replaced on refresh. Keep anything you want preserved outside the markers. -->

## Cubentia

Offline Android tutor that teaches beginners to solve a 3×3 cube on a virtual or physical cube. v1.0 ships camera-free; on-device camera recognition is planned for v1.1. Kotlin, Jetpack Compose, hexagonal ports and adapters with a functional core: `:app` plus pure-JVM `:core:domain`, `:core:solver`, `:core:application` (and `:macrobenchmark`), built through convention plugins in `build-logic/`. Planning lives in `_bmad-output/planning-artifacts/` (PRD, architecture spine); the reader-facing overview is `docs/ARCHITECTURE.md`.

## Policy

- This file overrides the parent `/mnt/e/Dynamic/AGENTS.md`; its .NET, `artifacts/` and UFOAdventures rules do not apply here.
- Never commit to `main`; work on a feature branch and merge by PR so CI gates run. Conventional Commits.
- Never hand-edit `_bmad/` or `.claude/skills/` — installer-generated (BMAD 6.12.4); change behavior via `_bmad/custom/` with the `bmad-customize` skill.
- Never hand-edit `prd.md` or `ARCHITECTURE-SPINE.md` (status: final); change them through `bmad-prd` / `bmad-architecture` update or `bmad-correct-course`.
- Never edit a `.memlog.md` by hand; append with `uv run _bmad/scripts/memlog.py append`.
- Never add the `INTERNET` permission, backup, or any analytics/ads/crash SDK — NFR-8 promises "no data collected" (AD-13).
- Never copy third-party solver code; `:core:solver` is a clean-room Kociemba implementation (min2phase is GPLv3-only).

## Where things are

- Binding decisions AD-1…AD-20: `_bmad-output/planning-artifacts/architecture/architecture-RubikXplorer-2026-10-29/ARCHITECTURE-SPINE.md` — read it before adding a module, port, persisted entity, `SessionEvent`, or content field.
- Requirements and IDs (FR-n, NFR-n, Glossary): `_bmad-output/planning-artifacts/prds/prd-RubikXplorer-2026-10-29/prd.md`; use Glossary terms verbatim in type names.
- Build gates (AD-1 module boundaries, AD-13 manifest and SDK denylist, Kover, R8 keep rules): `build-logic/src/main/kotlin/dev/cubentia/buildlogic/`; change gates there, never in per-module build scripts.
- Camera, scan, Quick Check, Placement or Case-recognition work is v1.1: plan it from `_bmad-output/planning-artifacts/post-launch/v1.1-camera-recognition.md`, and keep `CAMERA_SCAN`, `QUICK_SCAN`, `PLACEMENT`, `CASE_RECOGNITION` OFF for release in `FeatureFlagLevels.kt`.
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
- Cube home frame is WHITE on U, GREEN on F; `Facelets` are URFDLB, row-major per the Kociemba net; reach a `CubeState` only through `CubeValidator`.
- Certainty is `VERIFIED | LIKELY | UNKNOWN`, produced only by `tutor/Assess`; never a numeric confidence, never fill a partial scan from the expected state.
- Lessons, Algorithms and tutor thresholds are JSON content with predicates from the closed Kotlin registry; never hard-code lesson logic or Learner-facing text in screens — use `Text(key, args)`.
- Domain failures are sealed results, not exceptions or nulls.

## Known pitfalls

- AGP 9 has built-in Kotlin: never apply `kotlin("android")` or kapt (build error); use KSP, and pin KGP 2.4.21 on the root classpath or Android modules compile with AGP's bundled 2.2.10.
- Verify Android/Gradle/Compose APIs and versions against current docs before using them; training-data versions were wrong in this project's own planning.
- `./gradlew: bad interpreter: /bin/sh^M`: `.gitattributes` now forces LF for `gradlew`; on an older checkout run `rm gradlew && git checkout -- gradlew`, and never commit a CRLF `gradlew`.
- Don't add modules, layers, or abstractions the spine doesn't require — split `:app` into feature modules only after P0.3.

<!-- /bmad:context -->

## Emulators

- Reference-device AVDs (Galaxy S24 FE on Android 15/16/17, Galaxy Tab S7 FE 5G on Android 14), KVM checks, launch/install commands and how to recreate them: `docs/emulators.md`. Emulators give indicative numbers only; performance and camera acceptance stay on the physical devices.
