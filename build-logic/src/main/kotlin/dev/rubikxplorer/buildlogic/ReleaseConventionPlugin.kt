package dev.rubikxplorer.buildlogic

import com.android.build.api.dsl.ApplicationExtension
import com.android.build.api.variant.ApplicationAndroidComponentsExtension
import dev.rubikxplorer.buildlogic.policy.FeatureFlagLevels
import dev.rubikxplorer.buildlogic.tasks.GenerateKeepRulesTask
import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.kotlin.dsl.configure
import org.gradle.kotlin.dsl.getByType
import org.gradle.kotlin.dsl.register

/**
 * Build-type policy: AD-18 flag levels as one `FEATURE_FLAG_LEVELS` BuildConfig field per build
 * type (from [FeatureFlagLevels.byBuildType]), and R8 + resource shrinking for release with keep
 * rules owned by build-logic.
 */
class ReleaseConventionPlugin : Plugin<Project> {
    override fun apply(target: Project) =
        with(target) {
            pluginManager.withPlugin("com.android.application") {
                extensions.configure<ApplicationExtension> {
                    buildTypes.configureEach {
                        buildConfigField("String", FLAG_FIELD, "\"${FeatureFlagLevels.forBuildType(name)}\"")
                    }
                    buildTypes.named("release") {
                        isMinifyEnabled = true
                        isShrinkResources = true
                        proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"))
                    }
                }

                val keepRules =
                    tasks.register<GenerateKeepRulesTask>("generateRubikXplorerKeepRules") {
                        rules.set(GenerateKeepRulesTask.bundledRules())
                        outputFile.set(layout.buildDirectory.file("generated/rubikxplorer/keep-rules.pro"))
                    }
                val androidComponents = extensions.getByType<ApplicationAndroidComponentsExtension>()
                androidComponents.onVariants(androidComponents.selector().withBuildType("release")) { variant ->
                    variant.proguardFiles.add(keepRules.flatMap { it.outputFile })
                }
            }
        }

    private companion object {
        const val FLAG_FIELD = "FEATURE_FLAG_LEVELS"
    }
}
