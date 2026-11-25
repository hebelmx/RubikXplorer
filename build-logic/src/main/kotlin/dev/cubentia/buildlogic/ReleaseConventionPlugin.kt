package dev.cubentia.buildlogic

import com.android.build.api.artifact.SingleArtifact
import com.android.build.api.dsl.ApplicationExtension
import com.android.build.api.variant.ApplicationAndroidComponentsExtension
import com.android.build.api.variant.HostTestBuilder
import dev.cubentia.buildlogic.policy.CameraSizePolicy
import dev.cubentia.buildlogic.policy.FeatureFlagLevels
import dev.cubentia.buildlogic.policy.ReleaseInputs
import dev.cubentia.buildlogic.tasks.CheckCameraSizeTask
import dev.cubentia.buildlogic.tasks.CheckReleaseSizeTask
import dev.cubentia.buildlogic.tasks.GenerateKeepRulesTask
import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.api.artifacts.ExternalModuleDependency
import org.gradle.api.artifacts.MinimalExternalModuleDependency
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
 *
 * With `-Pcubentia.cornerR8=true` only, also creates `cornerR8` (Story 10.18 / 10.31): release R8 + keep rules with
 * the LiteRT flags raised, debug-signed, made the `testBuildType` so `LiteRtCornerDetectorDeviceTest` and
 * `LiteRtStickerCenterDetectorDeviceTest` prove LiteRT inference survives R8. With `-Pcubentia.release011=true` only,
 * also creates `release011` (Story 10.31): the opt-in minified 0.11.0 camera release profile without test keep rules.
 * Neither opt-in build type ever ships in the default build.
 */
class ReleaseConventionPlugin : Plugin<Project> {
    override fun apply(target: Project) =
        with(target) {
            pluginManager.withPlugin("com.android.application") {
                val flagLevels = featureFlagLevels()
                flagLevels.composesCamera = true
                // Opt-in only (-Pcubentia.cornerR8=true / -Pcubentia.release011=true): absent from the default build.
                val cornerR8 = providers.gradleProperty("cubentia.cornerR8").orNull == "true"
                val release011 = providers.gradleProperty("cubentia.release011").orNull == "true"
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
                    if (cornerR8) {
                        // Story 10.18 / 10.31: R8-minified like release, both LiteRT flags raised, debug-signed, and the
                        // instrumented test build type so LiteRtCornerDetectorDeviceTest + LiteRtStickerCenterDetectorDeviceTest
                        // run against R8 output.
                        buildTypes.create(CORNER_R8) {
                            initWith(release)
                            signingConfig = signingConfigs.getByName("debug")
                            matchingFallbacks += RELEASE
                        }
                        sourceSets.getByName(CORNER_R8).kotlin.directories += "src/$RELEASE/kotlin"
                        sourceSets.getByName("androidTestCornerR8").kotlin.directories += "src/androidTestDebug/kotlin"
                        testBuildType = CORNER_R8
                    }
                    if (release011) {
                        // Story 10.31: opt-in minified 0.11.0-profile build (CAMERA_SCAN=GA, PLACEMENT=BETA, QUICK_SCAN=BETA,
                        // CASE_RECOGNITION=OFF) without test keep rules, for measuring real Play-delivered AAB/APK size and gates.
                        buildTypes.create(RELEASE_011) {
                            initWith(release)
                            signingConfig = signingConfigs.getByName("debug")
                            matchingFallbacks += RELEASE
                        }
                        sourceSets.getByName(RELEASE_011).kotlin.directories += "src/$RELEASE/kotlin"
                    }
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
                            if (FeatureFlagLevels.isContinuousModelEnabled(levels)) {
                                sourceSet.kotlin.directories += "src/camera/continuous/kotlin"
                                sourceSet.assets.directories += "src/camera/continuous/assets"
                            }
                            if (FeatureFlagLevels.isCornerModelEnabled(levels)) {
                                sourceSet.kotlin.directories += "src/camera/corner/kotlin"
                                sourceSet.assets.directories += "src/camera/corner/assets"
                            }
                            if (FeatureFlagLevels.isLiteRtEnabled(levels)) {
                                dependencies.addProvider<MinimalExternalModuleDependency, ExternalModuleDependency>(
                                    "${buildType.name}Implementation",
                                    libs.library("litert"),
                                ) {
                                    // AD-13: ai-delivery is LiteRT's Play AI-pack download path (asset-delivery,
                                    // WorkManager, Play services: boot/network/foreground-service components). The
                                    // model is a bundled asset read by Interpreter, which never touches it.
                                    exclude(mapOf("group" to "com.google.android.play", "module" to "ai-delivery"))
                                }
                                android.androidResources.noCompress += "tflite"
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
                        if (flagLevels.isContinuousModelEnabled(variant.buildType)) {
                            variant.sources.manifests.addStaticManifestFile("src/camera/continuous/AndroidManifest.xml")
                        }
                        if (flagLevels.isCornerModelEnabled(variant.buildType)) {
                            variant.sources.manifests.addStaticManifestFile("src/camera/corner/AndroidManifest.xml")
                        }
                        if (variant.debuggable) {
                            variant.sources.manifests.addStaticManifestFile("src/camera/debug/AndroidManifest.xml")
                        }
                    }
                }
                listOf(RELEASE, BENCHMARK, CORNER_R8, RELEASE_011).forEach { buildType ->
                    androidComponents.onVariants(androidComponents.selector().withBuildType(buildType)) { variant ->
                        variant.proguardFiles.add(keepRules.flatMap { it.outputFile })
                    }
                }
                if (cornerR8) {
                    // The androidTest APK links against the app's own classes, the Kotlin runtime and the libraries the
                    // androidx.test runner shares with the app (androidx.tracing, androidx.annotation), which R8 would
                    // otherwise strip or rename; LiteRT (org.tensorflow.lite) keeps only the shipped keep rules.
                    val testRules =
                        tasks.register<GenerateKeepRulesTask>("generateCornerR8TestKeepRules") {
                            rules.set(CORNER_R8_TEST_RULES)
                            outputFile.set(layout.buildDirectory.file("generated/cubentia/corner-r8-test-rules.pro"))
                        }
                    androidComponents.onVariants(androidComponents.selector().withBuildType(CORNER_R8)) { variant ->
                        variant.proguardFiles.add(testRules.flatMap { it.outputFile })
                    }
                }
                androidComponents.onVariants { variant ->
                    variant.outputs.forEach { output ->
                        versionCode?.let { output.versionCode.set(it) }
                        versionName?.let { output.versionName.set(it) }
                    }
                    if (variant.buildType == RELEASE_011) {
                        tasks.register<CheckReleaseSizeTask>("checkRelease011Size") {
                            group = "verification"
                            description = "Fails when the opt-in 0.11.0-profile release exceeds the 30 MB NFR-11 size budget."
                            apk.set(variant.artifacts.get(SingleArtifact.APK_FROM_BUNDLE))
                            bundle.set(variant.artifacts.get(SingleArtifact.BUNDLE))
                            report.set(layout.buildDirectory.file("reports/cubentia/release011-size.txt"))
                        }
                    }
                    if (variant.buildType == RELEASE) {
                        val releaseSize =
                            tasks.register<CheckReleaseSizeTask>("checkReleaseSize") {
                                apk.set(variant.artifacts.get(SingleArtifact.APK_FROM_BUNDLE))
                                bundle.set(variant.artifacts.get(SingleArtifact.BUNDLE))
                                report.set(layout.buildDirectory.file("reports/cubentia/release-size.txt"))
                            }
                        val releaseHasCamera = flagLevels.isCameraEnabled(RELEASE)
                        val releaseHasContinuous = flagLevels.isContinuousModelEnabled(RELEASE)
                        tasks.register<CheckCameraSizeTask>("checkCameraSize") {
                            group = "verification"
                            releaseReport.set(releaseSize.flatMap { it.report })
                            val excludedClasses = CameraSizePolicy.debugOnlyClassGlobs + CameraSizePolicy.legacyCornerClassGlobs
                            if (!releaseHasContinuous) {
                                val compile = tasks.named("compileDebugKotlin")
                                dependsOn(compile)
                                val classInclude =
                                    if (!releaseHasCamera) {
                                        "dev/cubentia/app/camera/**/*.class"
                                    } else {
                                        "dev/cubentia/app/camera/continuous/**/*.class"
                                    }
                                cameraClasses.from(
                                    compile.map { task ->
                                        task.outputs.files.asFileTree.matching {
                                            include(classInclude)
                                            exclude(excludedClasses)
                                        }
                                    },
                                )
                            }
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
                            if (!releaseHasCamera) {
                                cameraAssets.from(fileTree("src/camera/assets"))
                            }
                            if (!releaseHasContinuous) {
                                cameraAssets.from(fileTree("src/camera/continuous/assets"))
                            }
                            allowLiteRt.set(flagLevels.isLiteRtEnabled("debug"))
                            runtimeArtifacts.set(
                                artifacts.map { all ->
                                    all.map { "${it.id.componentIdentifier.displayName}/${it.file.name}" }.sorted()
                                },
                            )
                            report.set(layout.buildDirectory.file("reports/cubentia/camera-size.txt"))
                        }
                    }
                }
                // The benchmark, cornerR8, and release011 variants share release code; running Robolectric again adds nothing.
                listOf(BENCHMARK, CORNER_R8, RELEASE_011).forEach { buildType ->
                    androidComponents.beforeVariants(androidComponents.selector().withBuildType(buildType)) { variant ->
                        variant.hostTests[HostTestBuilder.UNIT_TEST_TYPE]?.enable = false
                    }
                }
            }
        }

    private companion object {
        const val FLAG_FIELD = "FEATURE_FLAG_LEVELS"
        const val RELEASE = "release"
        const val BENCHMARK = "benchmark"
        const val CORNER_R8 = "cornerR8"
        const val RELEASE_011 = "release011"
        const val CORNER_R8_TEST_RULES =
            "-keep class dev.cubentia.** { *; }\n-keep class kotlin.** { *; }\n-keep class kotlinx.** { *; }\n" +
                "-keep class androidx.tracing.** { *; }\n-keep class androidx.annotation.** { *; }\n"
    }
}
