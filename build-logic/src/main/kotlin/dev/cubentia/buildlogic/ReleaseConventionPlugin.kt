package dev.cubentia.buildlogic

import com.android.build.api.artifact.SingleArtifact
import com.android.build.api.dsl.ApplicationExtension
import com.android.build.api.variant.ApplicationAndroidComponentsExtension
import com.android.build.api.variant.HostTestBuilder
import dev.cubentia.buildlogic.policy.FeatureFlagLevels
import dev.cubentia.buildlogic.policy.ReleaseInputs
import dev.cubentia.buildlogic.tasks.CheckReleaseSizeTask
import dev.cubentia.buildlogic.tasks.GenerateKeepRulesTask
import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.kotlin.dsl.configure
import org.gradle.kotlin.dsl.getByType
import org.gradle.kotlin.dsl.register

/**
 * Build-type policy: AD-18 flag levels as one `FEATURE_FLAG_LEVELS` BuildConfig field per build
 * type (from [FeatureFlagLevels.byBuildType]), and R8 + resource shrinking for release with keep
 * rules owned by build-logic.
 *
 * Also creates the `benchmark` build type that `:macrobenchmark` measures (NFR-2): a copy of
 * release (R8, shrinking, keep rules, release flag levels, release `DebugOptions`) that is
 * debug-signed so it installs, and profileable from shell only through its own manifest.
 */
class ReleaseConventionPlugin : Plugin<Project> {
    override fun apply(target: Project) =
        with(target) {
            pluginManager.withPlugin("com.android.application") {
                val signing =
                    ReleaseInputs.signing(
                        ReleaseInputs.signingKeys
                            .mapNotNull { key ->
                                providers.gradleProperty(key).orNull?.let { key to it }
                            }.toMap(),
                    )
                val versionCode = ReleaseInputs.versionCode(providers.gradleProperty("cubentia.versionCode").orNull)
                val versionName = ReleaseInputs.versionName(providers.gradleProperty("cubentia.versionName").orNull)
                extensions.configure<ApplicationExtension> {
                    val uploadSigning =
                        signing?.let { inputs ->
                            signingConfigs.create("upload") {
                                storeType = "pkcs12"
                                storeFile = file(inputs.getValue("cubentia.signing.storeFile"))
                                storePassword = inputs.getValue("cubentia.signing.storePassword")
                                keyAlias = inputs.getValue("cubentia.signing.keyAlias")
                                keyPassword = inputs.getValue("cubentia.signing.keyPassword")
                            }
                        }
                    buildTypes.configureEach {
                        buildConfigField("String", FLAG_FIELD, "\"${FeatureFlagLevels.forBuildType(name)}\"")
                    }
                    val release =
                        buildTypes.getByName(RELEASE).apply {
                            signingConfig = uploadSigning
                            isMinifyEnabled = true
                            isShrinkResources = true
                            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"))
                        }
                    buildTypes.create(BENCHMARK) {
                        initWith(release)
                        signingConfig = signingConfigs.getByName("debug")
                        matchingFallbacks += RELEASE
                    }
                    // Per-build-type sources (DebugOptions) come from release, so benchmark ships the same code.
                    sourceSets.getByName(BENCHMARK).kotlin.directories += "src/$RELEASE/kotlin"
                }

                val keepRules =
                    tasks.register<GenerateKeepRulesTask>("generateCubentiaKeepRules") {
                        rules.set(GenerateKeepRulesTask.bundledRules())
                        outputFile.set(layout.buildDirectory.file("generated/cubentia/keep-rules.pro"))
                    }
                val androidComponents = extensions.getByType<ApplicationAndroidComponentsExtension>()
                listOf(RELEASE, BENCHMARK).forEach { buildType ->
                    androidComponents.onVariants(androidComponents.selector().withBuildType(buildType)) { variant ->
                        variant.proguardFiles.add(keepRules.flatMap { it.outputFile })
                    }
                }
                androidComponents.onVariants { variant ->
                    variant.outputs.forEach { output ->
                        versionCode?.let { output.versionCode.set(it) }
                        versionName?.let { output.versionName.set(it) }
                    }
                    if (variant.buildType == RELEASE) {
                        tasks.register<CheckReleaseSizeTask>("checkReleaseSize") {
                            apk.set(variant.artifacts.get(SingleArtifact.APK_FROM_BUNDLE))
                            bundle.set(variant.artifacts.get(SingleArtifact.BUNDLE))
                            report.set(layout.buildDirectory.file("reports/cubentia/release-size.txt"))
                        }
                    }
                }
                // The benchmark variant shares release code; running the Robolectric suite a third time adds nothing.
                androidComponents.beforeVariants(androidComponents.selector().withBuildType(BENCHMARK)) { variant ->
                    variant.hostTests[HostTestBuilder.UNIT_TEST_TYPE]?.enable = false
                }
            }
        }

    private companion object {
        const val FLAG_FIELD = "FEATURE_FLAG_LEVELS"
        const val RELEASE = "release"
        const val BENCHMARK = "benchmark"
    }
}
