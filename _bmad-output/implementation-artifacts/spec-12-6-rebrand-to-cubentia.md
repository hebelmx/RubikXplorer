---
title: 'Rebrand to Cubentia'
type: 'refactor'
created: '2026-11-08'
status: 'done'
baseline_revision: '5ad27497d03146e7f67b475eaf41914108092207'
review_loop_iteration: 0
followup_review_recommended: false
context:
  - '{project-root}/AGENTS.md'
  - '{project-root}/_bmad-output/implementation-artifacts/epic-12-context.md'
warnings: ['oversized']
deferred:
  - summary: >-
      Macrobenchmark target package is a hand-copied literal of the applicationId; nothing checks it equals :app's applicationId.
    evidence: |-
      Net2DFrameTimingBenchmark.TARGET_PACKAGE and LessonProcessDeathTest.PACKAGE are literals ("com.exxerpro.cubentia", correct today); CI only assembles the benchmark module and never runs connected tests, so drift would surface only on a device run.
    location: >-
      macrobenchmark/src/main/kotlin/dev/cubentia/macrobenchmark/Net2DFrameTimingBenchmark.kt:59
    severity: medium
  - summary: >-
      AGENTS.md needs the planned bmad-project-context refresh after the rename (stale "Verified against 2cfa628" stamp; add the applicationId-vs-namespace rule).
    evidence: |-
      The managed bmad:context block was renamed in place by the script; the SCP (section 5) schedules a refresh after 12.6. The applicationId com.exxerpro.cubentia no longer shares a prefix with the namespace dev.cubentia.app, a source of adb/benchmark mistakes.
    location: >-
      AGENTS.md:1-60
    severity: low
---

<intent-contract>

## Intent

**Problem:** The app ships under the codename RubikXplorer (application ID `dev.rubikxplorer`, packages `dev.rubikxplorer.*`), and two Learner-facing strings say "Rubik's". The application ID becomes permanent at the first Play upload (Story 7.8), and "Rubik's" is a trademark.

**Approach:** Run one scripted, dry-run-first rename (`rubikxplorer` → `cubentia`, case-preserving) over tracked, non-excluded files and paths. Set the applicationId to `com.exxerpro.cubentia`. Fix the two strings, and add a content test that keeps "Rubik" out of Learner-facing text. Prove it with the full local gate.

## Boundaries & Constraints

**Always:**
- `applicationId = "com.exxerpro.cubentia"`; namespaces `dev.cubentia.app` and `dev.cubentia.macrobenchmark`; packages `dev.cubentia.*`; `rootProject.name = "Cubentia"`; `app_name` "Cubentia"; DB file `cubentia.db`.
- Wherever the old *applicationId* (`dev.rubikxplorer` with no package segment after it) is used, it becomes `com.exxerpro.cubentia`: macrobenchmark target packages, the adb commands in docs, test manifests. An adb component written relative to the old ID (`dev.rubikxplorer/.app.X`) becomes fully qualified: `com.exxerpro.cubentia/dev.cubentia.app.X`.
- Rename case-preserving: `rubikxplorer`→`cubentia`, `RubikXplorer`→`Cubentia`, `RUBIKXPLORER`→`CUBENTIA`. This covers identifiers (`RubikXplorerApp`→`CubentiaApp`, `RubikXplorerTheme`→`CubentiaTheme`, `generateRubikXplorerKeepRules`), plugin IDs and catalog aliases (`cubentia.jvm-core`, `libs.plugins.cubentia.*`), `-Pcubentia.*` properties, `reports/cubentia/`, and `cubentia-keep-rules.pro`.
- Moves use `git mv` and keep each file's content close to the original, so git detects renames and history is kept.
- Preserve each file's line endings.

**Never:**
- Touch the excluded paths: `.git/`, `_bmad/`, `.claude/` (skills, hooks, settings), `.agents/` (agent skill trees, including the project-owned `rubikxplorer-copilot-orchestrate` skill, whose name and `../RubikXplorer-worktrees` dir are tied to the repo/folder rename that is deferred to Story 12.5), build outputs (`build/`, `.gradle/`, `.kotlin/`, untracked files), or anything under `_bmad-output/`. The whole of `_bmad-output/` is historical or generated: planning artifacts, where the codename stays by PO decision; SCPs; retros; memlogs; `spec-*`; `plan-*`; `bmad-build-auto-result-*`; compiled `epic-*-context.md`; and `sprint-status.yaml`, whose `project:` is generated from `_bmad` config.
- Rewrite literal references that point *at* excluded paths, because they must keep resolving: `prd-RubikXplorer-2026-10-29`, `architecture-RubikXplorer-2026-10-29`, `ux-RubikXplorer-2026-10-29` (planning folder names), and `rubikxplorer-copilot-orchestrate`.
- Rename other `Rubik*` code identifiers. These stay, for a Story 12.5 decision: `RubikTheme`, `RubikDatabase`, `RubikTokens`, `LocalRubikTokens`, `RubikShapes`, `RubikTypography`, `RubikTextStyles`, `LocalRubikTextStyles`, `RubikExtraTextStyles`, the `Fixtures/` capture folders `RubikStickered_*`, the "Rubik stickered" rows in `docs/spikes/oq-4-guided-scan.md`, and "Rubik's Cube" prose in README and `docs/mvp-draft-product-and-architecture.md` (not Learner-facing).
- Rename the GitHub repo or the local working folder (Story 12.5). Change behavior, add a Room migration, or touch any test expectation other than package or ID renames.

## I/O & Edge-Case Matrix

| Scenario | Input / State | Expected Output / Behavior | Error Handling |
|----------|--------------|---------------------------|----------------|
| Trademark copy | `msg.milestone.first-solve`, `msg.practice.stage-07-independent-practice.objective` | "You solved the cube!" / "Solve the cube from a fresh scramble." | — |
| Guard test, shipped | every `en.json` value and every `strings.xml` string/plural item | none contains "rubik" (case-insensitive); the test passes | Fails, naming each offending key |
| Guard test, negative | in-test sample containing "Rubik's" (and "RUBIK") | the same matcher reports it | — |

</intent-contract>

## Risk Analysis (AC 1, written before any rename)

- **Application-ID permanence.** Play locks the application ID at the first upload, which is Story 7.8 and has not happened yet. So `com.exxerpro.cubentia` must land now. A later change would mean a new store listing. The namespace (`dev.cubentia.app`) is separate from the applicationId. The ID is set once, in `app/build.gradle.kts`. The macrobenchmark `TARGET_PACKAGE`/`PACKAGE` constants and the adb docs must use the ID, not the namespace, or the benchmarks launch nothing.
- **Room DB file rename.** `rubikxplorer.db` → `cubentia.db` in `PersistenceModule`. There are no installed users yet, so no migration is needed. Developer and emulator installs also change applicationId, so they install as a new app and the old app's data is simply left behind. Uninstall `dev.rubikxplorer` manually. Schema JSONs move with the class FQN (`app/schemas/dev.cubentia.app.data.RubikDatabase/`). `RubikDatabase` keeps its name, so `identityHash` and the migration tests are unaffected.
- **Package and namespace effects.**
  - The R class moves to `dev.cubentia.app.R`, because imports are rewritten by the same rule.
  - Hilt, KSP and Room code is regenerated from the renamed sources. Stale `build/` output must not mask errors, so verification runs `clean`.
  - Room `schemaDirectory` is unchanged. The asset lookup follows the moved folder.
  - The R8 keep resource is renamed together with its `GenerateKeepRulesTask.RESOURCE` constant. The rules text names no app package.
  - The `.App`-relative manifest names resolve against the new namespace.
  - Macrobenchmark targets the new ID.
  - `--tests 'dev.cubentia…'` filters in docs, `Fixtures/README.md` and `.github/copilot-instructions.md` are rewritten. CI (`ci.yml`) has no package filter.
  - `CubeCoverageGate.PACKAGE` and the Kover filters follow the package rule.
  - The convention-plugin IDs change in both `build-logic/build.gradle.kts` and the catalog.
- **Excluded paths.** See Never. The listing below proves they are untouched. Any remaining `rg -i rubikxplorer` hit outside them is one of the four protected literal references.
- **Rollback.** The whole rename is one commit on `feat/12-6-rebrand-to-cubentia`. `git revert <sha>` restores the tree, and the branch never reaches `main` without CI. No data migration needs undoing.

## Dry-Run Listing (AC 2, recorded before any write)

Script: `rename_to_cubentia.py` (Design Notes). Dry run on `5ad27497d03146e7f67b475eaf41914108092207`: `python3 -I rename_to_cubentia.py --root .`. Universe: `git ls-files`, minus the excluded prefixes. `M` = contents change, `A` = applicationId-rule hit, `R` = `git mv`.

<details><summary>Full listing (421 content changes, 9 applicationId hits, 24 moves)</summary>

```text
# content changes: 421 files
M .github/copilot-instructions.md
M AGENTS.md
M Fixtures/README.md
M README.md
M app/build.gradle.kts
M app/src/androidTest/kotlin/dev/rubikxplorer/app/data/SessionSnapshotRestartTest.kt
M app/src/androidTest/kotlin/dev/rubikxplorer/app/render/Cube3DRenderSyncTest.kt
M app/src/androidTest/kotlin/dev/rubikxplorer/app/session/EndToEndSyncVerificationTest.kt
M app/src/debug/kotlin/dev/rubikxplorer/app/DebugOptions.kt
M app/src/debug/kotlin/dev/rubikxplorer/app/solver/PruningTablesBenchmarkActivity.kt
M app/src/main/AndroidManifest.xml
M app/src/main/kotlin/dev/rubikxplorer/app/MainActivity.kt
M app/src/main/kotlin/dev/rubikxplorer/app/RubikXplorerApp.kt
M app/src/main/kotlin/dev/rubikxplorer/app/clock/SystemClock.kt
M app/src/main/kotlin/dev/rubikxplorer/app/content/ContentStrings.kt
M app/src/main/kotlin/dev/rubikxplorer/app/content/LessonCatalog.kt
M app/src/main/kotlin/dev/rubikxplorer/app/data/AttemptDao.kt
M app/src/main/kotlin/dev/rubikxplorer/app/data/AttemptEntity.kt
M app/src/main/kotlin/dev/rubikxplorer/app/data/CheckpointDao.kt
M app/src/main/kotlin/dev/rubikxplorer/app/data/CheckpointEntity.kt
M app/src/main/kotlin/dev/rubikxplorer/app/data/CheckpointPayload.kt
M app/src/main/kotlin/dev/rubikxplorer/app/data/PreferencesDao.kt
M app/src/main/kotlin/dev/rubikxplorer/app/data/PreferencesEntity.kt
M app/src/main/kotlin/dev/rubikxplorer/app/data/ProgressDao.kt
M app/src/main/kotlin/dev/rubikxplorer/app/data/ProgressEventEntity.kt
M app/src/main/kotlin/dev/rubikxplorer/app/data/RoomAttemptRepository.kt
M app/src/main/kotlin/dev/rubikxplorer/app/data/RoomCheckpointRepository.kt
M app/src/main/kotlin/dev/rubikxplorer/app/data/RoomPreferencesRepository.kt
M app/src/main/kotlin/dev/rubikxplorer/app/data/RoomProgressRepository.kt
M app/src/main/kotlin/dev/rubikxplorer/app/data/RoomSessionSnapshotRepository.kt
M app/src/main/kotlin/dev/rubikxplorer/app/data/RubikDatabase.kt
M app/src/main/kotlin/dev/rubikxplorer/app/data/SerialWriter.kt
M app/src/main/kotlin/dev/rubikxplorer/app/data/SessionSnapshotDao.kt
M app/src/main/kotlin/dev/rubikxplorer/app/data/SessionSnapshotEntity.kt
M app/src/main/kotlin/dev/rubikxplorer/app/data/SnapshotPayload.kt
M app/src/main/kotlin/dev/rubikxplorer/app/di/ClockModule.kt
M app/src/main/kotlin/dev/rubikxplorer/app/di/ContentModule.kt
M app/src/main/kotlin/dev/rubikxplorer/app/di/FlagsModule.kt
M app/src/main/kotlin/dev/rubikxplorer/app/di/PersistenceModule.kt
M app/src/main/kotlin/dev/rubikxplorer/app/flags/BuildConfigFeatureFlags.kt
M app/src/main/kotlin/dev/rubikxplorer/app/preferences/LocalPreferences.kt
M app/src/main/kotlin/dev/rubikxplorer/app/preferences/PreferenceStore.kt
M app/src/main/kotlin/dev/rubikxplorer/app/random/KotlinRandomSource.kt
M app/src/main/kotlin/dev/rubikxplorer/app/render/Cube3DRenderer.kt
M app/src/main/kotlin/dev/rubikxplorer/app/render/CubeRenderer.kt
M app/src/main/kotlin/dev/rubikxplorer/app/render/Net2DRenderer.kt
M app/src/main/kotlin/dev/rubikxplorer/app/render/PresentationItem.kt
M app/src/main/kotlin/dev/rubikxplorer/app/render/RenderStyle.kt
M app/src/main/kotlin/dev/rubikxplorer/app/render/StickerGlyph.kt
M app/src/main/kotlin/dev/rubikxplorer/app/render/gl/CubeGlRenderer.kt
M app/src/main/kotlin/dev/rubikxplorer/app/render/gl/CubeMesh.kt
M app/src/main/kotlin/dev/rubikxplorer/app/render/gl/CubieModel.kt
M app/src/main/kotlin/dev/rubikxplorer/app/render/gl/GlyphAtlas.kt
M app/src/main/kotlin/dev/rubikxplorer/app/render/gl/PresentationQueue.kt
M app/src/main/kotlin/dev/rubikxplorer/app/render/gl/ViewProjectionCache.kt
M app/src/main/kotlin/dev/rubikxplorer/app/session/PresentationMapping.kt
M app/src/main/kotlin/dev/rubikxplorer/app/session/SessionHost.kt
M app/src/main/kotlin/dev/rubikxplorer/app/ui/editor/ManualCubeEditor.kt
M app/src/main/kotlin/dev/rubikxplorer/app/ui/editor/ManualCubeEditorState.kt
M app/src/main/kotlin/dev/rubikxplorer/app/ui/editor/StickerReviewNet.kt
M app/src/main/kotlin/dev/rubikxplorer/app/ui/freecube/FreeCubeScreen.kt
M app/src/main/kotlin/dev/rubikxplorer/app/ui/freecube/FreeCubeViewModel.kt
M app/src/main/kotlin/dev/rubikxplorer/app/ui/freecube/MoveKeyBar.kt
M app/src/main/kotlin/dev/rubikxplorer/app/ui/freecube/MovePlainName.kt
M app/src/main/kotlin/dev/rubikxplorer/app/ui/freecube/ObjectiveOverlay.kt
M app/src/main/kotlin/dev/rubikxplorer/app/ui/freecube/ViewControls.kt
M app/src/main/kotlin/dev/rubikxplorer/app/ui/lesson/AssistanceLine.kt
M app/src/main/kotlin/dev/rubikxplorer/app/ui/lesson/CertaintyChip.kt
M app/src/main/kotlin/dev/rubikxplorer/app/ui/lesson/ConfirmCubeCard.kt
M app/src/main/kotlin/dev/rubikxplorer/app/ui/lesson/DemonstrationPlayer.kt
M app/src/main/kotlin/dev/rubikxplorer/app/ui/lesson/DiagnosisLine.kt
M app/src/main/kotlin/dev/rubikxplorer/app/ui/lesson/FirstSolveMilestone.kt
M app/src/main/kotlin/dev/rubikxplorer/app/ui/lesson/GuidedRecoveryLine.kt
M app/src/main/kotlin/dev/rubikxplorer/app/ui/lesson/HintLine.kt
M app/src/main/kotlin/dev/rubikxplorer/app/ui/lesson/LessonPanel.kt
M app/src/main/kotlin/dev/rubikxplorer/app/ui/lesson/LessonScreen.kt
M app/src/main/kotlin/dev/rubikxplorer/app/ui/lesson/MismatchLadder.kt
M app/src/main/kotlin/dev/rubikxplorer/app/ui/lesson/RecoveryPrompt.kt
M app/src/main/kotlin/dev/rubikxplorer/app/ui/lesson/ReduceMotion.kt
M app/src/main/kotlin/dev/rubikxplorer/app/ui/lesson/ResetConfirmation.kt
M app/src/main/kotlin/dev/rubikxplorer/app/ui/lesson/TeachingModeSwitcher.kt
M app/src/main/kotlin/dev/rubikxplorer/app/ui/playback/PlaybackTransport.kt
M app/src/main/kotlin/dev/rubikxplorer/app/ui/settings/SettingsScreen.kt
M app/src/main/kotlin/dev/rubikxplorer/app/ui/shell/AppShell.kt
M app/src/main/kotlin/dev/rubikxplorer/app/ui/shell/LearnScreen.kt
M app/src/main/kotlin/dev/rubikxplorer/app/ui/shell/WindowWidthClass.kt
M app/src/main/kotlin/dev/rubikxplorer/app/ui/shell/YouScreen.kt
M app/src/main/kotlin/dev/rubikxplorer/app/ui/solve/SolutionPlayback.kt
M app/src/main/kotlin/dev/rubikxplorer/app/ui/theme/Shape.kt
M app/src/main/kotlin/dev/rubikxplorer/app/ui/theme/Spacing.kt
M app/src/main/kotlin/dev/rubikxplorer/app/ui/theme/Theme.kt
M app/src/main/kotlin/dev/rubikxplorer/app/ui/theme/Tokens.kt
M app/src/main/kotlin/dev/rubikxplorer/app/ui/theme/Type.kt
M app/src/main/kotlin/dev/rubikxplorer/app/ui/trend/DotSparkline.kt
M app/src/main/kotlin/dev/rubikxplorer/app/ui/trend/TrendRow.kt
M app/src/main/kotlin/dev/rubikxplorer/app/ui/welcome/WelcomeScreen.kt
M app/src/main/res/values/strings.xml
M app/src/release/kotlin/dev/rubikxplorer/app/DebugOptions.kt
M app/src/sharedTest/kotlin/dev/rubikxplorer/app/data/SnapshotRestartScenario.kt
M app/src/sharedTest/kotlin/dev/rubikxplorer/app/session/EndToEndSyncScenario.kt
M app/src/sharedTest/kotlin/dev/rubikxplorer/app/session/TestLessons.kt
M app/src/spike3d/AndroidManifest.xml
M app/src/spike3d/kotlin/dev/rubikxplorer/app/spike3d/GlesCubeRenderer.kt
M app/src/spike3d/kotlin/dev/rubikxplorer/app/spike3d/Spike3dActivity.kt
M app/src/spike3d/kotlin/dev/rubikxplorer/app/spike3d/SpikeCubeModel.kt
M app/src/spike3d/kotlin/dev/rubikxplorer/app/spike3d/SpikeRenderer.kt
M app/src/spike3d/kotlin/dev/rubikxplorer/app/spike3d/SpikeTurnDriver.kt
M app/src/spike3dNoSceneView/kotlin/dev/rubikxplorer/app/spike3d/SpikeEngines.kt
M app/src/spike3dSceneView/kotlin/dev/rubikxplorer/app/spike3d/SceneViewCubeRenderer.kt
M app/src/spike3dSceneView/kotlin/dev/rubikxplorer/app/spike3d/SpikeEngines.kt
M app/src/spike3dTest/kotlin/dev/rubikxplorer/app/spike3d/SpikeCubeModelTest.kt
M app/src/spikeScan/kotlin/dev/rubikxplorer/app/spikeScan/GuidedScanActivity.kt
M app/src/test/kotlin/dev/rubikxplorer/app/content/AssistanceCopyTest.kt
M app/src/test/kotlin/dev/rubikxplorer/app/content/ContentStringsTest.kt
M app/src/test/kotlin/dev/rubikxplorer/app/content/DiagnosisCopyTest.kt
M app/src/test/kotlin/dev/rubikxplorer/app/content/HintCopyTest.kt
M app/src/test/kotlin/dev/rubikxplorer/app/data/CheckpointPayloadTest.kt
M app/src/test/kotlin/dev/rubikxplorer/app/data/PreferenceStoreTest.kt
M app/src/test/kotlin/dev/rubikxplorer/app/data/RoomAttemptRepositoryTest.kt
M app/src/test/kotlin/dev/rubikxplorer/app/data/RoomCheckpointRepositoryTest.kt
M app/src/test/kotlin/dev/rubikxplorer/app/data/RoomPreferencesRepositoryTest.kt
M app/src/test/kotlin/dev/rubikxplorer/app/data/RoomProgressRepositoryTest.kt
M app/src/test/kotlin/dev/rubikxplorer/app/data/RoomSessionSnapshotRepositoryTest.kt
M app/src/test/kotlin/dev/rubikxplorer/app/data/SerialWriterTest.kt
M app/src/test/kotlin/dev/rubikxplorer/app/data/SessionDatabaseMigrationTest.kt
M app/src/test/kotlin/dev/rubikxplorer/app/data/SessionHostAttemptTest.kt
M app/src/test/kotlin/dev/rubikxplorer/app/data/SessionHostCheckpointTest.kt
M app/src/test/kotlin/dev/rubikxplorer/app/data/SessionHostPersistenceTest.kt
M app/src/test/kotlin/dev/rubikxplorer/app/data/SessionHostProgressTest.kt
M app/src/test/kotlin/dev/rubikxplorer/app/flags/BuildConfigFeatureFlagsTest.kt
M app/src/test/kotlin/dev/rubikxplorer/app/flags/BuildConfigUsageTest.kt
M app/src/test/kotlin/dev/rubikxplorer/app/render/Cube3DEmphasisTest.kt
M app/src/test/kotlin/dev/rubikxplorer/app/render/Cube3DRendererTest.kt
M app/src/test/kotlin/dev/rubikxplorer/app/render/GestureModelAgreementTest.kt
M app/src/test/kotlin/dev/rubikxplorer/app/render/Net2DRendererTest.kt
M app/src/test/kotlin/dev/rubikxplorer/app/render/ViewRotationInvarianceTest.kt
M app/src/test/kotlin/dev/rubikxplorer/app/render/gl/CubeMeshTest.kt
M app/src/test/kotlin/dev/rubikxplorer/app/render/gl/CubieModelTest.kt
M app/src/test/kotlin/dev/rubikxplorer/app/render/gl/PresentationQueueTest.kt
M app/src/test/kotlin/dev/rubikxplorer/app/render/gl/ViewProjectionCacheTest.kt
M app/src/test/kotlin/dev/rubikxplorer/app/session/EndToEndSyncVerificationTest.kt
M app/src/test/kotlin/dev/rubikxplorer/app/session/PresentationMappingTest.kt
M app/src/test/kotlin/dev/rubikxplorer/app/session/SessionHostTest.kt
M app/src/test/kotlin/dev/rubikxplorer/app/session/SessionRenderSyncTest.kt
M app/src/test/kotlin/dev/rubikxplorer/app/ui/editor/EditorFixtures.kt
M app/src/test/kotlin/dev/rubikxplorer/app/ui/editor/ManualCubeEditorCompactTest.kt
M app/src/test/kotlin/dev/rubikxplorer/app/ui/editor/ManualCubeEditorStateTest.kt
M app/src/test/kotlin/dev/rubikxplorer/app/ui/editor/ManualCubeEditorTest.kt
M app/src/test/kotlin/dev/rubikxplorer/app/ui/freecube/FreeCubeScreenTest.kt
M app/src/test/kotlin/dev/rubikxplorer/app/ui/freecube/FreeCubeViewModelTest.kt
M app/src/test/kotlin/dev/rubikxplorer/app/ui/freecube/ObjectiveOverlayTest.kt
M app/src/test/kotlin/dev/rubikxplorer/app/ui/lesson/DemonstrationPlayerTest.kt
M app/src/test/kotlin/dev/rubikxplorer/app/ui/lesson/FirstSolveMilestoneTest.kt
M app/src/test/kotlin/dev/rubikxplorer/app/ui/lesson/FrameLabelTest.kt
M app/src/test/kotlin/dev/rubikxplorer/app/ui/lesson/LessonScreenTest.kt
M app/src/test/kotlin/dev/rubikxplorer/app/ui/lesson/PhysicalLessonScreenTest.kt
M app/src/test/kotlin/dev/rubikxplorer/app/ui/lesson/PracticeScreenTest.kt
M app/src/test/kotlin/dev/rubikxplorer/app/ui/lesson/ResetConfirmationTest.kt
M app/src/test/kotlin/dev/rubikxplorer/app/ui/lesson/StageMasteryMilestoneTest.kt
M app/src/test/kotlin/dev/rubikxplorer/app/ui/playback/NetPlaybackTest.kt
M app/src/test/kotlin/dev/rubikxplorer/app/ui/settings/SettingsScreenTest.kt
M app/src/test/kotlin/dev/rubikxplorer/app/ui/shell/AppShellSkipCheckTest.kt
M app/src/test/kotlin/dev/rubikxplorer/app/ui/shell/AppShellTest.kt
M app/src/test/kotlin/dev/rubikxplorer/app/ui/shell/WindowWidthClassTest.kt
M app/src/test/kotlin/dev/rubikxplorer/app/ui/shell/YouScreenTest.kt
M app/src/test/kotlin/dev/rubikxplorer/app/ui/solve/SolutionPlaybackTest.kt
M app/src/test/kotlin/dev/rubikxplorer/app/ui/theme/ThemeTokensTest.kt
M app/src/test/kotlin/dev/rubikxplorer/app/ui/welcome/WelcomeScreenTest.kt
M build-logic/build.gradle.kts
M build-logic/src/main/kotlin/dev/rubikxplorer/buildlogic/AndroidAppConventionPlugin.kt
M build-logic/src/main/kotlin/dev/rubikxplorer/buildlogic/AndroidMacrobenchmarkConventionPlugin.kt
M build-logic/src/main/kotlin/dev/rubikxplorer/buildlogic/CubeCoverageGate.kt
M build-logic/src/main/kotlin/dev/rubikxplorer/buildlogic/JvmCoreConventionPlugin.kt
M build-logic/src/main/kotlin/dev/rubikxplorer/buildlogic/ProjectExtensions.kt
M build-logic/src/main/kotlin/dev/rubikxplorer/buildlogic/QualityConventionPlugin.kt
M build-logic/src/main/kotlin/dev/rubikxplorer/buildlogic/ReleaseConventionPlugin.kt
M build-logic/src/main/kotlin/dev/rubikxplorer/buildlogic/policy/DeclaredDependencyCodec.kt
M build-logic/src/main/kotlin/dev/rubikxplorer/buildlogic/policy/FeatureFlagLevels.kt
M build-logic/src/main/kotlin/dev/rubikxplorer/buildlogic/policy/LearnerTextPolicy.kt
M build-logic/src/main/kotlin/dev/rubikxplorer/buildlogic/policy/ManifestPolicy.kt
M build-logic/src/main/kotlin/dev/rubikxplorer/buildlogic/policy/ModuleBoundaryPolicy.kt
M build-logic/src/main/kotlin/dev/rubikxplorer/buildlogic/policy/SdkDenylistPolicy.kt
M build-logic/src/main/kotlin/dev/rubikxplorer/buildlogic/tasks/CheckLearnerTextTask.kt
M build-logic/src/main/kotlin/dev/rubikxplorer/buildlogic/tasks/CheckMergedManifestTask.kt
M build-logic/src/main/kotlin/dev/rubikxplorer/buildlogic/tasks/CheckModuleBoundariesTask.kt
M build-logic/src/main/kotlin/dev/rubikxplorer/buildlogic/tasks/CheckSdkDenylistTask.kt
M build-logic/src/main/kotlin/dev/rubikxplorer/buildlogic/tasks/GenerateKeepRulesTask.kt
M build-logic/src/main/resources/rubikxplorer-keep-rules.pro
M build-logic/src/test/kotlin/dev/rubikxplorer/buildlogic/policy/DeclaredDependencyCodecTest.kt
M build-logic/src/test/kotlin/dev/rubikxplorer/buildlogic/policy/FeatureFlagLevelsTest.kt
M build-logic/src/test/kotlin/dev/rubikxplorer/buildlogic/policy/LearnerTextPolicyTest.kt
M build-logic/src/test/kotlin/dev/rubikxplorer/buildlogic/policy/ManifestPolicyTest.kt
M build-logic/src/test/kotlin/dev/rubikxplorer/buildlogic/policy/ModuleBoundaryPolicyTest.kt
M build-logic/src/test/kotlin/dev/rubikxplorer/buildlogic/policy/SdkDenylistPolicyTest.kt
M core/application/build.gradle.kts
M core/application/src/main/kotlin/dev/rubikxplorer/application/freecube/FreeCubeEvent.kt
M core/application/src/main/kotlin/dev/rubikxplorer/application/freecube/FreeCubeState.kt
M core/application/src/main/kotlin/dev/rubikxplorer/application/freecube/FreeCubeTransition.kt
M core/application/src/main/kotlin/dev/rubikxplorer/application/freecube/ReduceFreeCube.kt
M core/application/src/main/kotlin/dev/rubikxplorer/application/port/AttemptRepository.kt
M core/application/src/main/kotlin/dev/rubikxplorer/application/port/CheckpointRepository.kt
M core/application/src/main/kotlin/dev/rubikxplorer/application/port/Clock.kt
M core/application/src/main/kotlin/dev/rubikxplorer/application/port/CubeSolver.kt
M core/application/src/main/kotlin/dev/rubikxplorer/application/port/FeatureFlags.kt
M core/application/src/main/kotlin/dev/rubikxplorer/application/port/PreferencesRepository.kt
M core/application/src/main/kotlin/dev/rubikxplorer/application/port/ProgressRepository.kt
M core/application/src/main/kotlin/dev/rubikxplorer/application/port/RandomSource.kt
M core/application/src/main/kotlin/dev/rubikxplorer/application/port/SessionSnapshotRepository.kt
M core/application/src/main/kotlin/dev/rubikxplorer/application/preference/Preferences.kt
M core/application/src/main/kotlin/dev/rubikxplorer/application/preference/UpdatePreference.kt
M core/application/src/main/kotlin/dev/rubikxplorer/application/session/AttemptRecord.kt
M core/application/src/main/kotlin/dev/rubikxplorer/application/session/Checkpoint.kt
M core/application/src/main/kotlin/dev/rubikxplorer/application/session/Effect.kt
M core/application/src/main/kotlin/dev/rubikxplorer/application/session/GuidedRecovery.kt
M core/application/src/main/kotlin/dev/rubikxplorer/application/session/Presentation.kt
M core/application/src/main/kotlin/dev/rubikxplorer/application/session/Reduce.kt
M core/application/src/main/kotlin/dev/rubikxplorer/application/session/SessionEvent.kt
M core/application/src/main/kotlin/dev/rubikxplorer/application/session/SessionKind.kt
M core/application/src/main/kotlin/dev/rubikxplorer/application/session/SessionSnapshot.kt
M core/application/src/main/kotlin/dev/rubikxplorer/application/session/SessionState.kt
M core/application/src/main/kotlin/dev/rubikxplorer/application/session/SyncState.kt
M core/application/src/main/kotlin/dev/rubikxplorer/application/session/Transition.kt
M core/application/src/main/kotlin/dev/rubikxplorer/application/usecase/CompleteLesson.kt
M core/application/src/main/kotlin/dev/rubikxplorer/application/usecase/NewScramble.kt
M core/application/src/main/kotlin/dev/rubikxplorer/application/usecase/PassSkipCheck.kt
M core/application/src/main/kotlin/dev/rubikxplorer/application/usecase/PlaceFromScan.kt
M core/application/src/main/kotlin/dev/rubikxplorer/application/usecase/PlanRecovery.kt
M core/application/src/main/kotlin/dev/rubikxplorer/application/usecase/ResetProgress.kt
M core/application/src/main/kotlin/dev/rubikxplorer/application/usecase/SolveCube.kt
M core/application/src/main/kotlin/dev/rubikxplorer/application/usecase/StagePractice.kt
M core/application/src/test/kotlin/dev/rubikxplorer/application/freecube/ReduceFreeCubeTest.kt
M core/application/src/test/kotlin/dev/rubikxplorer/application/port/FeatureFlagsTest.kt
M core/application/src/test/kotlin/dev/rubikxplorer/application/preference/PreferencesTest.kt
M core/application/src/test/kotlin/dev/rubikxplorer/application/preference/UpdatePreferenceTest.kt
M core/application/src/test/kotlin/dev/rubikxplorer/application/session/AdaptiveAssistanceSessionTest.kt
M core/application/src/test/kotlin/dev/rubikxplorer/application/session/AttemptTest.kt
M core/application/src/test/kotlin/dev/rubikxplorer/application/session/CheckpointTest.kt
M core/application/src/test/kotlin/dev/rubikxplorer/application/session/DiagnosisSessionScenarioTest.kt
M core/application/src/test/kotlin/dev/rubikxplorer/application/session/GuidedRecoverySessionTest.kt
M core/application/src/test/kotlin/dev/rubikxplorer/application/session/HintLadderSessionTest.kt
M core/application/src/test/kotlin/dev/rubikxplorer/application/session/Lessons.kt
M core/application/src/test/kotlin/dev/rubikxplorer/application/session/PhysicalModeSessionTest.kt
M core/application/src/test/kotlin/dev/rubikxplorer/application/session/PlaybackNotCheckpointedTest.kt
M core/application/src/test/kotlin/dev/rubikxplorer/application/session/PracticeSessionTest.kt
M core/application/src/test/kotlin/dev/rubikxplorer/application/session/RecoverySessionTest.kt
M core/application/src/test/kotlin/dev/rubikxplorer/application/session/ReduceTest.kt
M core/application/src/test/kotlin/dev/rubikxplorer/application/session/ReducerPurityTest.kt
M core/application/src/test/kotlin/dev/rubikxplorer/application/session/SessionSnapshotTest.kt
M core/application/src/test/kotlin/dev/rubikxplorer/application/session/ShippedLessonPathsTest.kt
M core/application/src/test/kotlin/dev/rubikxplorer/application/session/SolvePlaybackSessionTest.kt
M core/application/src/test/kotlin/dev/rubikxplorer/application/session/StaleEffectTest.kt
M core/application/src/test/kotlin/dev/rubikxplorer/application/session/StepDerivationScenarioTest.kt
M core/application/src/test/kotlin/dev/rubikxplorer/application/session/TeachingModePreferenceTest.kt
M core/application/src/test/kotlin/dev/rubikxplorer/application/session/TeachingModesScenarioTest.kt
M core/application/src/test/kotlin/dev/rubikxplorer/application/session/TransitionsTableTest.kt
M core/application/src/test/kotlin/dev/rubikxplorer/application/session/TutorEventsTest.kt
M core/application/src/test/kotlin/dev/rubikxplorer/application/usecase/NewScrambleTest.kt
M core/application/src/test/kotlin/dev/rubikxplorer/application/usecase/PlanRecoveryTest.kt
M core/application/src/test/kotlin/dev/rubikxplorer/application/usecase/ProgressWriterTest.kt
M core/application/src/test/kotlin/dev/rubikxplorer/application/usecase/SeededRandomSource.kt
M core/application/src/test/kotlin/dev/rubikxplorer/application/usecase/SolveCubeTest.kt
M core/application/src/test/kotlin/dev/rubikxplorer/application/usecase/StagePracticeTest.kt
M core/application/src/test/kotlin/dev/rubikxplorer/application/usecase/StageProgressUseCasesTest.kt
M core/domain/build.gradle.kts
M core/domain/src/main/kotlin/dev/rubikxplorer/domain/appearance/StickerAppearance.kt
M core/domain/src/main/kotlin/dev/rubikxplorer/domain/content/ContentLoader.kt
M core/domain/src/main/kotlin/dev/rubikxplorer/domain/content/ContentProblem.kt
M core/domain/src/main/kotlin/dev/rubikxplorer/domain/content/ContentValidator.kt
M core/domain/src/main/kotlin/dev/rubikxplorer/domain/content/FaceletString.kt
M core/domain/src/main/kotlin/dev/rubikxplorer/domain/content/IcuArguments.kt
M core/domain/src/main/kotlin/dev/rubikxplorer/domain/content/Ids.kt
M core/domain/src/main/kotlin/dev/rubikxplorer/domain/content/Model.kt
M core/domain/src/main/kotlin/dev/rubikxplorer/domain/cube/Color.kt
M core/domain/src/main/kotlin/dev/rubikxplorer/domain/cube/CubeGeometry.kt
M core/domain/src/main/kotlin/dev/rubikxplorer/domain/cube/CubeState.kt
M core/domain/src/main/kotlin/dev/rubikxplorer/domain/cube/CubeValidator.kt
M core/domain/src/main/kotlin/dev/rubikxplorer/domain/cube/Cubies.kt
M core/domain/src/main/kotlin/dev/rubikxplorer/domain/cube/Face.kt
M core/domain/src/main/kotlin/dev/rubikxplorer/domain/cube/Facelets.kt
M core/domain/src/main/kotlin/dev/rubikxplorer/domain/cube/Move.kt
M core/domain/src/main/kotlin/dev/rubikxplorer/domain/cube/MoveHistory.kt
M core/domain/src/main/kotlin/dev/rubikxplorer/domain/cube/Notation.kt
M core/domain/src/main/kotlin/dev/rubikxplorer/domain/cube/Orientation.kt
M core/domain/src/main/kotlin/dev/rubikxplorer/domain/input/GestureInterpreter.kt
M core/domain/src/main/kotlin/dev/rubikxplorer/domain/input/MoveArrow.kt
M core/domain/src/main/kotlin/dev/rubikxplorer/domain/input/MoveIntent.kt
M core/domain/src/main/kotlin/dev/rubikxplorer/domain/input/TouchPath.kt
M core/domain/src/main/kotlin/dev/rubikxplorer/domain/input/ViewOrbit.kt
M core/domain/src/main/kotlin/dev/rubikxplorer/domain/input/ViewPose.kt
M core/domain/src/main/kotlin/dev/rubikxplorer/domain/playback/Demonstration.kt
M core/domain/src/main/kotlin/dev/rubikxplorer/domain/playback/MoveNote.kt
M core/domain/src/main/kotlin/dev/rubikxplorer/domain/playback/PlaybackController.kt
M core/domain/src/main/kotlin/dev/rubikxplorer/domain/playback/PlaybackItem.kt
M core/domain/src/main/kotlin/dev/rubikxplorer/domain/playback/PlaybackSpeed.kt
M core/domain/src/main/kotlin/dev/rubikxplorer/domain/predicate/AllOf.kt
M core/domain/src/main/kotlin/dev/rubikxplorer/domain/predicate/AlwaysTrue.kt
M core/domain/src/main/kotlin/dev/rubikxplorer/domain/predicate/AnyOf.kt
M core/domain/src/main/kotlin/dev/rubikxplorer/domain/predicate/CubeSolved.kt
M core/domain/src/main/kotlin/dev/rubikxplorer/domain/predicate/CubeSummary.kt
M core/domain/src/main/kotlin/dev/rubikxplorer/domain/predicate/LastLayerCornersPlaced.kt
M core/domain/src/main/kotlin/dev/rubikxplorer/domain/predicate/LastLayerCrossSolved.kt
M core/domain/src/main/kotlin/dev/rubikxplorer/domain/predicate/LastLayerOriented.kt
M core/domain/src/main/kotlin/dev/rubikxplorer/domain/predicate/MiddleLayerSolved.kt
M core/domain/src/main/kotlin/dev/rubikxplorer/domain/predicate/ObjectiveProgress.kt
M core/domain/src/main/kotlin/dev/rubikxplorer/domain/predicate/Piece.kt
M core/domain/src/main/kotlin/dev/rubikxplorer/domain/predicate/Predicate.kt
M core/domain/src/main/kotlin/dev/rubikxplorer/domain/predicate/StateReached.kt
M core/domain/src/main/kotlin/dev/rubikxplorer/domain/predicate/WhiteCornersSolved.kt
M core/domain/src/main/kotlin/dev/rubikxplorer/domain/predicate/WhiteCrossSolved.kt
M core/domain/src/main/kotlin/dev/rubikxplorer/domain/predicate/WhiteLayer.kt
M core/domain/src/main/kotlin/dev/rubikxplorer/domain/predicate/WhitePieceSolved.kt
M core/domain/src/main/kotlin/dev/rubikxplorer/domain/recognition/FaceSample.kt
M core/domain/src/main/kotlin/dev/rubikxplorer/domain/recognition/PhysicalObservation.kt
M core/domain/src/main/kotlin/dev/rubikxplorer/domain/text/Text.kt
M core/domain/src/main/kotlin/dev/rubikxplorer/domain/tutor/AdaptivePolicy.kt
M core/domain/src/main/kotlin/dev/rubikxplorer/domain/tutor/Assess.kt
M core/domain/src/main/kotlin/dev/rubikxplorer/domain/tutor/CandidateEnumerator.kt
M core/domain/src/main/kotlin/dev/rubikxplorer/domain/tutor/Certainty.kt
M core/domain/src/main/kotlin/dev/rubikxplorer/domain/tutor/DiagnosisRules.kt
M core/domain/src/main/kotlin/dev/rubikxplorer/domain/tutor/ErrorCategory.kt
M core/domain/src/main/kotlin/dev/rubikxplorer/domain/tutor/HintLadder.kt
M core/domain/src/main/kotlin/dev/rubikxplorer/domain/tutor/Mastery.kt
M core/domain/src/main/kotlin/dev/rubikxplorer/domain/tutor/MasteryAttempt.kt
M core/domain/src/main/kotlin/dev/rubikxplorer/domain/tutor/MoveFeedback.kt
M core/domain/src/main/kotlin/dev/rubikxplorer/domain/tutor/NextAction.kt
M core/domain/src/main/kotlin/dev/rubikxplorer/domain/tutor/ProgressEvent.kt
M core/domain/src/main/kotlin/dev/rubikxplorer/domain/tutor/Recovery.kt
M core/domain/src/main/kotlin/dev/rubikxplorer/domain/tutor/RecoveryPlanner.kt
M core/domain/src/main/kotlin/dev/rubikxplorer/domain/tutor/StageProgress.kt
M core/domain/src/main/kotlin/dev/rubikxplorer/domain/tutor/StageTrend.kt
M core/domain/src/main/kotlin/dev/rubikxplorer/domain/tutor/TeachingMode.kt
M core/domain/src/main/kotlin/dev/rubikxplorer/domain/tutor/Tutor.kt
M core/domain/src/main/kotlin/dev/rubikxplorer/domain/tutor/TutorDecision.kt
M core/domain/src/test/kotlin/dev/rubikxplorer/domain/appearance/StickerAppearanceTest.kt
M core/domain/src/test/kotlin/dev/rubikxplorer/domain/content/ContentChecks.kt
M core/domain/src/test/kotlin/dev/rubikxplorer/domain/content/ContentLoaderTest.kt
M core/domain/src/test/kotlin/dev/rubikxplorer/domain/content/ContentValidatorTest.kt
M core/domain/src/test/kotlin/dev/rubikxplorer/domain/content/SchemaModelTest.kt
M core/domain/src/test/kotlin/dev/rubikxplorer/domain/content/ShippedContentTest.kt
M core/domain/src/test/kotlin/dev/rubikxplorer/domain/content/TextPartsTest.kt
M core/domain/src/test/kotlin/dev/rubikxplorer/domain/content/VoiceGuide.kt
M core/domain/src/test/kotlin/dev/rubikxplorer/domain/cube/AllMoves.kt
M core/domain/src/test/kotlin/dev/rubikxplorer/domain/cube/CubeStateTest.kt
M core/domain/src/test/kotlin/dev/rubikxplorer/domain/cube/CubeValidatorTest.kt
M core/domain/src/test/kotlin/dev/rubikxplorer/domain/cube/CubiesTest.kt
M core/domain/src/test/kotlin/dev/rubikxplorer/domain/cube/FaceletsTest.kt
M core/domain/src/test/kotlin/dev/rubikxplorer/domain/cube/MoveHistoryTest.kt
M core/domain/src/test/kotlin/dev/rubikxplorer/domain/cube/MoveTest.kt
M core/domain/src/test/kotlin/dev/rubikxplorer/domain/cube/MoveTypesTest.kt
M core/domain/src/test/kotlin/dev/rubikxplorer/domain/cube/NotationTest.kt
M core/domain/src/test/kotlin/dev/rubikxplorer/domain/cube/OrientationTest.kt
M core/domain/src/test/kotlin/dev/rubikxplorer/domain/cube/SingleGateTest.kt
M core/domain/src/test/kotlin/dev/rubikxplorer/domain/cube/SliceFixtureTest.kt
M core/domain/src/test/kotlin/dev/rubikxplorer/domain/cube/StickerReader.kt
M core/domain/src/test/kotlin/dev/rubikxplorer/domain/input/GestureInterpreterTest.kt
M core/domain/src/test/kotlin/dev/rubikxplorer/domain/input/MoveArrowTest.kt
M core/domain/src/test/kotlin/dev/rubikxplorer/domain/input/ViewOrbitTest.kt
M core/domain/src/test/kotlin/dev/rubikxplorer/domain/input/ViewPoseTest.kt
M core/domain/src/test/kotlin/dev/rubikxplorer/domain/playback/DemonstrationTest.kt
M core/domain/src/test/kotlin/dev/rubikxplorer/domain/playback/MoveNoteTest.kt
M core/domain/src/test/kotlin/dev/rubikxplorer/domain/playback/PlaybackControllerTest.kt
M core/domain/src/test/kotlin/dev/rubikxplorer/domain/predicate/AlwaysTrueTest.kt
M core/domain/src/test/kotlin/dev/rubikxplorer/domain/predicate/CombinatorsTest.kt
M core/domain/src/test/kotlin/dev/rubikxplorer/domain/predicate/CubeSolvedTest.kt
M core/domain/src/test/kotlin/dev/rubikxplorer/domain/predicate/CubeSummaryTest.kt
M core/domain/src/test/kotlin/dev/rubikxplorer/domain/predicate/Fixtures.kt
M core/domain/src/test/kotlin/dev/rubikxplorer/domain/predicate/LastLayerCornersPlacedTest.kt
M core/domain/src/test/kotlin/dev/rubikxplorer/domain/predicate/LastLayerCrossSolvedTest.kt
M core/domain/src/test/kotlin/dev/rubikxplorer/domain/predicate/LastLayerOrientedTest.kt
M core/domain/src/test/kotlin/dev/rubikxplorer/domain/predicate/MiddleLayerSolvedTest.kt
M core/domain/src/test/kotlin/dev/rubikxplorer/domain/predicate/ObjectiveProgressTest.kt
M core/domain/src/test/kotlin/dev/rubikxplorer/domain/predicate/PredicateJsonTest.kt
M core/domain/src/test/kotlin/dev/rubikxplorer/domain/predicate/StateReachedTest.kt
M core/domain/src/test/kotlin/dev/rubikxplorer/domain/predicate/WhiteCornersSolvedTest.kt
M core/domain/src/test/kotlin/dev/rubikxplorer/domain/predicate/WhiteCrossSolvedTest.kt
M core/domain/src/test/kotlin/dev/rubikxplorer/domain/predicate/WhitePieceSolvedTest.kt
M core/domain/src/test/kotlin/dev/rubikxplorer/domain/recognition/PhysicalObservationTest.kt
M core/domain/src/test/kotlin/dev/rubikxplorer/domain/recognition/ScanFixtureEvaluationTest.kt
M core/domain/src/test/kotlin/dev/rubikxplorer/domain/tutor/AdaptivePolicyTest.kt
M core/domain/src/test/kotlin/dev/rubikxplorer/domain/tutor/AssessTest.kt
M core/domain/src/test/kotlin/dev/rubikxplorer/domain/tutor/CandidateEnumeratorTest.kt
M core/domain/src/test/kotlin/dev/rubikxplorer/domain/tutor/CertaintyProducerTest.kt
M core/domain/src/test/kotlin/dev/rubikxplorer/domain/tutor/ClassifyTest.kt
M core/domain/src/test/kotlin/dev/rubikxplorer/domain/tutor/DiagnosisScenarioTest.kt
M core/domain/src/test/kotlin/dev/rubikxplorer/domain/tutor/HintLadderTest.kt
M core/domain/src/test/kotlin/dev/rubikxplorer/domain/tutor/MasteryTest.kt
M core/domain/src/test/kotlin/dev/rubikxplorer/domain/tutor/RecoveryPathScenarioTest.kt
M core/domain/src/test/kotlin/dev/rubikxplorer/domain/tutor/RecoveryPlannerTest.kt
M core/domain/src/test/kotlin/dev/rubikxplorer/domain/tutor/StageProgressTest.kt
M core/domain/src/test/kotlin/dev/rubikxplorer/domain/tutor/StageTrendTest.kt
M core/domain/src/test/kotlin/dev/rubikxplorer/domain/tutor/TutorFixtures.kt
M core/domain/src/test/kotlin/dev/rubikxplorer/domain/tutor/TutorTest.kt
M core/domain/src/test/kotlin/dev/rubikxplorer/domain/tutor/TutorVocabularyTest.kt
M core/solver/build.gradle.kts
M core/solver/src/benchmark/kotlin/dev/rubikxplorer/solver/benchmark/CorpusStats.kt
M core/solver/src/benchmark/kotlin/dev/rubikxplorer/solver/benchmark/SolveCorpusBenchmarkMain.kt
M core/solver/src/benchmark/kotlin/dev/rubikxplorer/solver/benchmark/SolveCorpusHarness.kt
M core/solver/src/benchmark/kotlin/dev/rubikxplorer/solver/benchmark/SolverWorkload.kt
M core/solver/src/main/kotlin/dev/rubikxplorer/solver/Coordinates.kt
M core/solver/src/main/kotlin/dev/rubikxplorer/solver/MoveTables.kt
M core/solver/src/main/kotlin/dev/rubikxplorer/solver/PruningTableProvider.kt
M core/solver/src/main/kotlin/dev/rubikxplorer/solver/PruningTables.kt
M core/solver/src/main/kotlin/dev/rubikxplorer/solver/TwoPhaseCubeSolver.kt
M core/solver/src/test/kotlin/dev/rubikxplorer/solver/CoordinatesTest.kt
M core/solver/src/test/kotlin/dev/rubikxplorer/solver/PruningTablesTest.kt
M core/solver/src/test/kotlin/dev/rubikxplorer/solver/TwoPhaseCubeSolverTest.kt
M core/solver/src/test/kotlin/dev/rubikxplorer/solver/benchmark/CorpusStatsTest.kt
M core/solver/src/test/kotlin/dev/rubikxplorer/solver/benchmark/SolveCorpusBenchmarkMainTest.kt
M core/solver/src/test/kotlin/dev/rubikxplorer/solver/benchmark/SolveCorpusHarnessTest.kt
M core/solver/src/test/kotlin/dev/rubikxplorer/solver/benchmark/SolverWorkloadTest.kt
M docs/ARCHITECTURE.md
M docs/benchmarks.md
M docs/pruning-tables.md
M docs/spikes/oq-3-3d-rendering.md
M docs/spikes/oq-4-guided-scan.md
M docs/walkthrough/architecture-walkthrough.html
M gradle/libs.versions.toml
M macrobenchmark/build.gradle.kts
M macrobenchmark/src/main/kotlin/dev/rubikxplorer/macrobenchmark/LessonProcessDeathTest.kt
M macrobenchmark/src/main/kotlin/dev/rubikxplorer/macrobenchmark/Net2DFrameTimingBenchmark.kt
M settings.gradle.kts
# applicationId-rule hits (dev.rubikxplorer -> com.exxerpro.cubentia): 9
A app/build.gradle.kts:18
A build-logic/src/test/kotlin/dev/rubikxplorer/buildlogic/policy/ManifestPolicyTest.kt:16
A build-logic/src/test/kotlin/dev/rubikxplorer/buildlogic/policy/ManifestPolicyTest.kt:94
A docs/pruning-tables.md:28
A docs/pruning-tables.md:29
A docs/spikes/oq-3-3d-rendering.md:141
A docs/spikes/oq-3-3d-rendering.md:144
A macrobenchmark/src/main/kotlin/dev/rubikxplorer/macrobenchmark/LessonProcessDeathTest.kt:119
A macrobenchmark/src/main/kotlin/dev/rubikxplorer/macrobenchmark/Net2DFrameTimingBenchmark.kt:59
# git mv: 23 shallowest paths + 1 file renames below them
R app/schemas/dev.rubikxplorer.app.data.RubikDatabase -> app/schemas/dev.cubentia.app.data.RubikDatabase
R app/src/androidTest/kotlin/dev/rubikxplorer -> app/src/androidTest/kotlin/dev/cubentia
R app/src/debug/kotlin/dev/rubikxplorer -> app/src/debug/kotlin/dev/cubentia
R app/src/main/kotlin/dev/rubikxplorer -> app/src/main/kotlin/dev/cubentia
R app/src/release/kotlin/dev/rubikxplorer -> app/src/release/kotlin/dev/cubentia
R app/src/sharedTest/kotlin/dev/rubikxplorer -> app/src/sharedTest/kotlin/dev/cubentia
R app/src/spike3d/kotlin/dev/rubikxplorer -> app/src/spike3d/kotlin/dev/cubentia
R app/src/spike3dNoSceneView/kotlin/dev/rubikxplorer -> app/src/spike3dNoSceneView/kotlin/dev/cubentia
R app/src/spike3dSceneView/kotlin/dev/rubikxplorer -> app/src/spike3dSceneView/kotlin/dev/cubentia
R app/src/spike3dTest/kotlin/dev/rubikxplorer -> app/src/spike3dTest/kotlin/dev/cubentia
R app/src/spikeScan/kotlin/dev/rubikxplorer -> app/src/spikeScan/kotlin/dev/cubentia
R app/src/test/kotlin/dev/rubikxplorer -> app/src/test/kotlin/dev/cubentia
R build-logic/src/main/kotlin/dev/rubikxplorer -> build-logic/src/main/kotlin/dev/cubentia
R build-logic/src/main/resources/rubikxplorer-keep-rules.pro -> build-logic/src/main/resources/cubentia-keep-rules.pro
R build-logic/src/test/kotlin/dev/rubikxplorer -> build-logic/src/test/kotlin/dev/cubentia
R core/application/src/main/kotlin/dev/rubikxplorer -> core/application/src/main/kotlin/dev/cubentia
R core/application/src/test/kotlin/dev/rubikxplorer -> core/application/src/test/kotlin/dev/cubentia
R core/domain/src/main/kotlin/dev/rubikxplorer -> core/domain/src/main/kotlin/dev/cubentia
R core/domain/src/test/kotlin/dev/rubikxplorer -> core/domain/src/test/kotlin/dev/cubentia
R core/solver/src/benchmark/kotlin/dev/rubikxplorer -> core/solver/src/benchmark/kotlin/dev/cubentia
R core/solver/src/main/kotlin/dev/rubikxplorer -> core/solver/src/main/kotlin/dev/cubentia
R core/solver/src/test/kotlin/dev/rubikxplorer -> core/solver/src/test/kotlin/dev/cubentia
R macrobenchmark/src/main/kotlin/dev/rubikxplorer -> macrobenchmark/src/main/kotlin/dev/cubentia
R app/src/main/kotlin/dev/cubentia/app/RubikXplorerApp.kt -> app/src/main/kotlin/dev/cubentia/app/CubentiaApp.kt
```

</details>

## Code Map

- `app/build.gradle.kts:15-18` -- namespace and applicationId; script maps `dev.rubikxplorer` (bare) to `com.exxerpro.cubentia`.
- `app/src/main/kotlin/dev/rubikxplorer/app/RubikXplorerApp.kt` + `app/src/main/AndroidManifest.xml:9` -- Application class → `CubentiaApp` (file renamed by script second pass).
- `app/src/main/kotlin/dev/rubikxplorer/app/di/PersistenceModule.kt:35` -- DB file name `rubikxplorer.db`.
- `app/src/main/res/values/strings.xml:3,212` -- `app_name`, `welcome_title`.
- `core/domain/src/main/resources/content/strings/en.json:331,352` -- the two "Rubik's cube" strings (not touched by the script; edit by hand).
- `core/domain/src/test/kotlin/dev/rubikxplorer/domain/content/ShippedContentTest.kt` -- shipped-content tests; already reads `:app` sources via `File("../..")` (line ~49); add the guard test here.
- `core/domain/build.gradle.kts:11-22` -- `tasks.test` inputs; add `app/src/main/res/values*/strings.xml` so the guard is not served from cache.
- `macrobenchmark/src/main/kotlin/dev/rubikxplorer/macrobenchmark/{Net2DFrameTimingBenchmark.kt:59,LessonProcessDeathTest.kt:119}` -- target package = applicationId.
- `build-logic/build.gradle.kts`, `gradle/libs.versions.toml:95-99` -- convention-plugin IDs and aliases.
- `build-logic/src/main/kotlin/dev/rubikxplorer/buildlogic/tasks/GenerateKeepRulesTask.kt:26` + `build-logic/src/main/resources/rubikxplorer-keep-rules.pro` -- resource path and file rename move together.
- `settings.gradle.kts:22` -- `rootProject.name`.
- `README.md`, `AGENTS.md`, `docs/`, `.github/copilot-instructions.md`, `Fixtures/README.md` -- prose and command references.

## Tasks & Acceptance

**Execution:**
- `rename_to_cubentia.py` (Design Notes; copy to a temp dir outside the repo) -- run the dry run, confirm it matches the listing above (same counts, same entries), then run with `--apply` -- the one scripted rename, `git mv` included.
- `core/domain/src/main/resources/content/strings/en.json` -- set `msg.milestone.first-solve` to "You solved the cube!" and `msg.practice.stage-07-independent-practice.objective` to "Solve the cube from a fresh scramble." -- AC 4. Update any test that asserts the old wording (search tests for "Rubik's cube").
- `core/domain/src/test/kotlin/dev/cubentia/domain/content/ShippedContentTest.kt` -- add a test: no `bundle.strings` value and no `<string>`/`<item>` text in `../../app/src/main/res/values*/strings.xml` contains "rubik" (ignore case), failing with the offending keys/names; plus a negative case proving the same matcher flags "Rubik's" and "RUBIK" -- AC 4 enforcement.
- `core/domain/build.gradle.kts` -- add `app/src/main/res` `values*/strings.xml` to the `tasks.test` inputs -- cache correctness.
- Formatting -- run `./gradlew spotlessApply` after the rename (shorter identifiers may reflow), then confirm `spotlessCheck`.

**Acceptance Criteria:**
- Given the renamed tree, when `rg -i rubikxplorer --hidden -g '!.git/' -g '!_bmad/' -g '!_bmad-output/' -g '!.claude/' -g '!.agents/' .` runs, then every hit is one of the four protected literal references (`prd-`/`architecture-`/`ux-RubikXplorer-2026-10-29`, `rubikxplorer-copilot-orchestrate`).
- Given the renamed tree, when `git status` / `git diff --cached -M --stat` is inspected after staging, then every moved file shows as a rename (`R`), not delete+add.
- Given the renamed tree, when `./gradlew clean spotlessCheck build`, `./gradlew -p build-logic test` and `./gradlew compileDebugAndroidTestKotlin` run, then all pass, and the merged release manifest declares `package="com.exxerpro.cubentia"`.
- Given `app/build.gradle.kts`, `settings.gradle.kts`, `macrobenchmark/build.gradle.kts`, `strings.xml` and `PersistenceModule.kt`, when read, then they show `com.exxerpro.cubentia`, `Cubentia`, `dev.cubentia.macrobenchmark`, `Cubentia`, and `cubentia.db` respectively.

## Spec Change Log

## Review Triage Log

### 2026-11-08 — Review pass
- verdicts: 23 findings — high 0, medium 1, low 13, false 9, maybe-false 0
- findings:
  - `[low]` `[reject]` (edge) Old `-Prubikxplorer.spike3d/spikeScan` properties are now silently ignored — this only affects the developer, and every doc now shows the new `-Pcubentia.*` name. The fix would add a guard branch.
  - `[low]` `[reject]` (edge) The guard does not scan other values XML, other source-set res, or Kotlin literals — AC 4 scopes it to "content strings and `strings.xml`". `app/src/main/res` holds only `values/strings.xml`. Kotlin literals are already blocked by `checkLearnerText`. The spike labels are not shipped.
  - `[low]` `[patch]` (edge) An `<item name=x>` directly under `<resources>` got an empty parent label — `androidStrings` now prefers the item's own `name`.
  - `[medium]` `[defer]` (edge) Macrobenchmark `TARGET_PACKAGE`/`PACKAGE` are hand-copied literals of the applicationId — both are correct today. Injecting the ID from `:app` is a design change the rename does not cause. Grouped with the verification-gap row below.
  - `[low]` `[reject]` (edge) An existing `dev.rubikxplorer` install keeps its progress orphaned — this is an accepted pre-release reset (Risk Analysis): there are no installed users, and only developer devices are affected.
  - `[false]` `[reject]` (intent) The exclusion scope is wider than the AC's literal list (R1 vs R2) — the story's source SCP rules on this: "RubikXplorer remains the codename in planning documents", and historical `_bmad-output` records and planning folder names are "Not changed". Planning artifacts are also read-only for this build. The remaining hits in AGENTS.md and docs are links to the excluded planning folder names, which the AC itself excludes. `.agents/` holds agent skill trees, the same class as the excluded `.claude/skills/`, and its project-owned Copilot skill name and worktree dir are tied to the repo/folder rename that is deferred to 12.5.
  - `[false]` `[reject]` (intent) The diff cannot prove the dry run was recorded before the writes — the listing is generated against baseline `5ad2749`, and re-running the script on that commit reproduces it. The implementer confirmed that its pre-apply dry run matched byte for byte.
  - `[false]` `[reject]` (intent) The merged release manifest is not checked by a test — this run's Verification checked it on the built artifact: both release merged manifests declare `package="com.exxerpro.cubentia"`. CI runs at the PR.
  - `[low]` `[reject]` (intent) Guard scope — the same as the edge row above.
  - `[low]` `[reject]` (blind) Guard scope (other values files, manifest labels, Kotlin literals) — the same as the edge row above.
  - `[low]` `[patch]` (blind) The string-array `<item>` index counted every item in the document — it now counts the position within the parent.
  - `[false]` `[reject]` (blind) The renamed spike source sets are never compiled — checked: `./gradlew -Pcubentia.spike3d=true -Pcubentia.spikeScan=true :app:compileDebugKotlin :app:compileDebugUnitTestKotlin :app:processDebugMainManifest` succeeded, and spike3d and spikeScan classes were produced under `dev/cubentia/app/`.
  - `[low]` `[reject]` (blind) No developer-facing note to uninstall the old `dev.rubikxplorer` build — there is one developer, and it is recorded in the Risk Analysis.
  - `[low]` `[defer]` (blind) No rule tells readers that the applicationId (`com.exxerpro.cubentia`) differs from the namespace (`dev.cubentia.app`) — the fix edits AGENTS.md, an agent-context file. The SCP plans a `bmad-project-context` refresh after 12.6.
  - `[low]` `[defer]` (blind) The AGENTS.md managed block was hand-edited and its "Verified … against 2cfa628" stamp is stale — agent-context file; the same planned refresh.
  - `[low]` `[reject]` (blind) The `epic-12-context.md` exclusion list differs from the spec's — the context restates the AC, and the per-story spec decides. No later story is harmed by a narrower list.
  - `[false]` `[reject]` (blind) The spec is missing from the diff and the Verification results are unrecorded — the spec is deliberately the claims file, not review content. Results are recorded in the Auto Run Result, and sprint status belongs to the orchestrator.
  - `[low]` `[reject]` (blind) The Risk Analysis omits the "Cubentia" trademark-search risk — the fix edits this spec. The PO owns the search (SCP §5), and the name is a PO decision.
  - `[low]` `[reject]` (blind) The README title is Cubentia but its prose says "Rubik's Cube", with no codename note — the spec deliberately keeps the prose for the 12.5 decision, and README polish is 12.5's scope.
  - `[false]` `[reject]` (blind) Renames near git's similarity threshold could flip to delete+add — the patches did not touch those files. At commit, `git diff --cached -M` shows every move as `R`.
  - `[low]` `[reject]` (blind) The test's XML parser is not hardened against XXE — it parses only repo-local `strings.xml` in a unit test, so this is unlikely to matter, and the fix adds a guard.
  - `[medium]` `[defer]` (verification-gap) Nothing verifies that the macrobenchmark target equals `:app`'s applicationId — grouped with the edge row above. The filed disposition was defer: benchmarks are on demand by design.
  - `[false]` `[reject]` (verification-gap) Copy test expectations changed despite "Never touch test expectation other than package or ID renames" — the spec's own Execution task directs "Update any test that asserts the old wording", which the AC 4 copy change requires.

## Design Notes

The applicationId rule is the subtle part. A bare `dev.rubikxplorer` is the old ID only when no lowercase package segment follows it. So `dev.rubikxplorer.app` is a package, while `dev.rubikxplorer"`, `dev.rubikxplorer/` and `dev.rubikxplorer.DYNAMIC_RECEIVER_…` are the ID. Every other spelling maps by case. Protected literals are masked before the rename and restored after it. Any unexpected casing aborts the run with exit code 1.

Script (`rename_to_cubentia.py`, run with `python3 -I`):

```python
#!/usr/bin/env python3
"""Story 12.6: rename the codename to Cubentia across tracked, non-excluded files.

Usage: python3 -I rename_to_cubentia.py --root <repo> [--apply]
Without --apply it is a dry run: it prints every file whose contents change, the
applicationId-rule hits, and every path to `git mv`, and writes nothing.
"""

import argparse
import re
import subprocess
import sys
from pathlib import Path

EXCLUDED_PREFIXES = ("_bmad/", "_bmad-output/", ".claude/", ".agents/")
APP_ID = "com.exxerpro.cubentia"

# Literal references to excluded paths that must keep resolving (left untouched).
PROTECTED = [
    "prd-RubikXplorer-2026-10-29",
    "architecture-RubikXplorer-2026-10-29",
    "ux-RubikXplorer-2026-10-29",
    "rubikxplorer-copilot-orchestrate",
]

# adb component given relative to the old applicationId: dev.rubikxplorer/.app.X -> appId/dev.cubentia.app.X
ADB_RELATIVE = re.compile(r"dev\.rubikxplorer/\.app\.")
# The bare applicationId: `dev.rubikxplorer` not followed by a word char or by `.<lowercase package segment>`.
APP_ID_RE = re.compile(r"dev\.rubikxplorer(?![\w])(?!\.[a-z])")
CASES = {"rubikxplorer": "cubentia", "RubikXplorer": "Cubentia", "RUBIKXPLORER": "CUBENTIA"}
ANY_CASE = re.compile(r"rubikxplorer", re.IGNORECASE)


def tracked(root: Path) -> list[str]:
    out = subprocess.run(["git", "ls-files", "-z"], cwd=root, check=True, capture_output=True).stdout
    return [p for p in out.decode().split("\0") if p and not p.startswith(EXCLUDED_PREFIXES)]


def rename_text(text: str, file: str, app_id_hits: list[str], odd: list[str]) -> str:
    placeholders = {}
    for i, lit in enumerate(PROTECTED):
        key = f"\0PROTECTED{i}\0"
        placeholders[key] = lit
        text = text.replace(lit, key)
    text = ADB_RELATIVE.sub(f"{APP_ID}/dev.cubentia.app.", text)
    for m in APP_ID_RE.finditer(text):
        line = text.count("\n", 0, m.start()) + 1
        app_id_hits.append(f"{file}:{line}")
    text = APP_ID_RE.sub(APP_ID, text)

    def repl(m: re.Match) -> str:
        s = m.group(0)
        if s not in CASES:
            odd.append(f"{file}: unexpected casing {s!r}")
            return s
        return CASES[s]

    text = ANY_CASE.sub(repl, text)
    for key, lit in placeholders.items():
        text = text.replace(key, lit)
    return text


def rename_path(p: str) -> str:
    return "/".join(ANY_CASE.sub(lambda m: CASES.get(m.group(0), m.group(0)), part) for part in p.split("/"))


def main() -> int:
    ap = argparse.ArgumentParser()
    ap.add_argument("--root", required=True)
    ap.add_argument("--apply", action="store_true")
    args = ap.parse_args()
    root = Path(args.root).resolve()
    files = tracked(root)

    changed, app_id_hits, odd = {}, [], []
    for f in files:
        path = root / f
        try:
            with open(path, encoding="utf-8", newline="") as fh:
                text = fh.read()
        except (UnicodeDecodeError, FileNotFoundError, IsADirectoryError):
            continue
        new = rename_text(text, f, app_id_hits, odd)
        if new != text:
            changed[f] = new

    # Minimal set of moves: the shallowest path component that contains the codename.
    moves = {}
    for f in files:
        parts = f.split("/")
        for i, part in enumerate(parts):
            if ANY_CASE.search(part):
                src = "/".join(parts[: i + 1])
                moves[src] = rename_path(src)
                break

    print(f"# content changes: {len(changed)} files")
    for f in sorted(changed):
        print(f"M {f}")
    print(f"# applicationId-rule hits (dev.rubikxplorer -> {APP_ID}): {len(app_id_hits)}")
    for h in app_id_hits:
        print(f"A {h}")
    # Second pass: files whose own name (below a moved directory) still carries the codename.
    file_moves = {}
    for f in files:
        final = rename_path(f)
        after = f
        for src, dst in moves.items():
            if f == src or f.startswith(src + "/"):
                after = dst + f[len(src):]
                break
        if after != final:
            file_moves[after] = final
    print(f"# git mv: {len(moves)} shallowest paths + {len(file_moves)} file renames below them")
    for src in sorted(moves):
        print(f"R {src} -> {moves[src]}")
    for src in sorted(file_moves):
        print(f"R {src} -> {file_moves[src]}")
    for o in odd:
        print(f"! {o}")
    if odd:
        return 1

    if args.apply:
        for f, new in changed.items():
            with open(root / f, "w", encoding="utf-8", newline="") as fh:
                fh.write(new)
        for src in sorted(moves, key=lambda s: -s.count("/")):
            dst = moves[src]
            (root / dst).parent.mkdir(parents=True, exist_ok=True)
            subprocess.run(["git", "mv", src, dst], cwd=root, check=True)
        for src, dst in sorted(file_moves.items()):
            subprocess.run(["git", "mv", src, dst], cwd=root, check=True)
        print("# applied")
    return 0


if __name__ == "__main__":
    sys.exit(main())
```

## Verification

**Commands:**
- `export JAVA_HOME=/usr/lib/jvm/java-25-openjdk-amd64 ANDROID_HOME=$HOME/Android/Sdk` -- environment (AGENTS.md).
- `./gradlew clean spotlessCheck build` -- expected: BUILD SUCCESSFUL (all JVM tests including the new guard, lint, Kover, policy gates, debug+release APKs).
- `./gradlew -p build-logic test` -- expected: BUILD SUCCESSFUL.
- `./gradlew compileDebugAndroidTestKotlin` -- expected: BUILD SUCCESSFUL.
- `find app/build/intermediates -path '*release*' -name AndroidManifest.xml -path '*merged_manifest*' | xargs grep -o 'package="[^"]*"'` -- expected: `package="com.exxerpro.cubentia"`.
- `rg -i rubikxplorer --hidden -g '!.git/' -g '!_bmad/' -g '!_bmad-output/' -g '!.claude/' -g '!.agents/' .` -- expected: only the four protected literal references.

## Auto Run Result

Status: done

**Summary.** The codename RubikXplorer is renamed to Cubentia by one scripted, dry-run-first rename (`rename_to_cubentia.py`). The dry run matched the recorded listing: 421 content changes, 9 applicationId hits and 24 `git mv` moves.
- `applicationId` is `com.exxerpro.cubentia`.
- Packages and folders are `dev.cubentia.*`, and namespaces are `dev.cubentia.app` and `dev.cubentia.macrobenchmark`.
- `rootProject.name`, `app_name` and `welcome_title` say Cubentia, and the DB file is `cubentia.db`.
- `RubikXplorerApp` is now `CubentiaApp` and `RubikXplorerTheme` is now `CubentiaTheme`.
- Plugin IDs, catalog aliases, `-Pcubentia.*` properties, report dirs and the keep-rules resource are renamed.
- The macrobenchmark targets and the adb docs use the new ID.

The two trademark strings now say "the cube", and a new `ShippedContentTest` guard, with a negative case, keeps "Rubik" out of `en.json` and the app `strings.xml`.

**Files changed (main):**
- `app/build.gradle.kts`: ID and namespace.
- `settings.gradle.kts`: project name.
- `gradle/libs.versions.toml` and `build-logic/build.gradle.kts`: plugin IDs.
- `app/src/main/res/values/strings.xml`: name and welcome.
- `app/src/main/kotlin/dev/cubentia/app/di/PersistenceModule.kt`: DB file.
- `app/src/main/kotlin/dev/cubentia/app/CubentiaApp.kt` and `AndroidManifest.xml`: Application class.
- `macrobenchmark/.../{Net2DFrameTimingBenchmark,LessonProcessDeathTest}.kt`: target ID.
- `build-logic/src/main/resources/cubentia-keep-rules.pro` and `GenerateKeepRulesTask.kt`: keep rules.
- `core/domain/src/main/resources/content/strings/en.json`: trademark copy.
- `core/domain/src/test/.../ShippedContentTest.kt`: trademark guard.
- `core/domain/build.gradle.kts`: `strings.xml` added as test inputs.
- `app/src/test/.../{PhysicalLessonScreenTest,FirstSolveMilestoneTest}.kt`: new copy.
- README, AGENTS.md, `docs/`, `.github/copilot-instructions.md` and `Fixtures/README.md`: prose and commands.
- Every `dev/rubikxplorer/` source folder and `app/schemas/dev.rubikxplorer.app.data.RubikDatabase/`: moved with `git mv`.

**Review findings.** 23 findings: 2 low patches applied (the `androidStrings` labels), 4 rows deferred (macrobenchmark ID drift ×2; AGENTS.md refresh ×2), and 17 rejected, with each reason in the triage log. Follow-up review recommended: false (patched: 2 low, 0 medium, 0 high).

**Verification:**
- `./gradlew clean` then `./gradlew spotlessCheck build`: BUILD SUCCESSFUL. A single `./gradlew clean spotlessCheck build` invocation once hit a transient `:spotlessKotlin` "Could not read path …/build/…" error while a concurrent Gradle run was in progress; the split run passes.
- `./gradlew -p build-logic test`: BUILD SUCCESSFUL.
- `./gradlew compileDebugAndroidTestKotlin`: BUILD SUCCESSFUL.
- Spike variants (`-Pcubentia.spike3d=true -Pcubentia.spikeScan=true`): compile.
- `ShippedContentTest`: 18 tests, 0 failures, including both guard tests. The implementer also showed that the guard fails when "Rubik" is planted in `welcome_title`.
- Both release merged manifests declare `package="com.exxerpro.cubentia"`.
- `rg -i rubikxplorer` with the spec's exclusions finds only the four protected literal references, in `AGENTS.md:20-21`, `docs/ARCHITECTURE.md:6` and `docs/BMAD.md:23`.
- CI runs at the PR.

**Residual risks:**
- Developer and emulator installs appear as a new app, so uninstall `dev.rubikxplorer` by hand.
- For Story 12.5:
  - the `Rubik*` identifiers the spec keeps;
  - README "Rubik's Cube" prose;
  - the GitHub repo and local folder rename, together with the `.agents/skills/rubikxplorer-copilot-orchestrate` skill name and its `../RubikXplorer-worktrees` dir;
  - the "Cubentia" trademark search, which the PO owns.
- A `bmad-project-context` refresh of AGENTS.md is due.

