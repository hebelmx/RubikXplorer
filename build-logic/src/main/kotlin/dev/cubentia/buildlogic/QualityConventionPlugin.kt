package dev.cubentia.buildlogic

import com.android.build.api.artifact.ScopedArtifact
import com.android.build.api.artifact.SingleArtifact
import com.android.build.api.variant.ApplicationAndroidComponentsExtension
import com.android.build.api.variant.ScopedArtifacts
import dev.cubentia.buildlogic.tasks.CheckCameraClasspathTask
import dev.cubentia.buildlogic.tasks.CheckElfAlignmentTask
import dev.cubentia.buildlogic.tasks.CheckFixtureWriterTask
import dev.cubentia.buildlogic.tasks.CheckMergedManifestTask
import dev.cubentia.buildlogic.tasks.CheckPrivacyFactsTask
import dev.cubentia.buildlogic.tasks.CheckSdkDenylistTask
import dev.cubentia.buildlogic.tasks.CheckShadowReaderTask
import dev.cubentia.buildlogic.tasks.WritePrivacyFactsTask
import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.kotlin.dsl.getByType
import org.gradle.kotlin.dsl.register
import org.gradle.language.base.plugins.LifecycleBasePlugin

/**
 * AD-13 / AD-14 gates for the app: per-variant merged-manifest and SDK-denylist checks wired into
 * `check`, plus advisory (non-blocking) detekt.
 */
class QualityConventionPlugin : Plugin<Project> {
    override fun apply(target: Project) =
        with(target) {
            pluginManager.withPlugin("com.android.application") {
                val flagLevels = featureFlagLevels()
                val androidComponents = extensions.getByType<ApplicationAndroidComponentsExtension>()
                androidComponents.onVariants { variant ->
                    val suffix = variant.name.replaceFirstChar { it.uppercase() }
                    val manifestCheck =
                        tasks.register<CheckMergedManifestTask>("check${suffix}MergedManifest") {
                            group = LifecycleBasePlugin.VERIFICATION_GROUP
                            description = "Fails when the ${variant.name} merged manifest breaks AD-13."
                            variantName.set(variant.name)
                            debuggable.set(variant.debuggable)
                            cameraEnabled.set(flagLevels.isCameraEnabled(variant.buildType))
                            mergedManifest.set(variant.artifacts.get(SingleArtifact.MERGED_MANIFEST))
                            report.set(layout.buildDirectory.file("reports/cubentia/${variant.name}/manifest.txt"))
                        }
                    val cameraCheck =
                        tasks.register<CheckCameraClasspathTask>("check${suffix}CameraClasspath") {
                            group = LifecycleBasePlugin.VERIFICATION_GROUP
                            variantName.set(variant.name)
                            cameraEnabled.set(flagLevels.isCameraEnabled(variant.buildType))
                            liteRtEnabled.set(flagLevels.isLiteRtEnabled(variant.buildType))
                            runtimeCoordinates.set(
                                variant.runtimeConfiguration.incoming.resolutionResult.rootComponent
                                    .map(CheckSdkDenylistTask::coordinates),
                            )
                            report.set(layout.buildDirectory.file("reports/cubentia/${variant.name}/camera-classpath.txt"))
                        }
                    val denylistCheck =
                        tasks.register<CheckSdkDenylistTask>("check${suffix}SdkDenylist") {
                            group = LifecycleBasePlugin.VERIFICATION_GROUP
                            description = "Fails when the ${variant.name} runtime classpath has a denylisted SDK."
                            variantName.set(variant.name)
                            runtimeCoordinates.set(
                                variant.runtimeConfiguration.incoming.resolutionResult.rootComponent
                                    .map(CheckSdkDenylistTask::coordinates),
                            )
                            report.set(layout.buildDirectory.file("reports/cubentia/${variant.name}/sdk-denylist.txt"))
                        }
                    val elfAlignmentCheck =
                        tasks.register<CheckElfAlignmentTask>("check${suffix}ElfAlignment") {
                            group = LifecycleBasePlugin.VERIFICATION_GROUP
                            description = "Fails when any 64-bit native library in ${variant.name} lacks 16 KB ELF page alignment."
                            variantName.set(variant.name)
                            archives.from(
                                variant.runtimeConfiguration.incoming.artifacts.resolvedArtifacts.map { resolved ->
                                    resolved.map { it.file }
                                },
                            )
                            report.set(layout.buildDirectory.file("reports/cubentia/${variant.name}/elf-alignment.txt"))
                        }
                    if (!variant.debuggable) {
                        // Story 10.23 / 10.31: the shadow sticker reader is retired; every non-debuggable variant
                        // (release, benchmark, cornerR8, release011) proves it carries neither the model nor any reader class.
                        val shadowReaderCheck =
                            tasks.register<CheckShadowReaderTask>("check${suffix}ShadowReader") {
                                group = LifecycleBasePlugin.VERIFICATION_GROUP
                                description =
                                    "Fails when ${variant.name} carries the retired Story 10.23 shadow sticker reader or its model."
                                variantName.set(variant.name)
                                mergedAssets.set(variant.artifacts.get(SingleArtifact.ASSETS))
                                report.set(layout.buildDirectory.file("reports/cubentia/${variant.name}/shadow-reader.txt"))
                            }
                        variant.artifacts
                            .forScope(ScopedArtifacts.Scope.PROJECT)
                            .use(shadowReaderCheck)
                            .toGet(ScopedArtifact.CLASSES, CheckShadowReaderTask::classJars, CheckShadowReaderTask::classDirectories)
                        // Story 10.34 (AD-9 Frames): the continuous-session fixture writer is debug-only.
                        val fixtureWriterCheck =
                            tasks.register<CheckFixtureWriterTask>("check${suffix}FixtureWriter") {
                                group = LifecycleBasePlugin.VERIFICATION_GROUP
                                description = "Fails when ${variant.name} carries a debug-only continuous-session fixture-writer class."
                                variantName.set(variant.name)
                                report.set(layout.buildDirectory.file("reports/cubentia/${variant.name}/fixture-writer.txt"))
                            }
                        variant.artifacts
                            .forScope(ScopedArtifacts.Scope.PROJECT)
                            .use(fixtureWriterCheck)
                            .toGet(ScopedArtifact.CLASSES, CheckFixtureWriterTask::classJars, CheckFixtureWriterTask::classDirectories)
                        tasks.named(LifecycleBasePlugin.CHECK_TASK_NAME) { dependsOn(shadowReaderCheck, fixtureWriterCheck) }
                    }
                    if (variant.name == "release") {
                        val coordinates =
                            variant.runtimeConfiguration.incoming.resolutionResult.rootComponent
                                .map(CheckSdkDenylistTask::coordinates)
                        val writerPath = if (path == ":") ":writePrivacyFacts" else "$path:writePrivacyFacts"
                        val facts = rootProject.layout.projectDirectory.file("docs/privacy-facts.json")
                        val writer =
                            tasks.register<WritePrivacyFactsTask>("writePrivacyFacts") {
                                group = LifecycleBasePlugin.VERIFICATION_GROUP
                                description = "Writes release privacy facts for the public privacy page."
                                applicationId.set(variant.applicationId)
                                mergedManifest.set(variant.artifacts.get(SingleArtifact.MERGED_MANIFEST))
                                runtimeCoordinates.set(coordinates)
                                factsFile.set(facts)
                            }
                        val checker =
                            tasks.register<CheckPrivacyFactsTask>("checkPrivacyFacts") {
                                group = LifecycleBasePlugin.VERIFICATION_GROUP
                                description = "Fails when committed privacy facts differ from the release build."
                                applicationId.set(variant.applicationId)
                                mergedManifest.set(variant.artifacts.get(SingleArtifact.MERGED_MANIFEST))
                                runtimeCoordinates.set(coordinates)
                                factsFile.set(facts)
                                writeTaskPath.set(writerPath)
                                report.set(layout.buildDirectory.file("reports/cubentia/release/privacy-facts.txt"))
                                mustRunAfter(writer)
                            }
                        tasks.named(LifecycleBasePlugin.CHECK_TASK_NAME) { dependsOn(checker) }
                    }
                    tasks.named(LifecycleBasePlugin.CHECK_TASK_NAME) {
                        dependsOn(manifestCheck, denylistCheck, cameraCheck, elfAlignmentCheck)
                    }
                }
            }
            applyAdvisoryDetekt()
        }
}
