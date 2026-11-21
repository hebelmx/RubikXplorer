package dev.cubentia.buildlogic

import com.android.build.api.artifact.SingleArtifact
import com.android.build.api.dsl.ApplicationExtension
import com.android.build.api.variant.ApplicationAndroidComponentsExtension
import com.android.build.api.variant.HostTestBuilder
import dev.cubentia.buildlogic.policy.FeatureFlagLevels
import dev.cubentia.buildlogic.policy.ReleaseInputs
import dev.cubentia.buildlogic.tasks.CheckCameraSizeTask
import dev.cubentia.buildlogic.tasks.CheckReleaseSizeTask
import dev.cubentia.buildlogic.tasks.GenerateKeepRulesTask
import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.kotlin.dsl.configure
import org.gradle.kotlin.dsl.getByType
import org.gradle.kotlin.dsl.register

/**
 * Build-type policy: AD-18 flag levels as one `FEATURE_FLAG_LEVELS` BuildConfig field per build
 * type (from [FeatureFlagLevels.byBuildType], via the [FeatureFlagLevelsExtension] TestKit seam),
 * camera composition (Story 10.9: a build type with a camera flag above OFF, and only such a build
 * type, gets the `src/camera` Kotlin + manifest and the CameraX dependencies), and R8 + resource shrinking for release with keep
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
                val flagLevels = featureFlagLevels()
                flagLevels.composesCamera = true
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
                androidComponents.finalizeDsl { android ->
                    android.buildTypes.forEach { buildType ->
                        val levels =
                            requireNotNull(flagLevels.byBuildType[buildType.name]) {
                                "Build type '${buildType.name}' has no feature-flag levels; " +
                                    "add it to FeatureFlagLevels.byBuildType."
                            }
                        val violations = FeatureFlagLevels.cameraViolations(buildType.name, levels)
                        require(violations.isEmpty()) { violations.joinToString("\n") }
                        buildType.buildConfigField("String", FLAG_FIELD, "\"${FeatureFlagLevels.encode(levels)}\"")
                        if (FeatureFlagLevels.isCameraEnabled(levels)) {
                            val sourceSet = android.sourceSets.getByName(buildType.name)
                            sourceSet.kotlin.directories += "src/camera/kotlin"
                            sourceSet.assets.directories += "src/camera/assets"
                            if (FeatureFlagLevels.isCornerModelEnabled(levels)) {
                                sourceSet.kotlin.directories += "src/camera/corner/kotlin"
                                sourceSet.assets.directories += "src/camera/corner/assets"
                            }
                            if (buildType.isDebuggable) {
                                sourceSet.kotlin.directories += "src/camera/debug/kotlin"
                            }
                            listOf("camera2", "lifecycle", "view").forEach { component ->
                                dependencies.add("${buildType.name}Implementation", libs.library("androidx-camera-$component"))
                            }
                        }
                    }
                }
                androidComponents.onVariants { variant ->
                    if (flagLevels.isCameraEnabled(variant.buildType)) {
                        variant.sources.manifests.addStaticManifestFile("src/camera/AndroidManifest.xml")
                        if (variant.debuggable) {
                            variant.sources.manifests.addStaticManifestFile("src/camera/debug/AndroidManifest.xml")
                        }
                    }
                }
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
                        val releaseSize =
                            tasks.register<CheckReleaseSizeTask>("checkReleaseSize") {
                                apk.set(variant.artifacts.get(SingleArtifact.APK_FROM_BUNDLE))
                                bundle.set(variant.artifacts.get(SingleArtifact.BUNDLE))
                                report.set(layout.buildDirectory.file("reports/cubentia/release-size.txt"))
                            }
                        tasks.register<CheckCameraSizeTask>("checkCameraSize") {
                            group = "verification"
                            releaseReport.set(releaseSize.flatMap { it.report })
                            val compile = tasks.named("compileDebugKotlin")
                            dependsOn(compile)
                            cameraClasses.from(
                                compile.map { task ->
                                    task.outputs.files.asFileTree.matching {
                                        include("dev/cubentia/app/camera/**/*.class")
                                        exclude("dev/cubentia/app/camera/recorder/**")
                                    }
                                },
                            )
                            val debug = configurations.getByName("debugRuntimeClasspath").incoming
                            val release = variant.runtimeConfiguration.incoming
                            val cameraComponents =
                                debug.resolutionResult.rootComponent.zip(release.resolutionResult.rootComponent) { withCamera, shipped ->
                                    CheckCameraSizeTask.additionalCameraComponents(withCamera, shipped)
                                }
                            val artifacts = debug.artifacts.resolvedArtifacts
                            cameraRuntime.from(
                                artifacts.zip(cameraComponents) { all, included ->
                                    all.filter { it.id.componentIdentifier in included }.map { it.file }
                                },
                            )
                            cameraAssets.from(
                                fileTree("src/camera/assets"),
                                fileTree("src/camera/corner/assets"),
                            )
                            allowLiteRt.set(flagLevels.isCornerModelEnabled("debug"))
                            runtimeArtifacts.set(
                                artifacts.map { all ->
                                    all.map { "${it.id.componentIdentifier.displayName}/${it.file.name}" }.sorted()
                                },
                            )
                            report.set(layout.buildDirectory.file("reports/cubentia/camera-size.txt"))
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
