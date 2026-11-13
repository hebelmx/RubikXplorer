package dev.cubentia.buildlogic

import com.android.build.api.artifact.SingleArtifact
import com.android.build.api.variant.ApplicationAndroidComponentsExtension
import dev.cubentia.buildlogic.tasks.CheckMergedManifestTask
import dev.cubentia.buildlogic.tasks.CheckPrivacyFactsTask
import dev.cubentia.buildlogic.tasks.CheckSdkDenylistTask
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
                val androidComponents = extensions.getByType<ApplicationAndroidComponentsExtension>()
                androidComponents.onVariants { variant ->
                    val suffix = variant.name.replaceFirstChar { it.uppercase() }
                    val manifestCheck =
                        tasks.register<CheckMergedManifestTask>("check${suffix}MergedManifest") {
                            group = LifecycleBasePlugin.VERIFICATION_GROUP
                            description = "Fails when the ${variant.name} merged manifest breaks AD-13."
                            variantName.set(variant.name)
                            debuggable.set(variant.debuggable)
                            mergedManifest.set(variant.artifacts.get(SingleArtifact.MERGED_MANIFEST))
                            report.set(layout.buildDirectory.file("reports/cubentia/${variant.name}/manifest.txt"))
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
                        dependsOn(manifestCheck, denylistCheck)
                    }
                }
            }
            applyAdvisoryDetekt()
        }
}
