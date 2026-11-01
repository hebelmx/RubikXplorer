# Copilot instructions for RubikXplorer

## Project context

This repo is an Android app for teaching absolute beginners to solve a 3×3 cube on both a virtual cube and a physical cube, with an on-device camera-based recognition flow. The project intentionally keeps business logic in pure Kotlin/JVM modules and leaves Android/UI concerns in the app shell.

Source-of-truth project docs:
- `AGENTS.md` for repo-specific rules and build gates
- `docs/ARCHITECTURE.md` for the high-level architecture and constraints
- `_bmad-output/planning-artifacts/.../ARCHITECTURE-SPINE.md` for the architecture decisions and ownership matrix
- `_bmad-output/planning-artifacts/.../prd.md` for functional requirements and glossary terms

## Build, test, and lint commands

Use the repo's Gradle wrapper from the root. The CI workflow expects these environment values in fresh checkouts:

```bash
export JAVA_HOME=/usr/lib/jvm/java-25-openjdk-amd64
export ANDROID_HOME=$HOME/Android/Sdk
```

Primary validation before push:

```bash
./gradlew spotlessCheck build
./gradlew spotlessApply   # when formatting fixes are needed
```

This is the main CI gate and includes compile, Android lint, JVM tests, Kover coverage checks, module-boundary checks, merged-manifest checks, SDK-denylist checks, and debug/release APK builds. `build` is intentionally not allowed to depend on `detekt`.

Additional required validation for build-logic changes:

```bash
./gradlew -p build-logic test
```

APK-only builds:

```bash
./gradlew assembleDebug assembleRelease
```

Non-blocking advisory check:

```bash
./gradlew detekt
```

Run a single test or a filtered set with Gradle's `--tests` option, for example:

```bash
./gradlew :core:domain:test --tests "*"
./gradlew :core:solver:test --tests "*"
./gradlew :app:testDebugUnitTest --tests "dev.rubikxplorer.app.*"
```

When fixing or adding logic in `:core:*`, prefer JVM tests over instrumented Android tests.

## High-level architecture

The repo is split into a very small hexagonal architecture:

- `:app` is the Android shell: Compose UI, ViewModels, and adapters for camera, renderer, database, and lifecycle concerns.
- `:core:application` owns use cases, ports, and the reducer-driven learning session logic.
- `:core:domain` contains the pure cube model, move logic, content definitions, tutor logic, recognition primitives, and playback rules.
- `:core:solver` contains the in-house Kociemba two-phase solver implementation.
- `build-logic/` centralizes the shared Gradle conventions and policy gates so per-module build scripts do not bypass them.
- `macrobenchmark/` is an opt-in benchmarking module; it is not part of the default build path.

The important architectural boundary is that `:core:*` modules are plain Kotlin/JVM modules with no Android, Room, CameraX, Compose, or Hilt imports. Android and framework concerns live in `:app` only.

The app model is based on immutable state transitions: session changes flow through `SessionEvent` and a reducer (`reduce`) rather than direct mutation from screens or ViewModels. Async work is revisioned; stale results are discarded if they refer to an older state revision.

## Key conventions and project-specific rules

- Keep all cube-state creation and validation inside the domain rules. `CubeState` should only be reached through `CubeValidator` and the canonical conventions (home frame is white on U and green on F; facelet ordering follows the Kociemba net).
- Do not add Android-specific dependencies or imports into `:core:*` modules. A change that crosses the boundary is a design smell in this repo.
- `Certainty` is an explicit enum (`VERIFIED | LIKELY | UNKNOWN`), not a numeric confidence score. Only `tutor/Assess` produces it.
- Lessons, algorithms, and tutor thresholds are data-driven. The repo intentionally avoids hard-coded lesson logic or learner-facing strings in screens; text must use keyed rendering (`Text(key, args)` pattern) and content sits behind typed, closed registries.
- The solver is an internal clean-room implementation of Kociemba's two-phase algorithm; it is used for solve and recovery planning, not for lesson logic.
- The app is privacy-first/offline by default: no `INTERNET` permission, no analytics or crash-collection SDKs, and no backup settings that violate the repo policy.
- The build enforces module boundaries, manifest checks, SDK denylist checks, and Kover coverage on the pure core modules. Do not bypass these in module build scripts or ad hoc local changes.

## Working conventions to preserve

- All session-state changes go through the reducer/event pipeline rather than direct mutation.
- Prefer pure, testable logic in `:core:*` when implementing changes.
- Keep changes aligned with the architecture spine/ownership rules rather than creating new modules or layers without a clear need.
- Favor the existing `Text(key, args)` and data-driven content model over one-off hard-coded UI logic.

## Useful repo references

- `build.gradle.kts` and `build-logic/` for shared Gradle policy and gates
- `app/build.gradle.kts` for Android shell dependencies and test setup
- `core/domain/build.gradle.kts` and `core/solver/build.gradle.kts` for core-module expectations and test inputs
- `_bmad-output/planning-artifacts/...` for process artifacts and architecture decisions
